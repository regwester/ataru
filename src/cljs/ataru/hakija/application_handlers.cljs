(ns ataru.hakija.application-handlers
  (:require [re-frame.core :refer [reg-event-db reg-fx reg-event-fx dispatch]]
            [ataru.hakija.application-validators :as validator]
            [ataru.cljs-util :as util]
            [ataru.util :as cljc-util]
            [ataru.util :as autil]
            [ataru.hakija.rules :as rules]
            [cljs.core.match :refer-macros [match]]
            [ataru.hakija.application :refer [create-initial-answers
                                              create-application-to-submit
                                              extract-wrapper-sections]]
            [taoensso.timbre :refer-macros [spy debug]]
            [ataru.translations.translation-util :refer [get-translations]]
            [ataru.translations.application-view :refer [application-view-translations]]
            [clojure.data :as d]))

(defn initialize-db [_ _]
  {:form        nil
   :application {:answers {}}})

(defn- handle-get-application [{:keys [db]}
                               [_
                                {:keys [secret virkailija-secret]}
                                {:keys [answers
                                        form-key
                                        lang
                                        haku
                                        hakukohde
                                        hakukohde-name
                                        state]}]]
  (let [[secret-kwd secret-val] (if-not (clojure.string/blank? secret)
                                  [:secret secret]
                                  [:virkailija-secret virkailija-secret])]
    {:db       (-> db
                   (assoc-in [:application :editing?] true)
                   (assoc-in [:application secret-kwd] secret-val)
                   (assoc-in [:application :state] state)
                   (assoc-in [:application :hakukohde] hakukohde)
                   (assoc-in [:form :selected-language] (or (keyword lang) :fi))
                   (assoc-in [:form :hakukohde-name] hakukohde-name))
     :dispatch (if haku
                 [:application/get-latest-form-by-haku haku answers]
                 [:application/get-latest-form-by-key form-key answers])}))

(reg-event-fx
  :application/handle-get-application
  handle-get-application)

(defn- get-application-by-hakija-secret
  [{:keys [db]} [_ secret]]
  {:db   db
   :http {:method  :get
          :url     (str "/hakemus/api/application?secret=" secret)
          :handler [:application/handle-get-application {:secret secret}]}})

(reg-event-fx
  :application/get-application-by-hakija-secret
  get-application-by-hakija-secret)

(defn- get-application-by-virkailija-secret
  [{:keys [db]} [_ virkailija-secret]]
  {:db   db
   :http {:method  :get
          :url     (str "/hakemus/api/application?virkailija-secret=" virkailija-secret)
          :handler [:application/handle-get-application {:virkailija-secret virkailija-secret}]}})

(reg-event-fx
  :application/get-application-by-virkailija-secret
  get-application-by-virkailija-secret)

(reg-event-fx
  :application/get-latest-form-by-key
  (fn [{:keys [db]} [_ form-key answers]]
    {:db   db
     :http {:method  :get
            :url     (str "/hakemus/api/form/" form-key)
            :handler [:application/handle-form answers]}}))

(defn- get-latest-form-by-hakukohde [{:keys [db]} [_ hakukohde-oid answers]]
  {:db   (assoc-in db [:application :preselected-hakukohde] hakukohde-oid)
   :http {:method  :get
          :url     (str "/hakemus/api/hakukohde/" hakukohde-oid)
          :handler [:application/handle-form answers]}})

(reg-event-fx
  :application/get-latest-form-by-hakukohde
  get-latest-form-by-hakukohde)

(reg-event-fx
  :application/get-latest-form-by-haku
  (fn [{:keys [db]} [_ haku-oid answers]]
    {:db db
     :http {:method  :get
            :url     (str "/hakemus/api/haku/" haku-oid)
            :handler [:application/handle-form answers]}}))

(defn handle-submit [db _]
  (assoc-in db [:application :submit-status] :submitted))

(defn send-application [db method]
  {:db       (-> db (assoc-in [:application :submit-status] :submitting) (dissoc :error))
   :http     {:method        method
              :url           "/hakemus/api/application"
              :post-data     (create-application-to-submit (:application db) (:form db) (get-in db [:form :selected-language]))
              :handler       :application/handle-submit-response
              :error-handler :application/handle-submit-error}})

(reg-event-db
  :application/handle-submit-response
  handle-submit)

