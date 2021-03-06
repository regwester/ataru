(ns ataru.virkailija.application.handlers
  (:require [ataru.virkailija.virkailija-ajax :as ajax]
            [re-frame.core :refer [subscribe dispatch dispatch-sync reg-event-db reg-event-fx]]
            [ataru.virkailija.autosave :as autosave]
            [ataru.virkailija.application-sorting :as application-sorting]
            [ataru.virkailija.virkailija-ajax :refer [http]]
            [ataru.application.review-states :as review-states]
            [ataru.virkailija.db :as initial-db]
            [ataru.util :as util]
            [ataru.cljs-util :as cljs-util]
            [ataru.virkailija.temporal :as temporal]
            [reagent.core :as r]
            [taoensso.timbre :refer-macros [spy debug]]
            [ataru.feature-config :as fc]
            [ataru.url :as url]
            [camel-snake-kebab.core :as c]
            [camel-snake-kebab.extras :as ce]
            [cljs-time.core :as t]
            [ataru.application.application-states :as application-states]))

(defn- state-filter->query-param
  [db filter all-states]
  (when-let [filters (seq (clojure.set/difference
                           (set (map first all-states))
                           (set (get-in db [:application filter]))))]
    (str (name filter) "=" (clojure.string/join "," filters))))

(defn- applications-link
  [db]
  (let [selected-form           (get-in db [:application :selected-form-key])
        selected-haku           (get-in db [:application :selected-haku])
        selected-hakukohde      (get-in db [:application :selected-hakukohde])
        selected-hakukohderyhma (get-in db [:application :selected-hakukohderyhma])
        term                    (when (= :search-term (get-in db [:application :search-control :show]))
                                  (when-let [term (get-in db [:application :search-control :search-term :value])]
                                    (str "term=" term)))
        application-key         (when-let [application-key (get-in db [:application :selected-key])]
                                  (str "application-key=" application-key))
        ensisijaisesti          (when (or (some? selected-hakukohde)
                                          (some? selected-hakukohderyhma))
                                  (str "ensisijaisesti=" (get-in db [:application :ensisijaisesti?])))
        attachment-state-filter (state-filter->query-param
                                 db
                                 :attachment-state-filter
                                 review-states/attachment-hakukohde-review-types-with-no-requirements)
        processing-state-filter (state-filter->query-param
                                 db
                                 :processing-state-filter
                                 review-states/application-hakukohde-processing-states)
        selection-state-filter  (state-filter->query-param
                                 db
                                 :selection-state-filter
                                 review-states/application-hakukohde-selection-states)
        query-params            (when-let [params (->> [term
                                                        application-key
                                                        ensisijaisesti
                                                        attachment-state-filter
                                                        processing-state-filter
                                                        selection-state-filter]
                                                       (filter some?)
                                                       seq)]
                                  (str "?" (clojure.string/join "&" params)))]
    (cond (some? selected-form)
          (str "/lomake-editori/applications/" selected-form query-params)
          (some? selected-haku)
          (str "/lomake-editori/applications/haku/" selected-haku query-params)
          (some? selected-hakukohde)
          (str "/lomake-editori/applications/hakukohde/" selected-hakukohde query-params)
          (some? selected-hakukohderyhma)
          (str "/lomake-editori/applications/haku/" (first selected-hakukohderyhma)
               "/hakukohderyhma/" (second selected-hakukohderyhma)
               query-params))))

(reg-event-fx
  :application/select-application
  (fn [{:keys [db]} [_ application-key]]
    (if (not= application-key (get-in db [:application :selected-key]))
      (let [db (-> db
                   (assoc-in [:application :selected-key] application-key)
                   (assoc-in [:application :selected-application-and-form] nil)
                   (assoc-in [:application :review-comment] nil)
                   (assoc-in [:application :application-list-expanded?] false)
                   (assoc-in [:application :information-request] nil))]
        {:db         db
         :dispatch-n [[:application/stop-autosave]
                      [:application/fetch-application application-key]]}))))

(defn close-application [db]
  (cljs-util/update-url-with-query-params {:application-key nil})
  (-> db
      (assoc-in [:application :selected-review-hakukohde] nil)
      (assoc-in [:application :selected-key] nil)
      (assoc-in [:application :selected-application-and-form] nil)
      (assoc-in [:application :application-list-expanded?] true)))

(reg-event-db
 :application/close-application
 (fn [db [_ _]]
   (close-application db)))

