(ns ataru.db.migrations
  (:require [ataru.application.review-states :as review-states]
            [ataru.applications.application-store :as application-store]
            [ataru.background-job.job :as job]
            [ataru.component-data.person-info-module :as person-info-module]
            [ataru.component-data.value-transformers :as t]
            [ataru.config.core :refer [config]]
            [ataru.db.db :refer [get-datasource]]
            [ataru.db.flyway-migration :as migrations]
            [ataru.db.migrations.application-migration-store :as migration-app-store]
            [ataru.forms.form-store :as store]
            [ataru.hakija.background-jobs.attachment-finalizer-job :as attachment-finalizer-job]
            [ataru.hakija.background-jobs.hakija-jobs :as hakija-jobs]
            [ataru.koodisto.koodisto :as koodisto]
            [ataru.organization-service.ldap-client :as ldap]
            [ataru.person-service.person-integration :as person-integration]
            [ataru.tarjonta-service.tarjonta-client :as tarjonta-client]
            [ataru.util :as util]
            [ataru.util.random :as c]
            [camel-snake-kebab.core :refer [->camelCaseKeyword]]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [clojure.core.match :refer [match]]
            [clojure.java.jdbc :as jdbc :refer [with-db-transaction]]
            [taoensso.timbre :refer [spy debug info error]]
            [ataru.component-data.component :as component])
  (:import (java.time ZonedDateTime ZoneId)))

(def default-fetch-size 50)

(defn- with-query-results-cursor [conn [sql & params :as sql-params] func]
  (with-open [stmt (.prepareStatement (jdbc/get-connection conn) sql)]
    (doseq [[index value] (map vector (iterate inc 1) params)]
      (.setObject stmt index value))
    (.setFetchSize stmt default-fetch-size)
    (with-open [rset (.executeQuery stmt)]
      (func (jdbc/result-set-seq rset)))))

(defn- update-person-info-module
  [new-person-info-module form]
  (clojure.walk/prewalk
    (fn [expr]
      (match expr
        {:module (:or :person-info "person-info")}
        new-person-info-module
        :else expr))
    form))