(reg-event-fx
  :application/handle-submit-error
  (fn [cofx [_ response]]
    {:db (-> (update (:db cofx) :application dissoc :submit-status)
             (assoc :error {:message "Tapahtui virhe " :detail response}))}))

(defn- resize-vector [target-length x]
  (let [add-length (- target-length (count x))]
    (cond-> x
      (> add-length 0)
      (into (repeatedly add-length (fn [] nil))))))

(defn- vector-of-length [target-length]
  (comp (partial resize-vector target-length)
        (fnil identity [])))

(reg-event-db
  :application/show-attachment-too-big-error
  (fn [db [_ component-id question-group-idx]]
    (let [error-path (if question-group-idx
                       [:application :answers (keyword component-id) :errors question-group-idx :too-big]
                       [:application :answers (keyword component-id) :errors :too-big])
          db         (cond-> db
                       question-group-idx
                       (update-in [:application :answers (keyword component-id) :errors] (vector-of-length question-group-idx)))]
      (assoc-in db error-path true))))

(reg-event-fx
  :application/submit
  (fn [{:keys [db]} _]
    (send-application db :post)))

(reg-event-fx
  :application/edit
  (fn [{:keys [db]} _]
    (send-application db :put)))

(reg-event-db
  :application/hide-hakukohteet-if-no-tarjonta
  (fn [db _]
    (assoc-in db [:application :ui :hakukohteet :visible?] (boolean (-> db :form :tarjonta)))))

(defn- get-lang-from-path [supported-langs]
  (when-let [lang (-> (util/extract-query-params)
                      :lang
                      keyword)]
    (when (some (partial = lang) supported-langs)
      lang)))

(defn- set-form-language [form & [lang]]
  (let [supported-langs (:languages form)
        lang            (or lang
                          (get-lang-from-path supported-langs)
                          (first supported-langs))]
    (assoc form :selected-language lang)))

(defn- languages->kwd [form]
  (update form :languages
    (fn [languages]
      (mapv keyword languages))))

