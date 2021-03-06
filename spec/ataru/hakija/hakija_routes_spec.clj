(ns ataru.hakija.hakija-routes-spec
  (:require [ataru.util :as util]
            [ataru.applications.application-store :as store]
            [ataru.background-job.job :as job]
            [ataru.fixtures.application :as application-fixtures]
            [ataru.fixtures.db.unit-test-db :as db]
            [ataru.email.application-email-confirmation :as application-email]
            [ataru.hakija.background-jobs.hakija-jobs :as hakija-jobs]
            [ataru.tarjonta-service.tarjonta-service :as tarjonta-service]
            [ataru.organization-service.organization-service :as organization-service]
            [ataru.tarjonta-service.hakuaika :as hakuaika]
            [ataru.hakija.hakija-routes :as routes]
            [ataru.hakija.hakija-application-service :as application-service]
            [ataru.config.core :refer [config]]
            [cheshire.core :as json]
            [ataru.db.db :as ataru-db]
            [ring.mock.request :as mock]
            [speclj.core :refer :all]
            [yesql.core :as sql]
            [ataru.fixtures.form :as form-fixtures]
            [ataru.ohjausparametrit.ohjausparametrit-service :as ohjausparametrit-service]
            [ataru.person-service.person-service :as person-service]))

(sql/defqueries "sql/application-queries.sql")

(def ^:private form (atom nil))

(def application-blank-required-field (assoc-in application-fixtures/person-info-form-application [:answers 0 :value] ""))
(def application-invalid-email-field (assoc-in application-fixtures/person-info-form-application [:answers 2 :value] "invalid@email@foo.com"))
(def application-invalid-phone-field (assoc-in application-fixtures/person-info-form-application [:answers 5 :value] "invalid phone number"))
(def application-invalid-ssn-field (assoc-in application-fixtures/person-info-form-application [:answers 8 :value] "010101-123M"))
(def application-invalid-postal-code (assoc-in application-fixtures/person-info-form-application [:answers 10 :value] "0001"))
(def application-invalid-dropdown-value (assoc-in application-fixtures/person-info-form-application [:answers 12 :value] "kuikka"))
(def application-edited-email (assoc-in application-fixtures/person-info-form-application [:answers 2 :value] "edited@foo.com"))
(def application-edited-ssn (assoc-in application-fixtures/person-info-form-application [:answers 8 :value] "020202A0202"))
(def application-for-hakukohde-edited (-> application-fixtures/person-info-form-application-for-hakukohde
                                          (assoc-in [:answers 11 :value] "SV")
                                          (assoc-in [:answers 2 :value] "edited@foo.com")
                                          (assoc-in [:answers 16 :value] ["57af9386-d80c-4321-ab4a-d53619c14a74_edited"])))
(def application-for-hakukohde-hakukohde-order-edited (-> application-fixtures/person-info-form-application-for-hakukohde
                                                          (assoc :hakukohde [ "1.2.246.562.20.49028196524" "1.2.246.562.20.49028196523"])
                                                          (assoc-in [:answers 17 :value] [ "1.2.246.562.20.49028196524" "1.2.246.562.20.49028196523"])))

(def handler (-> (routes/new-handler)
                 (assoc :tarjonta-service (tarjonta-service/new-tarjonta-service))
                 (assoc :job-runner (job/new-job-runner hakija-jobs/job-definitions))
                 (assoc :organization-service (organization-service/new-organization-service))
                 (assoc :ohjausparametrit-service (ohjausparametrit-service/new-ohjausparametrit-service))
                 (assoc :person-service (person-service/new-person-service))
                 .start
                 :routes))