(defn- update-birth-date-place-holder []
  (doseq [form (->> (store/get-all-forms)
                    (map #(store/fetch-by-id (:id %)))
                    (sort-by :created-time))]
    (store/create-form-or-increment-version!
     (clojure.walk/prewalk
      (fn [expr]
        (match expr
          {:id "birth-date"}
          (assoc-in expr [:params :placeholder]
                    {:fi "pp.kk.vvvv"
                     :sv "dd.mm.åååå"
                     :en "dd.mm.yyyy"})
          :else expr))
      form))))

(defn refresh-person-info-modules []
  (let [new-person-module (person-info-module/person-info-module)]
    (doseq [form (->> (store/get-all-forms)
                      (map #(store/fetch-by-id (:id %)))
                      (sort-by :created-time))]
      (store/create-form-or-increment-version!
       (update-person-info-module new-person-module form)))))

(defn inject-hakukohde-component-if-missing
  "Add hakukohde component to legacy forms (new ones have one added on creation)"
  [form]
  (let [has-hakukohde-component? (-> (filter #(= (keyword (:id %)) :hakukohteet) (get-in form [:content :content]))
                                     (first)
                                     (not-empty))]
    (if has-hakukohde-component?
      nil
      (update-in form [:content :content] #(into [(component/hakukohteet)] %)))))

(defn migrate-legacy-form-content-to-contain-hakukohteet-module [connection]
  (let [update (fn [form conn]
                   (info "Updating followups of form-id:" (:id form))
                   (jdbc/execute! conn ["update forms set content = ? where id = ?" (:content form) (:id form)]))]
    (doseq [form (->> (migration-app-store/get-all-forms connection)
                      (map #(migration-app-store/fetch-by-id (:id %) connection)))]
      (some->
        form
        inject-hakukohde-component-if-missing
        (update connection)))))

(defn application-id->application-key
  "Make application_events to refer to applications using
   applications.key instead of applications.id"
  []
  (let [applications (migration-app-store/get-all-applications)]
    (doseq [application applications
            :let [application-id  (:id application)
                  application-key (:key application)]]
      (doseq [application-event (migration-app-store/get-application-events-by-application-id application-id)
              :when (nil? (:application-key application-event))
              :let [event-id (:id application-event)]]
        (migration-app-store/set-application-key-to-application-event event-id application-key))
      (doseq [confirmation-email (migration-app-store/get-application-confirmation-emails application-id)
              :when (nil? (:application-key confirmation-email))
              :let [confirmation-id (:id confirmation-email)]]
        (migration-app-store/set-application-key-to-application-confirmation-email confirmation-id application-key))
      (let [application-review (migration-app-store/get-application-review-by-application-id application-id)]
        (when (nil? (:application-key application-review))
          (let [review-id (:id application-review)]
            (migration-app-store/set-application-key-to-application-review review-id application-key)))))))

(defn- secrets->keys
  [secrets {:keys [key]}]
  (if-not (contains? secrets key)
    (let [secret (c/url-part 34)]
      (assoc secrets key secret))
    secrets))

(defn- secret->application [application]
  (let [secret (migration-app-store/get-application-secret application)]
    (assoc application :secret secret)))

(defn populate-application-secrets
  []
  (let [applications (->> (migration-app-store/get-all-applications)
                          (map secret->application))
        secrets      (reduce secrets->keys {} applications)]
    (doseq [{:keys [key] :as application} applications
            :let [secret (get secrets key)]]
      (migration-app-store/set-application-secret application secret))))

(defn- get-haku-for-hakukohde
  [hakukohde-oid]
  (info "Loading hakukohde" hakukohde-oid)
  (when-let [haku-oid (:hakuOid (tarjonta-client/get-hakukohde hakukohde-oid))]
    (tarjonta-client/get-haku haku-oid)))

(def memo-get-haku-for-hakukohde (memoize get-haku-for-hakukohde))

(defn- add-haku-details-for-applications
  []
  (doseq [{:keys [id hakukohde]} (migration-app-store/get-applications-without-haku)]
    (if-let [haku (memo-get-haku-for-hakukohde hakukohde)]
      (do
        (migration-app-store/update-application-add-haku id haku)
        (info "Updated haku details for application" id))
      (error "Could not update haku for application" id "with hakukohde" hakukohde))))

(defn- wrap-followups [form]
  (let [fw           (atom nil)
        wrapped-form (clojure.walk/prewalk
                       (fn [expr]
                         (match expr
                           {:followup followup}
                           (do
                             (reset! fw followup)
                             (-> (dissoc expr :followup)
                                 (assoc :followups [followup])))

                           :else expr))
                       form)]
    (when @fw
      wrapped-form)))

(defn followups-to-vectored-followups
  []
  (let [existing-forms (try
                         (map #(store/fetch-by-id (:id %)) (store/get-all-forms))
                         (catch Exception _ []))]
    (doseq [form existing-forms]
      (some-> form
              wrap-followups
              (store/create-form-or-increment-version!)))))

(defn followups-to-vectored-followups-like-all-of-them
  []
  (let [update (fn [form conn]
                 (info "Updating followups of form-id:" (:id form))
                 (jdbc/execute! conn ["update forms set content = ? where id = ?" (:content form) (:id form)]))]
    (with-db-transaction [conn {:datasource (get-datasource :db)}]
      (with-query-results-cursor conn ["select id, content from forms"]
        (fn [forms]
          (doseq [form forms]
            (some->
                form
                wrap-followups
              (update conn))))))))

; oph organization
(def fake-session {:identity
                   {:username      "Admin"
                    :organizations [{:oid "1.2.246.562.10.00000000001"}]}})

(defn- create-new-review-state
  [application]
  (let [application-key (:key application)
        old-review      (application-store/get-application-review application-key)
        old-state       (:state old-review)
        hakukohteet     (if (pos? (count (:hakukohde application)))
                          (:hakukohde application)
                          ["form"])
        [application-state selection-state] (case (keyword old-state)
                                              :unprocessed ["unprocessed" "incomplete"]
                                              :processing ["processing" "incomplete"]
                                              :invited-to-interview ["invited-to-interview" "incomplete"]
                                              :invited-to-exam ["invited-to-exam" "incomplete"]
                                              :evaluating ["evaluating" "incomplete"]
                                              :processed ["processed" "incomplete"]
                                              :inactivated ["inactivated" "incomplete"]
                                              :not-selected ["processed" "reserve"]
                                              :selection-proposal ["processed" "selection-proposal"]
                                              :selected ["processed" "selected"]
                                              :applicant-has-accepted ["processed" "selected"]
                                              :rejected ["processed" "rejected"]
                                              :canceled ["inactivated" "incomplete"])]
    (info "Creating new review state for application" application-key "in state" old-state)
    (when (not= old-state application-state)
      (info "Updating application state:" old-state "->" application-state)
      (application-store/save-application-review (merge old-review {:state application-state}) fake-session))
    (when (= 1 (count hakukohteet))
      (info "Updating hakukohde" (first hakukohteet) "to state" selection-state)
      (application-store/save-application-hakukohde-review
        (:key application)
        (first hakukohteet)
        "selection-state"
        selection-state
        fake-session))))

(defn- application-reviews->new-model
  []
  (doseq [application (migration-app-store/get-all-applications)]
    (create-new-review-state application)))

(defn- dob->dd-mm-yyyy-format []
  (letfn [(invalid-dob-format? [[day month _]]
            (and (some? day)
                 (some? month)
                 (or (< (count day) 2)
                     (< (count month) 2))))
          (application-with-invalid-dob-format? [application]
            (->> application
                 :content
                 :answers
                 (filter (fn [answer]
                           (and (= (:key answer) "birth-date")
                                (not (clojure.string/blank? (:value answer))))))
                 (eduction (map :value)
                           (map #(clojure.string/split % #"\.")))
                 (first)
                 (invalid-dob-format?)))
          (->dd-mm-yyyy-format [application]
            (update-in application [:content :answers] (partial map (fn [answer]
                                                                      (cond-> answer
                                                                        (= (:key answer) "birth-date")
                                                                        (update :value t/birth-date))))))
          (->applications [applications application]
            (if-let [application-key (:key application)]
              (-> applications
                  (update application-key (fnil identity []))
                  (update application-key conj application))
              applications))
          (latest-application-id [applications]
            (->> applications
                 (sort-by :created-time)
                 (last)
                 :id))]
    (let [applications (->> (migration-app-store/get-all-applications)
                            (filter application-with-invalid-dob-format?)
                            (map ->dd-mm-yyyy-format)
                            (reduce ->applications {}))]
      (doseq [[application-key applications] applications]
        (doseq [application applications]
          (info (str "Updating date of birth answer of application " (:id application)))
          (migration-app-store/update-application-content (:id application) (:content application)))
        (when-let [application-id (latest-application-id applications)]
          (info (str "Starting new person service job for application " application-id " (key: " application-key ")"))
          (job/start-job hakija-jobs/job-definitions
                         (:type person-integration/job-definition)
                         {:application-id application-id}))))))

(defn- camel-case-content-keys []
  (doseq [application (migration-app-store/get-all-applications)]
    (let [camel-cased-content (transform-keys ->camelCaseKeyword
                                                (:content application))]
      (when (not= camel-cased-content (:content application))
        (info "Camel casing keywords of application" (:id application))
        (migration-app-store/update-application-content
         (:id application)
         camel-cased-content)))))

(defn- review-notes->own-table []
  (doseq [review-note (->> (migration-app-store/get-all-application-reviews)
                           (filter (comp not clojure.string/blank? :notes)))]
    (let [application-key (:application-key review-note)]
      (info (str "Migrating review notes of application " application-key))
      (migration-app-store/create-application-review-note review-note))))

(defn- application-states-to-hakukohteet
  []
  (let [states->set            #(->> % (map first) (set))
        new-application-states (states->set review-states/application-review-states)
        new-hakukohde-states   (states->set review-states/application-hakukohde-processing-states)]
    (doseq [{:keys [hakukohde key] :as application} (migration-app-store/get-latest-versions-of-all-applications)]
      (let [review (application-store/get-application-review key)
            state  (:state review)]
        (when (and
                (not (contains? new-application-states state))
                (contains? new-hakukohde-states state))
          (doseq [hakukohde-oid-or-form (or (not-empty hakukohde) ["form"])]
            (println "Creating new hakukohde-review" (:key application) (:id application) "->" hakukohde-oid-or-form state)
            (application-store/save-application-hakukohde-review
              key
              hakukohde-oid-or-form
              "processing-state"
              state
              fake-session)))
        (let [new-application-state (if (= state "inactivated")
                                      "inactivated"
                                      "active")]
          (when (not= new-application-state state)
            (println "Updating application review state" key (:id application) state "->" new-application-state)
            (migration-app-store/set-application-state key new-application-state)))))))

(defn- start-attachment-finalizer-job-for-all-applications
  []
  (doseq [application-id (migration-app-store/get-ids-of-latest-applications)]
    (job/start-job hakija-jobs/job-definitions
                   (:type attachment-finalizer-job/job-definition)
                   {:application-id application-id})))

(defn- update-home-town
  [new-home-town-component form]
  (clojure.walk/prewalk
   (fn [e]
     (if (= "home-town" (:id e))
       new-home-town-component
       e))
   form))

(defn- update-kotikunta-answer
  [kunnat application]
  (update-in application [:content :answers]
             (partial map (fn [a]
                            (if (and (= "home-town" (:key a))
                                     (not (clojure.string/blank? (:value a))))
                              (if-let [match (kunnat (clojure.string/lower-case (:value a)))]
                                (assoc a :value match)
                                a)
                              a)))))

(defn- migrate-kotikunta-from-text-to-code
  [connection]
  (let [new-home-town {:fieldClass "formField"
                       :fieldType "dropdown"
                       :id "home-town"
                       :label {:fi "Kotikunta" :sv "Hemkommun" :en "Home town"}
                       :params {}
                       :options [{:value "" :label {:fi "" :sv "" :en ""}}]
                       :validators ["home-town"]
                       :koodisto-source {:uri "kunta" :version 1}
                       :exclude-from-answers-if-hidden true}
        kunnat        (reduce #(assoc %1
                                      (clojure.string/lower-case (:fi (:label %2)))
                                      (:value %2)
                                      (clojure.string/lower-case (:sv (:label %2)))
                                      (:value %2))
                              {}
                              (koodisto/get-koodisto-options "kunta" 1))]
    (doseq [form (migration-app-store/get-1.86-forms connection)
            :let [new-form (update-home-town new-home-town form)]]
      (if (= (:content new-form) (:content form))
        (info "Not updating form" (:key form))
        (let [{:keys [id key]} (migration-app-store/insert-1.86-form connection new-form)]
          (info "Updating form" (:key form))
          (doseq [application (migration-app-store/get-1.86-applications connection key)
                  :let [new-application (update-kotikunta-answer kunnat application)]]
            (if (or (= (:content new-application) (:content application))
                    (not= (:form_id application) (:id form)))
              (info "Not updating application" (:key application))
              (do (info "Updating application" (:key application))
                  (migration-app-store/insert-1.86-application
                   connection
                   (assoc new-application :form_id id))))))))))

(def system-metadata
  {:created-by  {:name "system"
                 :oid  "system"
                 :date "1970-01-01T00:00:00Z"}
   :modified-by {:name "system"
                 :oid  "system"
                 :date "1970-01-01T00:00:00Z"}})

(defn- get-field-metadata
  [virkailija]
  {:created-by  {:name (format "%s %s" (:givenName virkailija) (:sn virkailija))
                 :oid  (:employeeNumber virkailija)
                 :date (ZonedDateTime/now (ZoneId/of "Europe/Helsinki"))}
   :modified-by {:name (format "%s %s" (:givenName virkailija) (:sn virkailija))
                 :oid  (:employeeNumber virkailija)
                 :date (ZonedDateTime/now (ZoneId/of "Europe/Helsinki"))}})

(def get-virkailija (memoize ldap/get-virkailija-by-username))

(defn- migrate-element-metadata-to-forms
  [connection]
  (doseq [id   (migration-app-store/get-1.88-form-ids connection)
          :let [form           (migration-app-store/get-1.88-form connection id)
                virkailija     (get-virkailija (:created_by form))
                field-metadata (get-field-metadata virkailija)]]
    (-> (:content form)
        (update :content
                (fn [content]
                  (for [field content
                        :let  [metadata (if (or (= "hakukohteet" (:id field))
                                                (= "person-info" (:module field)))
                                          system-metadata
                                          field-metadata)]]
                    (clojure.walk/prewalk (fn [x]
                                            (if (and (map? x) (contains? x :fieldType))
                                              (assoc x :metadata metadata)
                                              x))
                                          field))))
        (migration-app-store/update-1.88-form-content (:id form) connection))))

(defn- create-attachment-reviews
  [attachment-field application-key hakutoiveet]
  (let [review-base {:application_key application-key
                     :attachment_key  (:id attachment-field)
                     :state           "not-checked"}]
    (map #(assoc review-base :hakukohde %)
         (cond
           (not-empty (:belongs-to-hakukohteet attachment-field))
           (clojure.set/intersection (set hakutoiveet)
                                     (-> attachment-field :belongs-to-hakukohteet set))

           (not-empty hakutoiveet)
           hakutoiveet

           :else [:form]))))

(defn- migrate-attachment-states-to-applications
  [connection]
  (doseq [res    (migration-app-store/get-1.92-latest-application-key-and-form connection)
          review (->> (migration-app-store/get-1.92-form-by-id connection (:form_id res))
                      :content
                      util/flatten-form-fields
                      (filter #(= "attachment" (:fieldType %)))
                      (map #(create-attachment-reviews % (:key res) (:hakukohde res)))
                      flatten)]
    (migration-app-store/insert-1.92-attachment-review connection review)))

(migrations/defmigration
  migrate-person-info-module "1.13"
  "Update person info module structure in existing forms"
  (refresh-person-info-modules))

(migrations/defmigration
  migrate-person-info-module "1.22"
  "Update person info module structure in existing forms"
  (refresh-person-info-modules))

(migrations/defmigration
  migrate-application-versioning "1.25"
  "Change references to applications.id to be references to applications.key"
  (application-id->application-key))

(migrations/defmigration
  migrate-application-secrets "1.28"
  "Add a secret key to each application in database"
  (populate-application-secrets))

(migrations/defmigration
  migrate-application-haku-ids "1.36"
  "Add haku oids to applications (from tarjonta-service) with hakukohde data"
  (add-haku-details-for-applications))

(migrations/defmigration
  migrate-followups-to-vectored-followups "1.38"
  "Wrap all existing followups with vector"
  (followups-to-vectored-followups))

(migrations/defmigration
  migrate-followups-to-vectored-followups "1.39"
  "Wrap all existing followups with vector, like really all of them ever."
  (followups-to-vectored-followups-like-all-of-them))

(migrations/defmigration
  migrate-application-reviews "1.64"
  "Migrate old per-application reviews to application + hakukohde specific ones"
  (application-reviews->new-model))

(migrations/defmigration
  migrate-birth-date-placeholders "1.70"
  "Add multi lang placeholder texts to birth date question"
  (update-birth-date-place-holder))

(migrations/defmigration
  migrate-dob-into-dd-mm-yyyy-format "1.71"
  "Update date of birth from application answers to dd.mm.yyyy format"
  (dob->dd-mm-yyyy-format))

(migrations/defmigration
  migrate-camel-case-content-keys "1.72"
  "Camel case application content keys"
  (camel-case-content-keys))

(migrations/defmigration
  migrate-person-info-module "1.74"
  "Update person info module structure in existing forms"
  (refresh-person-info-modules))

(migrations/defmigration
  migrate-person-info-module "1.75"
  "Update person info module structure in existing forms"
  (refresh-person-info-modules))

(migrations/defmigration
  migrate-application-review-notes-to-own-table "1.77"
  "Migrate application review notes to application_review_notes table"
  (review-notes->own-table))

(migrations/defmigration
  migrate-application-states-to-hakukohteet "1.80"
  "Move (most) application states to be hakukohde specific"
  (application-states-to-hakukohteet))

(migrations/defmigration
  migrate-start-attachment-finalizer-jobs "1.82"
  "Start attachment finalizer job for all applications"
  (start-attachment-finalizer-job-for-all-applications))

(migrations/defmigration
  migrate-kotikunta-from-text-to-a-code "1.86"
  "Migrate kotikunta from text to a code"
  (with-db-transaction [conn {:connection connection}]
    (migrate-kotikunta-from-text-to-code conn)))

(migrations/defmigration
  update-forms-metadata "1.88"
  "Migrate creator to form elements"
  (with-db-transaction [conn {:connection connection}]
    (migrate-element-metadata-to-forms conn)))

(migrations/defmigration
  migrate-legacy-forms-to-include-hakukohteet-module "1.90"
  "Migrate legacy form content to contain hakukohteet module"
  (with-db-transaction [conn {:connection connection}]
    (migrate-legacy-form-content-to-contain-hakukohteet-module conn)))

(migrations/defmigration
  update-forms-metadata "1.92"
  "Migrate attachment states for applications"
  (with-db-transaction [conn {:connection connection}]
    (migrate-attachment-states-to-applications conn)))

(defn migrate
  []
 (migrations/migrate :db "db.migration" "ataru.db.migrations"))