(defn- toggle-multiple-choice-option [answer option-value validators answers-by-key question-group-idx]
  (let [option-path            (if question-group-idx
                                 [:options question-group-idx option-value]
                                 [:options option-value])
        answer                 (cond-> answer
                                 question-group-idx (update :options (vector-of-length (inc question-group-idx)))
                                 true (update-in option-path not))
        parse-option-values    (fn [options]
                                 (->> options
                                      (filter (comp true? second))
                                      (map first)))
        value                  (if question-group-idx
                                 (map parse-option-values (:options answer))
                                 (parse-option-values (:options answer)))
        values-in-group-valid? (fn [values]
                                 (every? true? (map #(validator/validate % values answers-by-key nil) validators)))
        valid                  (or (empty? validators)
                                   (and question-group-idx
                                        (every? values-in-group-valid? value))
                                   (and (not question-group-idx)
                                        (values-in-group-valid? value)))]
    (merge answer {:value value :valid valid})))

(defn- select-single-choice-button [db [_ button-id value validators question-group-idx]]
  (let [button-path   [:application :answers button-id]
        current-value (:value (get-in db (if question-group-idx
                                           (into button-path [:values question-group-idx])
                                           button-path)))
        new-value     (when (not= value current-value) value)
        value-valid?  (fn [value]
                        (if (not-empty validators)
                          (every? true? (map #(validator/validate % value (-> db :application :answers) nil) validators))
                          true))
        update-value  (fn [value]
                        (let [valid? (value-valid? new-value)]
                          (merge value {:value new-value
                                        :valid valid?})))]
    (if question-group-idx
      (letfn [(values-valid? [values]
                (and (every? value-valid? values)
                     (or (not (some #{"required"} validators))
                         (not-empty values))))]
        (-> db
            (update-in (conj button-path :values) (vector-of-length (inc question-group-idx)))
            (update-in (into button-path [:values question-group-idx]) (fnil identity []))
            (update-in (into button-path [:values question-group-idx 0]) update-value)
            (update-in button-path (fn [answer]
                                     (assoc answer :valid (every? values-valid? (:values answer)))))
            (update-in button-path (fn [answer]
                                     (assoc answer :value (map (partial map :value) (:values answer)))))))
      (update-in db button-path update-value))))

(defn- toggle-values
  [answer options answers-by-key]
  (let [question-group-answer? (and (vector? options)
                                    (every? vector? options))]
    (->> options
         (map-indexed (fn [question-group-idx option-or-options]
                        [(when question-group-answer? question-group-idx)
                         option-or-options]))
         (reduce (fn [answer [question-group-idx option-or-options]]
                   (if question-group-idx
                     (reduce #(toggle-multiple-choice-option %1 %2 nil answers-by-key question-group-idx)
                             answer
                             option-or-options)
                     (toggle-multiple-choice-option answer
                                                    option-or-options
                                                    nil
                                                    answers-by-key
                                                    nil)))
                 answer))))

(defn- merge-multiple-choice-option-values [value answers-by-key answer]
  (if (string? value)
    (toggle-values answer (clojure.string/split value #"\s*,\s*") answers-by-key)
    (toggle-values answer value answers-by-key)))

(defn- set-ssn-field-visibility [db]
  (rules/run-rule {:toggle-ssn-based-fields "ssn"} db))

(defn- set-country-specific-fields-visibility
  [db]
  (rules/run-rule {:change-country-of-residence nil} db))

(defonce multi-value-field-types #{"multipleChoice" "singleChoice" "textField" "attachment" "hakukohteet" "dropdown" "textArea"})

(defn- supports-multiple-values [field-type]
  (contains? multi-value-field-types field-type))

(defn- set-have-finnish-ssn
  [db]
  (let [ssn (get-in db [:application :answers :ssn])]
    (update-in db [:application :answers :have-finnish-ssn]
               merge {:valid true
                      :value (str (or (and (clojure.string/blank? (:value ssn))
                                           (:cannot-view ssn))
                                      (not (clojure.string/blank? (:value ssn)))))})))

(defn- populate-hakukohde-answers-if-necessary
  "Populate hakukohde answers for legacy applications where only top-level hakukohde array exists"
  [db]
  (let [hakukohteet (-> db :application :hakukohde)
        hakukohde-answers (-> db :application :answers :hakukohteet :value)]
    (if (and (not-empty hakukohteet)
             (empty? hakukohde-answers))
      (-> db
          (assoc-in [:application :answers :hakukohteet :values] (map (fn [oid] {:valid true :value oid}) hakukohteet))
          (assoc-in [:application :answers :hakukohteet :valid] true))
      db)))

(defn- >0? [x]
  (when (> x 0)
    x))

(defn set-question-group-row-amounts [db]
  (let [flattened-form-fields (cljc-util/flatten-form-fields (-> db :form :content))]
    (reduce-kv (fn [db answer-key {:keys [value values]}]
                 (let [field-descriptor  (->> flattened-form-fields
                                              (filter (comp (partial = answer-key) keyword :id))
                                              (first))
                       question-group-id (-> field-descriptor :params :question-group-id)]
                   (cond-> db
                     question-group-id
                     (update-in [:application :ui question-group-id :count] #(let [provided-val ((some-fn >0?)
                                                                                                  (-> values count)
                                                                                                  (-> value count)
                                                                                                  1)]
                                                                               (if (> % provided-val)
                                                                                 %
                                                                                 provided-val))))))
               db
               (-> db :application :answers))))

(defn- merge-single-choice-values [value answer]
  (if (and (vector? value)
           (every? vector? value))
    (merge answer {:valid true
                   :value value
                   :values (mapv (partial mapv (fn [value]
                                                 {:valid true :value value}))
                                 value)})
    (merge answer {:valid true :value value})))

(defn- merge-dropdown-values [value answer]
  (if (and (vector? value)
           (every? vector? value))
    (let [values (mapv (partial mapv (fn [value] {:valid true :value value})) value)]
      (merge answer {:valid true :values values}))
    (merge answer {:valid true :value value})))

(defn- merge-submitted-answers [db submitted-answers]
  (-> db
      (update-in [:application :answers]
                 (fn [answers]
                   (reduce (fn [answers {:keys [key value cannot-edit cannot-view] :as answer}]
                             (let [answer-key (keyword key)
                                   value      (cond-> value
                                                (and (vector? value)
                                                     (not (supports-multiple-values (:fieldType answer))))
                                                (first))]
                               (if (contains? answers answer-key)
                                 (let [answer (match answer
                                                     {:fieldType "multipleChoice"}
                                                     (update answers answer-key (partial merge-multiple-choice-option-values value (-> db :application :answers)))

                                                     {:fieldType "singleChoice"}
                                                     (update answers answer-key (partial merge-single-choice-values value))

                                                     {:fieldType "dropdown"}
                                                     (update answers answer-key (partial merge-dropdown-values value))

                                                     {:fieldType (field-type :guard supports-multiple-values) :value (_ :guard vector?)}
                                                     (letfn [(parse-values [value-or-values]
                                                               (if (vector? value-or-values)
                                                                 (mapv parse-values value-or-values)
                                                                 (cond-> {:valid true :value value-or-values}
                                                                   (= field-type "attachment")
                                                                   (assoc :status :ready))))]
                                                       (update answers answer-key merge
                                                               {:valid  true
                                                                :values (parse-values (:value answer))}))

                                                     :else
                                                     (update answers answer-key merge {:valid true :value value}))]
                                   (update
                                     answer
                                     answer-key
                                     merge
                                     {:cannot-edit cannot-edit :cannot-view cannot-view}))
                                 answers)))
                           answers
                           submitted-answers)))
      (populate-hakukohde-answers-if-necessary)
      (set-have-finnish-ssn)
      (set-ssn-field-visibility)
      (set-country-specific-fields-visibility)
      (set-question-group-row-amounts)))

(defn- set-followup-visibility-to-false
  [db]
  (update-in db
             [:application :ui]
             merge
             (->> (autil/flatten-form-fields (:content (:form db)))
                  (filter :followup?)
                  (map (fn [field]
                         (let [id            (keyword (:id field))
                               has-value?    (or (some? (get-in db [:application :answers id :value]))
                                                 (some #(some? (:value %))
                                                       (get-in db [:application :answers id :values] [])))
                               has-children? (not (empty? (:children field)))]
                           {id {:visible? (or has-value? has-children?)}})))
                  (reduce merge))))

(defn- original-values->answers [db]
  (cond-> db
    (or (-> db :application :secret)
        (-> db :application :virkailija-secret))
    (update-in [:application :answers]
               (partial reduce-kv
                        (fn [answers answer-key {:keys [value values] :as answer}]
                          (let [answer (assoc answer :original-value (or values value))]
                            (assoc answers answer-key answer)))
                        {}))))

(defn- set-question-group-ids [fields & {:keys [question-group-id]}]
  (map (fn [{:keys [fieldClass id] :as field}]
         (cond (= fieldClass "questionGroup")
               (update field :children set-question-group-ids :question-group-id (keyword id))

               question-group-id
               (assoc-in field [:params :question-group-id] question-group-id)

               :else
               field))
       fields))

(defn handle-form [{:keys [db]} [_ answers form]]
  (let [form (-> (languages->kwd form)
                 (set-form-language)
                 (update :content set-question-group-ids))
        preselected-hakukohde (-> db :application :preselected-hakukohde)]
    {:db         (-> db
                     (update :form (fn [{:keys [selected-language]}]
                                     (cond-> form
                                             (some? selected-language)
                                             (assoc :selected-language selected-language))))
                     (assoc-in [:application :answers] (create-initial-answers form preselected-hakukohde))
                     (assoc-in [:application :show-hakukohde-search] false)
                     (assoc :wrapper-sections (extract-wrapper-sections form))
                     (merge-submitted-answers answers)
                     (original-values->answers)
                     (set-followup-visibility-to-false))
     :dispatch-n [[:application/hide-hakukohteet-if-no-tarjonta]
                  [:application/show-answers-belonging-to-hakukohteet]
                  [:application/hakukohde-query-change "" 0]
                  [:application/set-page-title]]}))

(reg-event-db
  :flasher
  (fn [db [_ flash]]
    (assoc db :flasher flash)))

(reg-event-fx
  :application/handle-form
  handle-form)

(reg-event-db
  :application/initialize-db
  initialize-db)

(reg-event-fx
  :application/textual-field-blur
  (fn [{db :db} [_ field]]
    (let [id (keyword (:id field))
          answer (get-in db [:application :answers id])]
      {:dispatch-n (if (or (empty? (:blur-rules field))
                           (not (:valid answer)))
                     []
                     [[:application/run-rule (:blur-rules field)]])})))

(reg-event-fx
  :application/set-application-field
  (fn [{db :db} [_ field value idx]]
    (let [id       (keyword (:id field))
          answers  (get-in db [:application :answers])
          answer   (get answers id)
          valid?   (or (:cannot-view answer)
                       (:cannot-edit answer)
                       (every? #(validator/validate % value answers field)
                               (:validators field)))
          changed? (not= value (:original-value answer))]
      {:db         (-> db
                       (update-in [:application :answers id] merge {:valid valid? :value value})
                       (update-in [:application :values-changed?] (fn [values]
                                                                    (let [values (or values #{})]
                                                                      (if changed?
                                                                        (conj values id)
                                                                        (disj values id))))))
       :dispatch-n (if (empty? (:rules field))
                     []
                     [[:application/run-rule (:rules field)]])})))

(defn- set-repeatable-field-values
  [db field-descriptor value data-idx question-group-idx]
  (let [id         (keyword (:id field-descriptor))
        answers    (get-in db [:application :answers])
        answer     (get answers id)
        valid?     (or (:cannot-view answer)
                       (:cannot-edit answer)
                       (every? #(validator/validate % value answers field-descriptor)
                               (:validators field-descriptor)))
        value-path (cond-> [:application :answers id :values]
                     question-group-idx (conj question-group-idx))]
    (-> db
        (update-in [:application :answers id :values] (vector-of-length (inc question-group-idx)))
        (update-in value-path
                   (fnil assoc []) data-idx {:valid valid? :value value}))))

(defn- set-multi-value-changed [db id & [subpath]]
  (let [{:keys [original-value value values]} (-> db :application :answers id)
        new-value (cond->> (or values value)
                    (vector? subpath)
                    (map #(get-in % subpath)))
        [new-diff original-diff _] (d/diff new-value
                                           original-value)]
    (update-in db [:application :values-changed?] (fn [values]
                                                    (let [values (or values #{})]
                                                      (if (and (empty? new-diff)
                                                               (empty? original-diff))
                                                        (disj values id)
                                                        (conj values id)))))))

(defn- set-repeatable-field-value
  [db field-descriptor]
  (let [id                   (keyword (:id field-descriptor))
        values               (get-in db [:application :answers id :values])
        required?            (some (partial = "required")
                                   (:validators field-descriptor))
        multi-value-answers? (every? #(or (vector? %) (list? %)) values)
        is-empty?            (if multi-value-answers?
                               (partial every? empty?)
                               (partial empty?))
        valid?               (if (is-empty? values)
                               (not required?)
                               (every? :valid (flatten values)))
        value-fn             (if multi-value-answers?
                               (partial map (partial map :value))
                               (partial map :value))]
    (-> db
        (update-in [:application :answers id]
                   merge
                   {:valid valid? :value (value-fn values)})
        (set-multi-value-changed id
                                 (when-not (= (:fieldType field-descriptor) "dropdown")
                                   [:value])))))

(reg-event-db
  :application/set-repeatable-application-field
  (fn [db [_ field-descriptor value data-idx question-group-idx]]
    (-> db
        (set-repeatable-field-values field-descriptor value data-idx question-group-idx)
        (set-repeatable-field-value field-descriptor))))

(defn- remove-repeatable-field-value
  [db field-descriptor data-idx question-group-idx]
  (let [id              (keyword (:id field-descriptor))
        raw-value-path  (cond-> [:application :answers id :values]
                          question-group-idx (conj question-group-idx))
        disp-value-path (cond-> [:application :answers id :value]
                          question-group-idx (conj question-group-idx))]
    (cond-> db
      (seq (get-in db raw-value-path))
      (update-in raw-value-path
                 #(autil/remove-nth % data-idx))

      ;; when creating application, we have the value below (and it's important). when editing, we do not.
      ;; consider this a temporary, terrible bandaid solution
      (seq (get-in db disp-value-path))
      (update-in disp-value-path
                 #(autil/remove-nth (vec %) data-idx))

      true
      (set-repeatable-field-value field-descriptor))))

(reg-event-db
  :application/remove-repeatable-application-field-value
  (fn [db [_ field-descriptor data-idx question-group-idx]]
    (remove-repeatable-field-value db field-descriptor data-idx question-group-idx)))

(defn default-error-handler [db [_ response]]
  (assoc db :error {:message "Tapahtui virhe " :detail (str response)}))

(defn application-run-rule [db rule]
  (if (not-empty rule)
    (rules/run-rule rule db)
    (rules/run-all-rules db)))

(reg-event-db
  :application/run-rule
  (fn [db [_ rule]]
    (if (#{:submitting :submitted} (-> db :application :submit-status))
      db
      (application-run-rule db rule))))

(reg-event-db
  :application/default-handle-error
  default-error-handler)

(reg-event-db
 :application/default-http-ok-handler
 (fn [db _] db))

(reg-event-db
  :state-update
  (fn [db [_ f]]
    (or (f db)
        db)))

(reg-event-db
  :application/handle-postal-code-input
  (fn [db [_ postal-office-name]]
    (update-in db [:application :answers :postal-office]
               merge {:value (:fi postal-office-name) :valid true})))

(reg-event-db
  :application/handle-postal-code-error
  (fn [db _]
    (-> db
        (update-in [:application :answers :postal-code]
                   merge {:valid false})
        (update-in [:application :answers :postal-office]
                   merge {:value "" :valid false}))))

(defn- set-field-visibility
  [db field-descriptor visible?]
  (let [id (keyword (:id field-descriptor))
        db (assoc-in db [:application :ui id :visible?] visible?)]
    (if (= "adjacentfieldset" (:fieldType field-descriptor))
      (reduce #(set-field-visibility %1 %2 visible?)
              db
              (:children field-descriptor))
      db)))

(defn- set-multiple-choice-followup-visibility
  [db field-descriptor option]
  (let [id (keyword (:id field-descriptor))
        selected? (get-in db [:application :answers id :options (:value option)])]
    (reduce #(set-field-visibility %1 %2 selected?)
            db
            (:followups option))))

(reg-event-db
  :application/toggle-multiple-choice-option
  (fn [db [_ field-descriptor option question-group-idx]]
    (let [id         (keyword (:id field-descriptor))
          validators (:validators field-descriptor)
          db      (-> db
                         (update-in [:application :answers id]
                                    (fn [answer]
                                      (toggle-multiple-choice-option answer
                                                                     (:value option)
                                                                     validators
                                                                     (-> db :application :answers)
                                                                     question-group-idx)))
                         (set-multi-value-changed id))]
      (cond-> db
        (not question-group-idx)
        (set-multiple-choice-followup-visibility field-descriptor option)))))

(reg-event-db
  :application/select-single-choice-button
  select-single-choice-button)

(reg-event-db
  :application/set-adjacent-field-answer
  (fn [db [_ field-descriptor idx value question-group-idx]]
    (-> db
        (set-repeatable-field-values field-descriptor value idx question-group-idx)
        (set-repeatable-field-value field-descriptor))))

(reg-event-db
  :application/add-adjacent-fields
  (fn [db [_ field-descriptor question-group-idx]]
    (reduce (fn [db child]
              (let [id (keyword (:id child))
                    new-idx (count (if question-group-idx
                                     (get-in db [:application :answers id :values question-group-idx])
                                     (get-in db [:application :answers id :values])))]
                (-> db
                    (set-repeatable-field-values child "" new-idx question-group-idx)
                    (set-repeatable-field-value child))))
            db
            (:children field-descriptor))))

(reg-event-db
  :application/remove-adjacent-field
  (fn [db [_ field-descriptor index]]
    (reduce #(remove-repeatable-field-value %1 %2 index nil)
            db
            (:children field-descriptor))))

(reg-event-fx
  :application/add-single-attachment
  (fn [{:keys [db]} [_ field-descriptor component-id attachment-idx file retries question-group-idx]]
    (let [name      (.-name file)
          form-data (doto (js/FormData.)
                      (.append "file" file name))]
      {:db   db
       :http {:method    :post
              :url       "/hakemus/api/files"
              :handler   [:application/handle-attachment-upload field-descriptor component-id attachment-idx question-group-idx]
              :error-handler [:application/handle-attachment-upload-error field-descriptor component-id attachment-idx name file (inc retries) question-group-idx]
              :body      form-data}})))

(reg-event-fx
  :application/add-attachments
  (fn [{:keys [db]} [_ field-descriptor component-id attachment-count files question-group-idx]]
    (let [files         (filter (fn [file]
                                  (let [prev-files (get-in db [:application :answers (keyword component-id) :values])
                                        new-file   {:filename (.-name file)
                                                    :size     (.-size file)}]
                                    (not (some (partial = new-file)
                                               (eduction (map :value)
                                                         (map #(select-keys % [:filename :size]))
                                                         prev-files)))))
                                files)
          dispatch-list (map-indexed (fn file->dispatch-vec [idx file]
                                       [:application/add-single-attachment field-descriptor component-id (+ attachment-count idx) file 0 question-group-idx])
                                     files)
          db            (if (not-empty files)
                          (as-> db db'
                                (update-in db' [:application :answers (keyword component-id) :values] (vector-of-length (or question-group-idx 0)))
                                (cond-> db'
                                  question-group-idx
                                  (update-in [:application :answers (keyword component-id) :values question-group-idx] (fnil identity [])))
                                (->> files
                                     (map-indexed (fn attachment-idx->file [idx file]
                                                    {:idx (+ attachment-count idx) :file file}))
                                     (reduce (fn attachment-spec->db [db {:keys [idx file]}]
                                               (assoc-in db (if question-group-idx
                                                              [:application :answers (keyword component-id) :values question-group-idx idx]
                                                              [:application :answers (keyword component-id) :values idx])
                                                 {:value   {:filename     (.-name file)
                                                            :content-type (.-type file)
                                                            :size         (.-size file)}
                                                  :valid   false
                                                  :too-big false
                                                  :status  :uploading}))
                                             db'))
                                (assoc-in db' [:application :answers (keyword component-id) :valid] false))
                          db)]
      {:db         db
       :dispatch-n dispatch-list})))

(defn- update-attachment-answer-validity [db field-descriptor component-id]
  (let [attachment-valid?        (fn [attachment]
                                   (every? true? (map #(validator/validate % attachment (-> db :application :answers) nil) (:validators field-descriptor))))
        required?                (some #{"required"} (:validators field-descriptor))
        answers-valid?           (fn [answers]
                                   (and (every? (comp true? :valid) answers)
                                        (or (not required?)
                                            (not (empty? answers)))))
        attachment-answer-valid? (fn [answer]
                                   (let [question-group-answer? (and (vector? (:values answer))
                                                                     (not (empty? (:values answer)))
                                                                     (every? vector? (:values answer)))
                                         validators-ok?         (if question-group-answer?
                                                                  (->> answer :values (every? (partial every? attachment-valid?)))
                                                                  (->> answer :values (every? attachment-valid?)))
                                         every-valid?           (if question-group-answer?
                                                                  (->> answer :values (every? answers-valid?))
                                                                  (->> answer :values answers-valid?))]
                                     (assoc answer :valid (and validators-ok? every-valid?))))]
    (update-in db [:application :answers (keyword component-id)] attachment-answer-valid?)))

(reg-event-db
  :application/handle-attachment-upload
  (fn [db [_ field-descriptor component-id attachment-idx question-group-idx response]]
    (let [path (if question-group-idx
                  [:application :answers (keyword component-id) :values question-group-idx attachment-idx]
                  [:application :answers (keyword component-id) :values attachment-idx])]
      (-> db
          (update-in path
                     merge
                     {:value response :valid true :status :ready})
          (update-attachment-answer-validity field-descriptor component-id)
          (set-multi-value-changed (keyword component-id) [:value])))))

(defn- rate-limit-error? [response]
  (= (:status response) 429))

(reg-event-fx
  :application/handle-attachment-upload-error
  (fn [{:keys [db]} [_ field-descriptor component-id attachment-idx filename file retries question-group-idx response]]
    (let [rate-limited? (rate-limit-error? response)
          current-error (if rate-limited?
                          {:fi "Tiedostoa ei ladattu, yritä uudelleen"
                           :en "File failed to upload, try again"
                           :sv "Fil inte laddat, försök igen"}
                          {:fi "Kielletty tiedostomuoto"
                           :en "File type forbidden"
                           :sv "Förbjudet filformat"})]
      (if (and rate-limited? (< retries 3))
        {:db db
         :delayed-dispatch {:dispatch-vec [:application/add-single-attachment field-descriptor component-id attachment-idx file retries question-group-idx]
                            :timeout (+ 2000 (rand-int 2000))}}
        {:db (-> db
                 (update-in (if question-group-idx
                              [:application :answers (keyword component-id) :values question-group-idx attachment-idx]
                              [:application :answers (keyword component-id) :values attachment-idx])
                            merge
                            {:value {:filename filename} :valid false :status :error :error current-error})
                 (update-attachment-answer-validity field-descriptor component-id))}))))

(reg-event-db
  :application/handle-attachment-delete
  (fn [db [_ field-descriptor component-id question-group-idx attachment-key _]]
    (-> db
        (update-in (if question-group-idx
                     [:application :answers (keyword component-id) :values question-group-idx]
                     [:application :answers (keyword component-id) :values])
                   (comp vec
                         (partial remove (comp (partial = attachment-key) :key :value))))
        (update-attachment-answer-validity field-descriptor component-id)
        (set-multi-value-changed (keyword component-id) [:value]))))

(reg-event-fx
  :application/remove-attachment
  (fn [{:keys [db]} [_ field-descriptor component-id attachment-idx question-group-idx]]
    (let [key       (get-in db (if question-group-idx
                                 [:application :answers (keyword component-id) :values question-group-idx attachment-idx :value :key]
                                 [:application :answers (keyword component-id) :values attachment-idx :value :key]))
          db        (-> db
                        (assoc-in [:application :answers (keyword component-id) :valid] false)
                        (update-in (if question-group-idx
                                     [:application :answers (keyword component-id) :values question-group-idx attachment-idx]
                                     [:application :answers (keyword component-id) :values attachment-idx])
                                   merge
                                   {:status :deleting
                                    :valid  false}))
          db-and-fx {:db db}]
      (if (get-in db [:application :editing?])
        (assoc db-and-fx :dispatch [:application/handle-attachment-delete field-descriptor component-id question-group-idx key])
        (assoc db-and-fx :http {:method  :delete
                                :url     (str "/hakemus/api/files/" key)
                                :handler [:application/handle-attachment-delete field-descriptor component-id question-group-idx key]})))))

(reg-event-db
  :application/remove-attachment-error
  (fn [db [_ field-descriptor component-id attachment-idx]]
    (-> db
        (update-in [:application :answers (keyword component-id) :values] autil/remove-nth attachment-idx)
        (update-attachment-answer-validity field-descriptor component-id))))

(reg-event-db
  :application/rating-hover
  (fn [db [_ star-number]]
    (assoc-in db [:application :feedback :star-hovered] star-number)))

(reg-event-db
  :application/rating-submit
  (fn [db [_ star-number]]
    (-> db
        (assoc-in [:application :feedback :stars] star-number)
        (assoc-in [:application :feedback :status] :rating-given))))

(reg-event-db
  :application/rating-update-feedback
  (fn [db [_ feedback-text]]
    (assoc-in db [:application :feedback :text] feedback-text)))

(reg-event-fx
  :application/rating-feedback-submit
  (fn [{:keys [db]}]
    (let [new-db    (assoc-in db [:application :feedback :status] :feedback-submitted)
          feedback  (-> db :application :feedback)
          text      (:text feedback)
          post-data {:form-key   (-> db :form :key)
                     :form-id    (-> db :form :id)
                     :form-name  (-> db :form :name)
                     :user-agent (.-userAgent js/navigator)
                     :rating     (:stars feedback)
                     :feedback   (when text
                                   (subs text 0 2000))}]
      {:db   new-db
       :http {:method    :post
              :post-data post-data
              :url       "/hakemus/api/feedback"}})))

(reg-event-db
  :application/rating-form-toggle
  (fn [db _]
    (update-in db [:application :feedback :hidden?] not)))

(reg-event-fx
  :application/set-page-title
  (fn [{:keys [db]}]
    (let [lang-kw       (keyword (-> db :form :selected-language))
          translations  (get-translations lang-kw application-view-translations)
          title-prefix  (:page-title translations)
          title-suffix  (or
                          (lang-kw (-> db :form :tarjonta :haku-name))
                          (-> db :form :name))]
      {:db db
       :set-page-title (str title-prefix " – " title-suffix)})))

(reg-event-db
  :application/add-question-group-row
  (fn add-question-group-row [db [_ field-descriptor-id]]
    (update-in db [:application :ui (keyword field-descriptor-id) :count] (fnil inc 2))))