(defn- parse-body
  [resp]
  (if-not (nil? (:body resp))
    (update resp :body (comp #(json/parse-string % true) slurp))
    resp))

(defmacro with-response
  [method resp application & body]
  `(let [~resp (-> (mock/request ~method "/hakemus/api/application" (json/generate-string ~application))
                   (mock/content-type "application/json")
                   handler
                   parse-body)]
     ~@body))

(defmacro with-get-response
  [secret resp & body]
  `(let [~resp (-> (mock/request :get (str "/hakemus/api/application?secret=" ~secret))
                   (mock/content-type "application/json")
                   handler
                   parse-body)]
     ~@body))

(defmacro with-haku-form-response
  [haku-oid roles resp & body]
  `(let [~resp (-> (mock/request :get (str "/hakemus/api/haku/" ~haku-oid
                                           (when (not-empty ~roles)
                                             (str "?role=" (clojure.string/join
                                                            "&role="
                                                            (map name ~roles))))))
                   (mock/content-type "application/json")
                   handler
                   parse-body)]
     ~@body))

(defn- have-any-application-in-db
  []
  (let [app-count
        (+ (count (ataru-db/exec
                    :db
                    yesql-get-application-list-for-virkailija
                    (merge {:form                   nil
                            :application_oid        nil
                            :application_oids       nil
                            :person_oid             nil
                            :name                   nil
                            :email                  nil
                            :dob                    nil
                            :ssn                    nil
                            :haku                   nil
                            :hakukohde              nil
                            :ensisijainen_hakukohde nil}
                           {:query_key   "hakukohde"
                            :query_value (:hakukohde @form)})))
           (count (ataru-db/exec
                   :db
                   yesql-get-application-list-for-virkailija
                   (merge {:form                   nil
                           :application_oid        nil
                           :application_oids       nil
                           :person_oid             nil
                           :name                   nil
                           :email                  nil
                           :dob                    nil
                           :ssn                    nil
                           :haku                   nil
                           :hakukohde              nil
                           :ensisijainen_hakukohde nil}
                          {:query_key   "form"
                           :query_value (:key @form)}))))]
    (< 0 app-count)))

(defmacro add-failing-post-spec
  [desc fixture]
  `(it ~desc
     (with-response :post resp# ~fixture
       (should= 400 (:status resp#))
       (should-not (have-any-application-in-db)))))

(defn- get-application-by-id [id]
  (first (ataru-db/exec :db yesql-get-application-by-id {:application_id id})))

(defn- have-application-in-db
  [application-id]
  (when-let [actual (get-application-by-id application-id)]
    (= (:form application-fixtures/person-info-form-application) (:form actual))))

(defn- have-application-for-hakukohde-in-db
  [application-id]
  (when-let [actual (get-application-by-id application-id)]
    (= (:form application-fixtures/person-info-form-application-for-hakukohde) (:form actual))))

(defn- cannot-edit? [field] (true? (:cannot-edit field)))

(defn- cannot-view? [field] (true? (:cannot-view field)))

(defn- get-answer
  [application key]
  (->> application
       :content
       :answers
       (filter #(= (:key %) key))
       first
       :value))

(defn- hakuaika-ongoing
  [_ _ _]
  {:on                                  true
   :start                               (- (System/currentTimeMillis) (* 2 24 3600 1000))
   :end                                 (+ (System/currentTimeMillis) (* 2 24 3600 1000))
   :hakukierros-end                     nil
   :jatkuva-haku?                       false
   :attachment-modify-grace-period-days (-> config :public-config :attachment-modify-grace-period-days)})

(defn- hakuaika-ended
  [_ _ _]
  {:on                                  false
   :start                               (- (System/currentTimeMillis) (* 2 24 3600 1000))
   :end                                 (- (System/currentTimeMillis) (* 2 24 3600 1000))
   :hakukierros-end                     nil
   :jatkuva-haku?                       false
   :attachment-modify-grace-period-days nil})

(defn- hakuaika-ended-within-grace-period
  [_ _ _]
  (let [edit-grace-period (-> config :public-config :attachment-modify-grace-period-days)
        start             (* 2 edit-grace-period)
        end               (quot edit-grace-period 2)]
    {:on                                  false
     :start                               (- (System/currentTimeMillis) (* start 24 3600 1000))
     :end                                 (- (System/currentTimeMillis) (* end 24 3600 1000))
     :hakukierros-end                     nil
     :jatkuva-haku?                       false
     :attachment-modify-grace-period-days edit-grace-period}))

(defn- hakuaika-ended-within-grace-period-hakukierros-ongoing
  [_ _ _]
  (let [edit-grace-period (-> config :public-config :attachment-modify-grace-period-days)
        start             (* 2 edit-grace-period)
        end               (quot edit-grace-period 2)]
    {:on                                  false
     :start                               (- (System/currentTimeMillis) (* start 24 3600 1000))
     :end                                 (- (System/currentTimeMillis) (* end 24 3600 1000))
     :hakukierros-end                     (+ (System/currentTimeMillis) (* 2 24 3600 1000))
     :jatkuva-haku?                       false
     :attachment-modify-grace-period-days edit-grace-period}))

(defn- hakuaika-ended-grace-period-passed-hakukierros-ongoing
  [_ _ _]
  (let [edit-grace-period (-> config :public-config :attachment-modify-grace-period-days)
        start             (* 2 edit-grace-period)
        end               (+ edit-grace-period 1)]
    {:on                                  false
     :start                               (- (System/currentTimeMillis) (* start 24 3600 1000))
     :end                                 (- (System/currentTimeMillis) (* end 24 3600 1000))
     :hakukierros-end                     (+ (System/currentTimeMillis) (* 2 24 3600 1000))
     :jatkuva-haku?                       false
     :attachment-modify-grace-period-days edit-grace-period}))

(describe "/haku"
  (tags :unit :hakija-routes)

  (around [spec]
    (with-redefs [application-email/start-email-submit-confirmation-job (fn [_ _ _])]
      (spec)))

  (before
   (reset! form (db/init-db-fixture form-fixtures/person-info-form)))

  (it "should get form"
    (with-redefs [hakuaika/get-hakuaika-info hakuaika-ongoing]
      (with-haku-form-response "1.2.246.562.29.65950024186" [:hakija :with-henkilo] resp
        (should= 200 (:status resp))
        (let [fields (-> resp :body :content util/flatten-form-fields)]
          (should= (map :id (filter cannot-edit? fields))
                   ["first-name" "preferred-name" "last-name" "nationality" "have-finnish-ssn" "ssn" "birth-date" "gender" "language"])
           (should= (map :id (filter cannot-view? fields))
                   ["ssn" "birth-date"])))))

  (it "should get form as virkailija"
    (with-redefs [hakuaika/get-hakuaika-info hakuaika-ongoing]
      (with-haku-form-response "1.2.246.562.29.65950024186" [:virkailija :with-henkilo] resp
        (should= 200 (:status resp))
        (let [fields (-> resp :body :content util/flatten-form-fields)]
          (should= (map :id (remove cannot-edit? fields))
                   ["hakukohteet" "birthplace" "passport-number" "national-id-number" "email" "phone" "country-of-residence" "address" "postal-code" "postal-office" "home-town" "city" "b0839467-a6e8-4294-b5cc-830756bbda8a" "164954b5-7b23-4774-bd44-dee14071316b"])
          (should= (map :id (filter cannot-edit? fields))
                   ["first-name" "preferred-name" "last-name" "nationality" "have-finnish-ssn" "ssn" "birth-date" "gender" "language"])
          (should= (map :id (filter cannot-view? fields))
                   [])))))

  (it "should get form as virkailija without henkilo"
    (with-redefs [hakuaika/get-hakuaika-info hakuaika-ongoing]
      (with-haku-form-response "1.2.246.562.29.65950024186" [:virkailija] resp
        (should= 200 (:status resp))
        (let [fields (-> resp :body :content util/flatten-form-fields)]
          (should= (map :id (remove cannot-edit? fields))
                   ["hakukohteet" "first-name" "preferred-name" "last-name" "nationality" "have-finnish-ssn" "ssn" "birth-date" "gender" "birthplace" "passport-number" "national-id-number" "email" "phone" "country-of-residence" "address" "postal-code" "postal-office" "home-town" "city" "language" "b0839467-a6e8-4294-b5cc-830756bbda8a" "164954b5-7b23-4774-bd44-dee14071316b"])
          (should= (map :id (filter cannot-edit? fields))
                   [])
          (should= (map :id (filter cannot-view? fields))
                   [])))))

  (it "should get application with hakuaika ended"
    (with-redefs [hakuaika/get-hakuaika-info hakuaika-ended-within-grace-period]
      (with-haku-form-response "1.2.246.562.29.65950024186" [:hakija :with-henkilo] resp
        (should= 200 (:status resp))
        (let [fields (-> resp :body :content util/flatten-form-fields)]
          (should= (map :id (remove cannot-edit? fields))
                   ["164954b5-7b23-4774-bd44-dee14071316b"])
          (should= (map :id (filter cannot-edit? fields))
                   ["hakukohteet" "first-name" "preferred-name" "last-name" "nationality" "have-finnish-ssn" "ssn" "birth-date" "gender" "birthplace" "passport-number" "national-id-number" "email" "phone" "country-of-residence" "address" "postal-code" "postal-office" "home-town" "city" "language" "b0839467-a6e8-4294-b5cc-830756bbda8a"])
          (should= (map :id (filter cannot-view? fields))
                   ["ssn" "birth-date"])))))

  (it "should get application with hakuaika ended as virkailija"
    (with-redefs [hakuaika/get-hakuaika-info hakuaika-ended-within-grace-period]
      (with-haku-form-response "1.2.246.562.29.65950024186" [:virkailija :with-henkilo] resp
        (should= 200 (:status resp))
        (let [fields (-> resp :body :content util/flatten-form-fields)]
          (should= (map :id (remove cannot-edit? fields))
                   ["hakukohteet" "birthplace" "passport-number" "national-id-number" "email" "phone" "country-of-residence" "address" "postal-code" "postal-office" "home-town" "city" "b0839467-a6e8-4294-b5cc-830756bbda8a" "164954b5-7b23-4774-bd44-dee14071316b"])
          (should= (map :id (filter cannot-edit? fields))
                   ["first-name" "preferred-name" "last-name" "nationality" "have-finnish-ssn" "ssn" "birth-date" "gender" "language"])
          (should= (map :id (filter cannot-view? fields))
                   [])))))

  (it "should get application with hakuaika ended but hakukierros ongoing"
    (with-redefs [hakuaika/get-hakuaika-info hakuaika-ended-grace-period-passed-hakukierros-ongoing]
      (with-haku-form-response "1.2.246.562.29.65950024186" [:hakija :with-henkilo] resp
        (should= 200 (:status resp))
        (let [fields (-> resp :body :content util/flatten-form-fields)]
          (should= (map :id (remove cannot-edit? fields))
                   ["birthplace" "passport-number" "national-id-number" "email" "phone" "country-of-residence" "address" "postal-code" "home-town" "city"])
          (should= (map :id (filter cannot-edit? fields))
                   ["hakukohteet" "first-name" "preferred-name" "last-name" "nationality" "have-finnish-ssn" "ssn" "birth-date" "gender" "postal-office" "language" "b0839467-a6e8-4294-b5cc-830756bbda8a" "164954b5-7b23-4774-bd44-dee14071316b"])
          (should= (map :id (filter cannot-view? fields))
                   ["ssn" "birth-date"])))))

  (it "should get application with hakuaika ended but hakukierros ongoing as virkailija"
    (with-redefs [hakuaika/get-hakuaika-info hakuaika-ended-grace-period-passed-hakukierros-ongoing]
      (with-haku-form-response "1.2.246.562.29.65950024186" [:virkailija :with-henkilo] resp
        (should= 200 (:status resp))
        (let [fields (-> resp :body :content util/flatten-form-fields)]
          (should= (map :id (remove cannot-edit? fields))
                   ["hakukohteet" "birthplace" "passport-number" "national-id-number" "email" "phone" "country-of-residence" "address" "postal-code" "postal-office" "home-town" "city" "b0839467-a6e8-4294-b5cc-830756bbda8a" "164954b5-7b23-4774-bd44-dee14071316b"])
          (should= (map :id (filter cannot-edit? fields))
                   ["first-name" "preferred-name" "last-name" "nationality" "have-finnish-ssn" "ssn" "birth-date" "gender" "language"])
          (should= (map :id (filter cannot-view? fields))
                   []))))))

(describe "/application"
  (tags :unit :hakija-routes)

  (describe "POST application"
    (around [spec]
      (with-redefs [application-email/start-email-submit-confirmation-job (fn [_ _ _])
                    hakuaika/get-hakuaika-info                            hakuaika-ongoing]
        (spec)))

    (before
      (reset! form (db/init-db-fixture form-fixtures/person-info-form)))

    (it "should validate application for hakukohde"
        (with-redefs [hakuaika/get-hakuaika-info hakuaika-ongoing]
          (with-response :post resp application-fixtures/person-info-form-application-for-hakukohde
                         (should= 200 (:status resp))
                         (should (have-application-for-hakukohde-in-db (get-in resp [:body :id]))))))

    (it "should validate application"
      (with-response :post resp application-fixtures/person-info-form-application
        (should= 200 (:status resp))
        (should (have-application-in-db (get-in resp [:body :id])))))

    (it "should validate application for hakukohde"
      (with-response :post resp application-fixtures/person-info-form-application-for-hakukohde
        (should= 200 (:status resp))
        (should (have-application-for-hakukohde-in-db (get-in resp [:body :id])))))

    (it "should not validate application with extra answers"
      (with-response :post resp application-fixtures/person-info-form-application-with-extra-answer
        (should= 400 (:status resp))
        (should= {:failures {:extra-answers ["extra-answer-key"]}} (:body resp))))

    (add-failing-post-spec "should not validate form with blank required field" application-blank-required-field)

    (add-failing-post-spec "should not validate form with invalid email field" application-invalid-email-field)

    (add-failing-post-spec "should not validate form with invalid phone field" application-invalid-phone-field)

    (add-failing-post-spec "should not validate form with invalid ssn field" application-invalid-ssn-field)

    (add-failing-post-spec "should not validate form with invalid postal code field" application-invalid-postal-code)

    (add-failing-post-spec "should not validate form with invalid dropdown field" application-invalid-dropdown-value))

  (describe "GET application"
    (around [spec]
      (with-redefs [application-email/start-email-submit-confirmation-job (fn [_ _ _])
                    hakuaika/get-hakuaika-info                            hakuaika-ongoing]
        (spec)))

    (before-all
      (reset! form (db/init-db-fixture form-fixtures/person-info-form)))

    (it "should create"
        (with-redefs [store/generate-new-application-secret (constantly "12345")]
          (with-response :post resp application-fixtures/person-info-form-application-for-hakukohde
                         (should= 200 (:status resp))
                         (should (have-application-in-db (get-in resp [:body :id]))))))

    (it "should not get application with wrong secret"
      (with-get-response "asdfasfas" resp
        (should= 404 (:status resp))))

    (it "should get application"
      (with-redefs [hakuaika/get-hakuaika-info hakuaika-ongoing]
        (with-get-response "12345" resp
          (should= 200 (:status resp)))))

    (it "should get application with hakuaika ended"
      (with-redefs [hakuaika/get-hakuaika-info hakuaika-ended-within-grace-period]
        (with-get-response "12345" resp
          (should= 200 (:status resp)))))

    (it "should get application with hakuaika ended but hakukierros ongoing"
      (with-redefs [hakuaika/get-hakuaika-info hakuaika-ended-grace-period-passed-hakukierros-ongoing]
        (with-get-response "12345" resp
          (should= 200 (:status resp))))))

  (describe "PUT application"
    (around [spec]
      (with-redefs [application-email/start-email-submit-confirmation-job (fn [_ _ _])
                    application-email/start-email-edit-confirmation-job   (fn [_ _ _])
                    application-service/remove-orphan-attachments         (fn [_ _])
                    hakuaika/get-hakuaika-info                            hakuaika-ongoing]
        (spec)))

    (before-all
      (reset! form (db/init-db-fixture form-fixtures/person-info-form)))

    (it "should create"
      (with-redefs [store/generate-new-application-secret (constantly "0000000010")]
        (with-response :post resp application-fixtures/person-info-form-application
          (should= 200 (:status resp))
          (should (have-application-in-db (get-in resp [:body :id]))))))

    (it "should edit application"
      (with-redefs [store/generate-new-application-secret (constantly "0000000011")]
        (with-response :put resp (merge application-edited-email {:secret "0000000010"})
          (should= 200 (:status resp))
          (let [id          (-> resp :body :id)
                application (get-application-by-id id)]
            (should= "edited@foo.com" (get-answer application "email"))))))

    (it "should not allow editing ssn"
      (with-redefs [store/generate-new-application-secret (constantly "0000000012")]
        (with-response :put resp (merge application-edited-ssn {:secret "0000000011"})
          (should= 200 (:status resp))
          (let [id          (-> resp :body :id)
                application (get-application-by-id id)]
            (should= "010101A123N" (get-answer application "ssn"))))))

    (it "should create for hakukohde with hakukohde order check"
      (with-redefs [hakuaika/get-hakuaika-info            hakuaika-ongoing
                    store/generate-new-application-secret (constantly "0000000013")]
        (with-response :post resp application-fixtures/person-info-form-application-for-hakukohde
          (should= 200 (:status resp))
          (should (have-application-in-db (get-in resp [:body :id])))
          (should= ["1.2.246.562.20.49028196523" "1.2.246.562.20.49028196524"]
                   (->> (get-application-by-id (-> resp :body :id))
                        :content
                        :answers
                        (filter #(= "hakukohteet" (:key %)))
                        first
                        :value)))))

    (it "should change hakukohde order"
      (with-response :put resp (merge application-for-hakukohde-hakukohde-order-edited {:secret "0000000013"})
        (should= 200 (:status resp))
        (should= ["1.2.246.562.20.49028196524" "1.2.246.562.20.49028196523"]
                 (->> (get-application-by-id (-> resp :body :id))
                      :content
                      :answers
                      (filter #(= "hakukohteet" (:key %)))
                      first
                      :value)))))

  (describe "PUT application after hakuaika ended"
    (around [spec]
      (with-redefs [application-email/start-email-submit-confirmation-job (fn [_ _ _])
                    application-email/start-email-edit-confirmation-job   (fn [_ _ _])
                    application-service/remove-orphan-attachments         (fn [_ _])]
        (spec)))

    (before-all
      (reset! form (db/init-db-fixture form-fixtures/person-info-form)))

    (it "should create"
      (with-redefs [hakuaika/get-hakuaika-info hakuaika-ongoing
                    store/generate-new-application-secret (constantly "0000000020")]
        (with-response :post resp application-fixtures/person-info-form-application-for-hakukohde
          (should= 200 (:status resp))
          (should (have-application-in-db (get-in resp [:body :id]))))))

    (it "should allow application edit after hakuaika within 10 days and only changes to attachments and limited person info"
      (with-redefs [hakuaika/get-hakuaika-info hakuaika-ended-within-grace-period-hakukierros-ongoing
                    store/generate-new-application-secret (constantly "0000000021")]
        (with-response :put resp (merge application-for-hakukohde-edited {:secret "0000000020"})
          (should= 200 (:status resp))
          (let [id          (-> resp :body :id)
                application (get-application-by-id id)]
            (should= "FI" (get-answer application "language"))
            (should= "edited@foo.com" (get-answer application "email"))
            (should= ["57af9386-d80c-4321-ab4a-d53619c14a74_edited"]
                     (get-answer application "164954b5-7b23-4774-bd44-dee14071316b"))))))

    (it "should allow application edit after grace period and only changes to limited person info"
      (with-redefs [hakuaika/get-hakuaika-info hakuaika-ended-grace-period-passed-hakukierros-ongoing
                    store/generate-new-application-secret (constantly "0000000022")]
        (with-response :put resp (merge application-fixtures/person-info-form-application-for-hakukohde {:secret "0000000021"})
          (should= 200 (:status resp))
          (let [id          (-> resp :body :id)
                application (get-application-by-id id)]
            (should= "FI" (get-answer application "language"))
            (should= "aku@ankkalinna.com" (get-answer application "email"))
            (should= ["57af9386-d80c-4321-ab4a-d53619c14a74_edited"]
                     (get-answer application "164954b5-7b23-4774-bd44-dee14071316b")))))))

  (describe "Tests for a more complicated form"
    (around [spec]
      (with-redefs [application-email/start-email-submit-confirmation-job (fn [_ _ _])
                    application-email/start-email-edit-confirmation-job   (fn [_ _ _])
                    application-service/remove-orphan-attachments         (fn [_ _])
                    hakuaika/get-hakuaika-info                            hakuaika-ongoing]
        (spec)))

    (before-all
      (reset! form (db/init-db-fixture form-fixtures/person-info-form-with-more-questions)))

    (it "should not create"
      (with-response :post resp application-fixtures/person-info-form-application
        (should= 400 (:status resp))
        (should= {:failures {:adjacent-answer-1            {:passed? false}
                             :repeatable-required          {:passed? false}
                             :more-questions-attachment-id {:passed? false}}}
                 (:body resp))))

    (it "should create"
      (with-redefs [store/generate-new-application-secret (constantly "0000000030")]
        (with-response :post resp application-fixtures/person-info-form-application-with-more-answers
          (should= 200 (:status resp))
          (should (have-application-in-db (get-in resp [:body :id]))))))

    (it "should update answers"
      (with-redefs [store/generate-new-application-secret (constantly "0000000031")]
        (with-response :put resp (merge application-fixtures/person-info-form-application-with-more-modified-answers {:secret "0000000030"})
          (should= 200 (:status resp))
          (let [id          (-> resp :body :id)
                application (get-application-by-id id)]
            (should= "Toistuva pakollinen 4" (last (get-answer application "repeatable-required")))
            (should= "modified-attachment-id" (get-answer application "more-questions-attachment-id"))
            (should= "Vierekkäinen vastaus 2" (get-answer application "adjacent-answer-2"))
            (should= "toka vaihtoehto" (get-answer application "more-answers-dropdown-id"))))))

    (it "should not update dropdown answer when required followups are not answered"
      (with-response :put resp (-> (merge application-fixtures/person-info-form-application-with-modified-answers {:secret "0000000031"})
                                   (assoc-in [:answers 20 :value] "eka vaihtoehto"))
        (should= 400 (:status resp))
        (should= {:failures {:dropdown-followup-2 {:passed? false}}} (:body resp))))

    (it "should update dropdown answer"
      (with-response :put resp (-> (merge application-fixtures/person-info-form-application-with-more-modified-answers {:secret "0000000031"})
                                   (assoc-in [:answers 20 :value] "eka vaihtoehto"))
        (should= 200 (:status resp))
        (let [id          (-> resp :body :id)
              application (get-application-by-id id)]
          (should= "eka vaihtoehto" (get-answer application "more-answers-dropdown-id"))
          (should= "followup-attachment" (get-answer application "dropdown-followup-1"))
          (should= "toka" (get-answer application "dropdown-followup-2")))))))