(defn- processing-state-counts-for-application
  [{:keys [application-hakukohde-reviews]}]
  (frequencies
    (map
      :state
      (or
        (->> application-hakukohde-reviews
             (filter #(= "processing-state" (:requirement %)))
             (not-empty))
        [{:requirement "processing-state" :state review-states/initial-application-hakukohde-processing-state}]))))

(defn review-state-counts
  [applications]
  (reduce
    (fn [acc application]
      (merge-with + acc (processing-state-counts-for-application application)))
    {}
    applications))

(defn- map-vals-to-zero [m]
  (into {} (for [[k v] m] [k 0])))

(defn attachment-state-counts
  [applications selected-hakukohde]
  (reduce
    (fn [acc application]
      (merge-with (fn [prev new] (+ prev (if (not-empty new) 1 0)))
        acc
        (group-by :state (cond->> (application-states/attachment-reviews-with-no-requirements application)
                                  (some? selected-hakukohde)
                                  (filter #(= (:hakukohde %) selected-hakukohde))))))
    (map-vals-to-zero review-states/attachment-hakukohde-review-types-with-no-requirements)
    applications))

(defn- update-review-field-of-selected-application-in-list
  [application selected-application-key field value]
  (if (= selected-application-key (:key application))
    (assoc application field value)
    application))

(defn- update-hakukohde-review-field-of-selected-application-in-list
  [application selected-application-key hakukohde review-field state]
  (if (= selected-application-key (:key application))
    (let [hakukohde-reviews             (or (:application-hakukohde-reviews application) [])
          reviews-with-existing-removed (remove
                                          (fn [review]
                                            (and
                                              (= (:requirement review) (name review-field))
                                              (= (:hakukohde review) hakukohde)))
                                          hakukohde-reviews)
          new-review                    {:requirement (name review-field)
                                         :state       state
                                         :hakukohde   hakukohde}]
      (assoc application :application-hakukohde-reviews (conj reviews-with-existing-removed new-review)))
    application))

(reg-event-db
 :application/update-review-field
 (fn [db [_ field value]]
   (let [selected-key           (get-in db [:application :selected-key])
         application-list       (get-in db [:application :applications])
         selected-hakukohde-oid (get-in db [:application :selected-review-hakukohde])
         is-hakukohde-review?   (-> (map first review-states/hakukohde-review-types)
                                    (set)
                                    (contains? field))
         updated-applications   (cond
                                  (some #{field} [:state :score])
                                  (mapv
                                    #(update-review-field-of-selected-application-in-list % selected-key field value)
                                    application-list)

                                  is-hakukohde-review?
                                  (mapv
                                    #(update-hakukohde-review-field-of-selected-application-in-list % selected-key selected-hakukohde-oid field value)
                                    application-list)

                                  :else
                                  application-list)
         db                     (cond-> db
                                  (and (= field :processing-state)
                                       (= value "information-request"))
                                  (assoc-in [:application :information-request :visible?] true))]
     (if is-hakukohde-review?
       (-> db
           (assoc-in [:application :review :hakukohde-reviews (keyword selected-hakukohde-oid) field] value)
           (assoc-in [:application :applications] updated-applications))
       (-> db
           (update-in [:application :review] assoc field value)
           (assoc-in [:application :applications] updated-applications)
           (assoc-in [:application :review-state-counts] (review-state-counts updated-applications)))))))

(defn- update-attachment-hakukohde-review-field-of-selected-application-in-list
  [application selected-application-key hakukohde attachment-key state]
  (if (= selected-application-key (:key application))
    (let [reviews-with-existing-removed (remove
                                          (fn [review]
                                            (and
                                             (= (:attachment-key review) attachment-key)
                                             (= (:hakukohde review) hakukohde)))
                                          (:application-attachment-reviews application))
          new-review                    {:attachment-key attachment-key
                                         :state          state
                                         :hakukohde      hakukohde}]
      (assoc application :application-attachment-reviews (conj reviews-with-existing-removed new-review)))
    application))

(reg-event-db
  :application/update-attachment-review
  (fn [db [_ attachment-key hakukohde-oid state]]
    (let [selected-key           (get-in db [:application :selected-key])
          application-list       (get-in db [:application :applications])
          updated-applications   (mapv
                                   #(update-attachment-hakukohde-review-field-of-selected-application-in-list
                                     % selected-key hakukohde-oid (name attachment-key) state)
                                   application-list)]
      (-> db
          (assoc-in [:application :review :attachment-reviews (keyword hakukohde-oid) attachment-key] state)
          (assoc-in [:application :applications] updated-applications)
          (assoc-in [:application :attachment-state-counts] (attachment-state-counts updated-applications
                                                                                     (-> db :application :selected-hakukohde)))))))

(defn- update-sort
  [db column-id swap-order?]
  (let [current-applications (get-in db [:application :applications])
        current-sort         (get-in db [:application :sort])
        new-order            (if swap-order?
                               (if (= :ascending (:order current-sort))
                                 :descending
                                 :ascending)
                               (:order current-sort))]
    (if (= column-id (:column current-sort))
      (-> db
          (update-in
            [:application :sort]
            assoc
            :order
            new-order)
          (assoc-in
            [:application :applications]
            (application-sorting/sort-by-column current-applications column-id new-order)))
      (-> db
          (assoc-in
            [:application :sort]
            {:column column-id :order :descending})
          (assoc-in
            [:application :applications]
            (application-sorting/sort-by-column current-applications column-id :descending))))))

(reg-event-db
  :application/toggle-filter
  (fn [db [_ filter-id state]]
    (if state
      (update-in
        db
        [:application :filters filter-id state]
        not))))

(reg-event-db
  :application/toggle-shown-time-column
  (fn [db _]
    (let [new-value (if (= :created-time (-> db :application :selected-time-column))
                      :original-created-time
                      :created-time)]
      (-> db
          (assoc-in [:application :selected-time-column] new-value)
          (update-sort new-value true)))))

(reg-event-db
  :application/remove-filters
  (fn [db _]
    (assoc-in db [:application :filters] (get-in initial-db/default-db [:application :filters]))))

(reg-event-db
 :application/update-sort
 (fn [db [_ column-id]]
   (update-sort db column-id true)))

(defn- parse-application-time
  [application]
  (-> application
      (update :created-time temporal/str->googdate)
      (update :original-created-time temporal/str->googdate)))

(reg-event-fx
  :application/handle-fetch-applications-response
  (fn [{:keys [db]} [_ {:keys [applications]}]]
    (let [parsed-applications (->> applications
                                   (map parse-application-time)
                                   (map #(assoc % :application-hakukohde-reviews (application-states/get-all-reviews-for-all-requirements %))))
          db                  (-> db
                                  (assoc-in [:application :applications] parsed-applications)
                                  (assoc-in [:application :fetching-applications] false)
                                  (assoc-in [:application :review-state-counts] (review-state-counts parsed-applications))
                                  (assoc-in [:application :attachment-state-counts] (attachment-state-counts parsed-applications
                                                                                                             (-> db :application :selected-hakukohde)))
                                  (assoc-in [:application :sort] application-sorting/initial-sort)
                                  (assoc-in [:application :selected-time-column] :created-time)
                                  (assoc-in [:application :information-request] nil)
                                  (update-sort (:column application-sorting/initial-sort) false))
          application-key     (if (= 1 (count parsed-applications))
                                (-> parsed-applications first :key)
                                (when-let [query-key (:application-key (cljs-util/extract-query-params))]
                                  (some #{query-key} (map :key parsed-applications))))]
      {:db       db
       :dispatch (if application-key
                   [:application/select-application application-key]
                   [:application/close-application])})))

(defn- extract-unselected-review-states-from-query
  [query-param states]
  (-> (cljs-util/extract-query-params)
      query-param
      (clojure.string/split #",")
      (cljs-util/get-unselected-review-states states)))

(defn fetch-applications-fx [db path]
  {:db       (-> db
                 (assoc-in [:application :fetching-applications] true)
                 (assoc-in [:application :attachment-state-filter] (extract-unselected-review-states-from-query
                                                                     :attachment-state-filter
                                                                     review-states/attachment-hakukohde-review-types-with-no-requirements))
                 (assoc-in [:application :processing-state-filter] (extract-unselected-review-states-from-query
                                                                     :processing-state-filter
                                                                     review-states/application-hakukohde-processing-states))
                 (assoc-in [:application :selection-state-filter] (extract-unselected-review-states-from-query
                                                                    :selection-state-filter
                                                                    review-states/application-hakukohde-selection-states))
                 (assoc-in [:application :filters] (get-in initial-db/default-db [:application :filters])))
   :dispatch [:application/refresh-haut-and-hakukohteet]
   :http     {:method              :get
              :path                path
              :skip-parse-times?   true
              :handler-or-dispatch :application/handle-fetch-applications-response}})

(reg-event-fx
  :application/fetch-applications
  (fn [{:keys [db]} [_ form-key]]
    (fetch-applications-fx db (str "/lomake-editori/api/applications/list?formKey=" form-key))))

(reg-event-fx
  :application/fetch-applications-by-hakukohde
  (fn [{:keys [db]} [_ hakukohde-oid]]
    (fetch-applications-fx
     db
     (str "/lomake-editori/api/applications/list"
          "?hakukohdeOid=" hakukohde-oid
          (when-let [ensisijaisesti (get-in db [:application :ensisijaisesti?] false)]
            (str "&ensisijaisesti=" ensisijaisesti))))))

(reg-event-fx
  :application/fetch-applications-by-hakukohderyhma
  (fn [{:keys [db]} [_ [haku-oid hakukohderyhma-oid]]]
    (fetch-applications-fx
     db
     (str "/lomake-editori/api/applications/list"
          "?hakuOid=" haku-oid
          "&hakukohderyhmaOid=" hakukohderyhma-oid
          (when-let [ensisijaisesti (get-in db [:application :ensisijaisesti?] false)]
            (str "&ensisijaisesti=" ensisijaisesti))))))

(reg-event-fx
  :application/fetch-applications-by-haku
  (fn [{:keys [db]} [_ haku-oid]]
    (fetch-applications-fx db (str "/lomake-editori/api/applications/list?hakuOid=" haku-oid))))

(reg-event-fx
  :application/fetch-applications-by-term
  (fn [{:keys [db]} [_ term type]]
    (let [query-param (case type
                        :application-oid "applicationOid"
                        :person-oid "personOid"
                        :ssn "ssn"
                        :dob "dob"
                        :email "email"
                        :name "name")]
      (fetch-applications-fx db (str "/lomake-editori/api/applications/list?" query-param "=" term)))))

(reg-event-db
 :application/review-updated
 (fn [db [_ response]]
   (assoc-in db [:application :events] (:events response))))

(defn answers-indexed
  "Convert the rest api version of application to a version which application
  readonly-rendering can use (answers are indexed with key in a map)"
  [application]
  (let [answers    (:answers application)
        answer-map (into {} (map (fn [answer] [(keyword (:key answer)) answer])) answers)]
    (assoc application :answers answer-map)))

(defn- review-notes-by-hakukohde-and-state-name
  [review-notes]
  (let [notes-by-hakukohde (->> review-notes
                                (filter #(some? (:hakukohde %)))
                                (group-by :hakukohde))]
    (reduce-kv (fn [by-hakukohde hakukohde notes]
                 (let [notes-by-state-name (group-by :state-name notes)]
                   (assoc by-hakukohde
                          (keyword hakukohde)
                          (reduce-kv (fn [by-state-name state-name notes]
                                       (assoc by-state-name (keyword state-name) (-> notes first :notes)))
                                     {}
                                     notes-by-state-name))))
               {}
               notes-by-hakukohde)))

(defn update-application-details [db {:keys [form
                                             application
                                             events
                                             review
                                             hakukohde-reviews
                                             attachment-reviews
                                             information-requests
                                             review-notes]}]
  (-> db
      (assoc-in [:application :selected-application-and-form]
        {:form        form
         :application (answers-indexed application)})
      (assoc-in [:application :events] events)
      (assoc-in [:application :review] review)
      (assoc-in [:application :review-notes] review-notes)
      (assoc-in [:application :notes] (review-notes-by-hakukohde-and-state-name review-notes))
      (assoc-in [:application :review :hakukohde-reviews] hakukohde-reviews)
      (assoc-in [:application :review :attachment-reviews] attachment-reviews)
      (update-in [:application :selected-review-hakukohde]
                 (fn [current-hakukohde]
                   (or ((set (:hakukohde application)) (get-in db [:application :selected-hakukohde]))
                       (first (:hakukohde application))
                       "form")))
      (assoc-in [:application :information-requests] information-requests)))

(defn review-autosave-predicate [current prev]
  (if (not= (:id current) (:id prev))
    false
    ;timestamp instances for same timestamp fetched via ajax are not equal :(
    (not= (dissoc current :created-time) (dissoc prev :created-time))))

(defn start-application-review-autosave [db]
  (assoc-in
    db
    [:application :review-autosave]
    (autosave/interval-loop {:subscribe-path [:application :review]
                             :changed-predicate review-autosave-predicate
                             :handler (fn [current _]
                                        (ajax/http
                                          :put
                                          "/lomake-editori/api/applications/review"
                                          :application/review-updated
                                          :override-args {:params (select-keys current [:id
                                                                                        :application-id
                                                                                        :application-key
                                                                                        :score
                                                                                        :state
                                                                                        :hakukohde-reviews
                                                                                        :attachment-reviews])}))})))

(reg-event-fx
  :application/handle-fetch-application-attachment-metadata
  (fn [{:keys [db]} [_ response]]
    (let [response-map       (group-by :key response)
          file-key->metadata (fn file-key->metadata [file-key-or-keys]
                               (if (vector? file-key-or-keys)
                                 (mapv file-key->metadata file-key-or-keys)
                                 (first (response-map file-key-or-keys))))
          set-file-metadata  (fn [answer]
                               (assoc answer :values (-> answer :value file-key->metadata)))
          db                 (->> (get-in db [:application :selected-application-and-form :application :answers])
                                  (map (fn [[_ {:keys [fieldType] :as answer}]]
                                         (cond-> answer
                                           (= fieldType "attachment")
                                           (set-file-metadata))))
                                  (reduce (fn [db {:keys [key] :as answer}]
                                            (assoc-in db [:application :selected-application-and-form :application :answers (keyword key)] answer))
                                          db))]
      {:db       db
       :dispatch [:application/start-autosave]})))

(reg-event-fx
  :application/fetch-application-attachment-metadata
  (fn [{:keys [db]} _]
    (let [file-keys (->> (get-in db [:application :selected-application-and-form :application :answers])
                         (filter (comp (partial = "attachment") :fieldType second))
                         (map (comp :value second))
                         (flatten))]
      (if (empty? file-keys)
        ; sanity check to ensure autosave starts if application has no attachments
        {:db       db
         :dispatch [:application/start-autosave]}
        {:db   db
         :http {:method              :post
                :path                "/lomake-editori/api/files/metadata"
                :params              {:keys file-keys}
                :handler-or-dispatch :application/handle-fetch-application-attachment-metadata}}))))

(defn- application-has-attachments? [db]
  (some (comp (partial = "attachment") :fieldType second)
        (get-in db [:application :selected-application-and-form :application :answers])))

(defn- parse-application-times
  [response]
  (let [answers           (-> response :application :answers)
        tarjonta          (-> response :application :tarjonta)
        form-content      (-> response :form :content)
        without-huge-data (-> response
                              (update-in [:application] dissoc :answers)
                              (update-in [:application] dissoc :tarjonta)
                              (update-in [:form] dissoc :content))
        with-times        (ataru.virkailija.temporal/parse-times without-huge-data)]
    (-> with-times
        (assoc-in [:application :answers] answers)
        (assoc-in [:application :tarjonta] tarjonta)
        (assoc-in [:form :content] form-content))))

(reg-event-fx
  :application/handle-fetch-application
  (fn [{:keys [db]} [_ response]]
    (let [response-with-parsed-times (parse-application-times response)
          db                         (update-application-details db response-with-parsed-times)]
      {:db         db
       :dispatch-n [(if (application-has-attachments? db)
                      [:application/fetch-application-attachment-metadata]
                      [:application/start-autosave])
                    [:application/get-application-change-history (-> response :application :key)]]})))

(reg-event-fx
  :application/fetch-application
  (fn [{:keys [db]} [_ application-id]]
    (when-let [autosave (get-in db [:application :review-autosave])]
      (autosave/stop-autosave! autosave))
    (let [db (assoc-in db [:application :review-autosave] nil)]
      {:db   db
       :http {:method              :get
              :path                (str "/lomake-editori/api/applications/" application-id)
              :handler-or-dispatch :application/handle-fetch-application
              :skip-parse-times?   true}})))

(reg-event-db
  :application/start-autosave
  (fn [db _]
    (start-application-review-autosave db)))

(reg-event-fx
  :application/stop-autosave
  (fn [{:keys [db]} _]
    (let [autosave (get-in db [:application :review-autosave])]
      (cond-> {:db db}
        (some? autosave) (assoc :stop-autosave autosave)))))

(defn- clear-selection
  [db]
  (update db :application dissoc
          :selected-form-key
          :selected-haku
          :selected-hakukohde
          :selected-hakukohderyhma))

(reg-event-fx
  :application/clear-applications-haku-and-form-selections
  (fn [{db :db} _]
    (cljs-util/unset-query-param "term")
    {:db (-> db
             (assoc-in [:editor :selected-form-key] nil)
             (assoc-in [:application :applications] nil)
             (assoc-in [:application :search-control :search-term :value] "")
             clear-selection)}))

(reg-event-db
  :application/select-form
  (fn [db [_ form-key]]
    (-> db
        clear-selection
        (assoc-in [:application :selected-form-key] form-key))))

(reg-event-db
  :application/select-hakukohde
  (fn [db [_ hakukohde-oid]]
    (-> db
        clear-selection
        (assoc-in [:application :selected-hakukohde] hakukohde-oid))))

(reg-event-db
  :application/select-hakukohderyhma
  (fn [db [_ [haku-oid hakukohderyhma-oid]]]
    (-> db
        clear-selection
        (assoc-in [:application :selected-hakukohderyhma] [haku-oid hakukohderyhma-oid]))))

(reg-event-db
  :application/select-haku
  (fn [db [_ haku-oid]]
    (-> db
        clear-selection
        (assoc-in [:application :selected-haku] haku-oid))))

(defn- set-ensisijaisesti
  [db ensisijaisesti?]
  (assoc-in db [:application :ensisijaisesti?] ensisijaisesti?))

(reg-event-db
  :application/set-ensisijaisesti
  (fn [db [_ ensisijaisesti?]]
    (set-ensisijaisesti db ensisijaisesti?)))

(reg-event-fx
  :application/navigate-to-ensisijaisesti
  (fn [{:keys [db]} [_ ensisijaisesti?]]
    {:navigate (applications-link (set-ensisijaisesti db ensisijaisesti?))}))

(defn- keys-to-names [m] (reduce-kv #(assoc %1 (name %2) %3) {} m))

(reg-event-db
  :editor/handle-refresh-haut-and-hakukohteet
  (fn [db [_ {:keys [tarjonta-haut direct-form-haut haut hakukohteet hakukohderyhmat]}]]
    (-> db
        (assoc-in [:application :haut :tarjonta-haut] (keys-to-names tarjonta-haut))
        (assoc-in [:application :haut :direct-form-haut] (keys-to-names direct-form-haut))
        (assoc-in [:application :forms] (keys-to-names direct-form-haut))
        (update :haut merge (keys-to-names haut))
        (update :hakukohteet merge (keys-to-names hakukohteet))
        (update :hakukohderyhmat merge (keys-to-names hakukohderyhmat))
        (update :fetching-haut dec)
        (update :fetching-hakukohteet dec))))

(reg-event-fx
  :application/refresh-haut-and-hakukohteet
  (fn [{:keys [db]}]
    {:db   (-> db
               (update :fetching-haut inc)
               (update :fetching-hakukohteet inc))
     :http {:method              :get
            :path                "/lomake-editori/api/haut"
            :handler-or-dispatch :editor/handle-refresh-haut-and-hakukohteet
            :skip-parse-times?   true}}))

(reg-event-fx
  :application/navigate
  (fn [{:keys [db]} [_ path]]
    {:db       db
     :navigate path}))

(reg-event-fx
  :application/dispatch
  (fn [{:keys [db]} [_ dispatch-vec]]
    {:db       db
     :dispatch dispatch-vec}))

(reg-event-db
  :application/select-review-hakukohde
  (fn [db [_ selected-hakukohde-oid]]
    (assoc-in db [:application :selected-review-hakukohde] selected-hakukohde-oid)))

(reg-event-db
  :application/set-information-request-subject
  (fn [db [_ subject]]
    (assoc-in db [:application :information-request :subject] subject)))

(reg-event-db
  :application/set-information-request-message
  (fn [db [_ message]]
    (assoc-in db [:application :information-request :message] message)))

(reg-event-fx
  :application/submit-information-request
  (fn [{:keys [db]} _]
    (let [application-key (-> db :application :selected-application-and-form :application :key)]
      {:db   (assoc-in db [:application :information-request :state] :submitting)
       :http {:method              :post
              :path                "/lomake-editori/api/applications/information-request"
              :params              (-> db :application :information-request
                                       (select-keys [:message :subject])
                                       (assoc :application-key application-key))
              :handler-or-dispatch :application/handle-submit-information-request-response}})))

(reg-event-db
  :application/set-information-request-window-visibility
  (fn [db [_ visible?]]
    (assoc-in db [:application :information-request :visible?] visible?)))

(reg-event-fx
  :application/handle-submit-information-request-response
  (fn [{:keys [db]} [_ response]]
    {:db             (-> db
                         (assoc-in [:application :information-request] {:state    :submitted
                                                                        :visible? true})
                         (update-in [:application :information-requests] (fnil identity []))
                         (update-in [:application :information-requests] #(conj % response)))
     :dispatch-later [{:ms       3000
                       :dispatch [:application/reset-submit-information-request-state]}]}))

(reg-event-db
  :application/reset-submit-information-request-state
  (fn [db _]
    (let [application-key (-> db :application :selected-key)]
      (-> db
          (assoc-in [:application :information-request] {:visible? false})
          (update-in [:application :applications] (partial map (fn [application]
                                                                 (cond-> application
                                                                   (= (:key application) application-key)
                                                                   (assoc :new-application-modifications 0)))))))))

(reg-event-fx
  :application/handle-mass-update-application-reviews
  (fn [{:keys [db]} [_ _]]
    (let [db-application (:application db)
          selected-type  @(subscribe [:application/application-list-selected-by])
          selected-id    (if (= :selected-form-key selected-type)
                           (:selected-form-key db-application)
                           (-> db-application selected-type))
          dispatch-kw    (case selected-type
                           :selected-form-key :application/fetch-applications
                           :selected-haku :application/fetch-applications-by-haku
                           :selected-hakukohde :application/fetch-applications-by-hakukohde)]
      (if selected-type
        {:db db
         :dispatch [dispatch-kw selected-id]}
        {:db db}))))

(reg-event-fx
  :application/mass-update-application-reviews
  (fn [{:keys [db]} [_ application-keys from-state to-state]]
    {:db   (assoc-in db [:application :fetching-applications] true)
     :http {:method              :post
            :params              {:application-keys application-keys
                                  :from-state       from-state
                                  :to-state         to-state
                                  :hakukohde-oid    (-> db :application :selected-hakukohde)}
            :path                "/lomake-editori/api/applications/mass-update"
            :handler-or-dispatch :application/handle-mass-update-application-reviews}}))

(reg-event-fx
  :application/resend-modify-application-link
  (fn [{:keys [db]} _]
    (let [application-key (-> db :application :selected-key)]
      {:db   (assoc-in db [:application :modify-application-link :state] :submitting)
       :http {:method              :post
              :params              {:application-key application-key}
              :path                (str "/lomake-editori/api/applications/" application-key "/resend-modify-link")
              :handler-or-dispatch :application/handle-resend-modify-application-link-response}})))

(reg-event-fx
  :application/handle-resend-modify-application-link-response
  (fn [{:keys [db]} [_ response]]
    {:db             (-> db
                         (assoc-in [:application :modify-application-link :state] :submitted)
                         (update-in [:application :events] (fnil identity []))
                         (update-in [:application :events] #(conj % response)))
     :dispatch-later [{:ms       3000
                       :dispatch [:application/fade-out-resend-modify-application-link-confirmation-dialog]}]}))

(reg-event-fx
  :application/fade-out-resend-modify-application-link-confirmation-dialog
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:application :modify-application-link :state] :disappearing)
     :dispatch-later [{:ms 1000
                       :dispatch [:application/reset-resend-modify-application-link-state]}]}))

(reg-event-db
  :application/reset-resend-modify-application-link-state
  (fn [db _]
    (assoc-in db [:application :modify-application-link :state] nil)))

(reg-event-db
  :application/toggle-review-area-settings-visibility
  (fn [db _]
    (let [not-or-true (fnil not false)
          visible?    (-> db :application :review-settings :visible? not-or-true)
          keys->false (partial reduce-kv
                               (fn [config review-key _]
                                 (assoc config review-key false))
                               {})]
      (cond-> (assoc-in db [:application :review-settings :visible?] visible?)
        visible?
        (update-in [:application :ui/review] keys->false)))))

(reg-event-fx
  :application/toggle-review-state-setting
  (fn [{:keys [db]} [_ setting-kwd]]
    (let [not-or-false (fnil not true)
          enabled?     (-> db :application :review-settings :config setting-kwd not-or-false)]
      {:db   (assoc-in db [:application :review-settings :config setting-kwd] :updating)
       :http {:method              :post
              :params              {:setting-kwd setting-kwd
                                    :enabled     enabled?}
              :path                "/lomake-editori/api/applications/review-setting"
              :handler-or-dispatch :application/handle-toggle-review-state-setting-response}})))

(reg-event-db
  :application/handle-toggle-review-state-setting-response
  (fn [db [_ response]]
    (assoc-in db [:application :review-settings :config (-> response :setting-kwd keyword)] (:enabled response))))

(reg-event-fx
  :application/get-virkailija-settings
  (fn [{:keys [db]} _]
    {:db   db
     :http {:method              :get
            :path                "/lomake-editori/api/applications/virkailija-settings"
            :handler-or-dispatch :application/handle-get-virkailija-settings-response}}))

(reg-event-db
  :application/handle-get-virkailija-settings-response
  (fn [db [_ response]]
    (let [review-config (->> response
                             (ce/transform-keys c/->kebab-case-keyword)
                             :review)]
      (update-in db
                 [:application :review-settings :config]
                 merge
                 review-config))))

(reg-event-db
  :application/toggle-review-list-visibility
  (fn [db [_ list-kwd]]
    (update-in db [:application :ui/review list-kwd] (fnil not false))))

(reg-event-fx
  :application/add-review-note
  (fn [{:keys [db]} [_ text state-name]]
    (let [application-key (-> db :application :selected-key)
          hakukohde       (-> db :application :selected-review-hakukohde)
          tmp-id          (cljs-util/new-uuid)
          note            (merge {:notes           text
                                  :application-key application-key}
                                 (when state-name
                                   {:hakukohde  hakukohde
                                    :state-name state-name}))
          db              (-> db
                              (update-in [:application :review-notes]
                                         (fn [notes]
                                           (vec (cons (merge note
                                                             {:created-time (t/now)
                                                              :id           tmp-id
                                                              :animated?    true})
                                                      notes))))
                              (assoc-in [:application :review-comment] nil))]
      {:db   db
       :http {:method              :post
              :params              note
              :path                "/lomake-editori/api/applications/notes"
              :handler-or-dispatch :application/handle-add-review-note-response
              :handler-args        {:tmp-id tmp-id}}})))

(reg-event-fx :application/handle-add-review-note-response
  (fn [{:keys [db]} [_ resp {:keys [tmp-id]}]]
    {:db             (update-in db [:application :review-notes]
                                (fn [notes]
                                  (mapv (fn [note]
                                          (if (= tmp-id (:id note))
                                            (merge note resp)
                                            note))
                                        notes)))
     :dispatch-later [{:ms 1000 :dispatch [:application/reset-review-note-animations (:id resp)]}]}))

(reg-event-db :application/reset-review-note-animations
  (fn [db [_ note-id]]
    (update-in db [:application :review-notes]
               (fn [notes]
                 (mapv (fn [note]
                         (if (= note-id (:id note))
                           (dissoc note :animated?)
                           note))
                       notes)))))

(reg-event-db :application/set-review-comment-value
  (fn [db [_ review-comment]]
    (assoc-in db [:application :review-comment] review-comment)))

(reg-event-fx :application/remove-review-note
  (fn [{:keys [db]} [_ note-idx]]
    (let [note-id (-> db :application :review-notes (get note-idx) :id)
          db      (assoc-in db [:application :review-notes note-idx :state] :removing)]
      {:db   db
       :http {:method              :delete
              :path                (str "/lomake-editori/api/applications/notes/" note-id)
              :handler-or-dispatch :application/handle-remove-review-note-response}})))

(reg-event-db :application/handle-remove-review-note-response
  (fn [db [_ resp]]
    (let [note-with-id (comp (partial = (:id resp)) :id)
          remove-note  (comp vec (partial remove note-with-id))]
      (update-in db [:application :review-notes] remove-note))))

(def application-active-state (-> review-states/application-review-states (first) (first)))
(def application-inactive-state (-> review-states/application-review-states (last) (first)))

(reg-event-db
  :application/set-application-activeness
  (fn [db [_ active?]]
    (assoc-in db [:application :review :state] (if active?
                                                 application-active-state
                                                 application-inactive-state))))

(reg-event-db
  :application/handle-change-history-response
  (fn [db [_ response]]
    (assoc-in db [:application :selected-application-and-form :application-change-history] response)))

(reg-event-fx
  :application/get-application-change-history
  (fn [{:keys [db]} [_ application-key]]
    {:db   db
     :http {:method              :get
            :path                (str "/lomake-editori/api/applications/" application-key "/changes")
            :handler-or-dispatch :application/handle-change-history-response}}))

(reg-event-db
  :application/open-application-version-history
  (fn [db [_ event]]
    (assoc-in db [:application :selected-application-and-form :selected-event] event)))

(reg-event-db
  :application/close-application-version-history
  (fn [db _]
    (update-in db [:application :selected-application-and-form] dissoc :selected-event)))

(reg-event-db
  :application/remove-field-highlight
  (fn [db [_ field-id]]
    (let [highlighted-fields (-> db :application :selected-application-and-form :highlighted-fields)
          updated-fields     (remove #(= field-id %) highlighted-fields)]
      (assoc-in db [:application :selected-application-and-form :highlighted-fields] updated-fields))))

(reg-event-fx
  :application/highlight-field
  (fn [{:keys [db]} [_ field-id]]
    (.scrollIntoView (.getElementById js/document (name field-id)) (js-obj "behavior" "smooth"))
    {:db (update-in db [:application :selected-application-and-form :highlighted-fields] conj field-id)
     :dispatch-later [{:ms 3000 :dispatch [:application/remove-field-highlight field-id]}]}))

(reg-event-db
  :application/toggle-all-pohjakoulutus-filters
  (fn [db [_ all-enabled?]]
    (update-in
      db
      [:application :filters :base-education]
      (fn [filter-map] (reduce-kv (fn [acc k _] (assoc acc k (not all-enabled?))) {} filter-map)))))
