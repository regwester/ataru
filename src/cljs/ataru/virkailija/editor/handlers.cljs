(ns ataru.virkailija.editor.handlers
  (:require [re-frame.core :refer [reg-event-db reg-event-fx dispatch dispatch-sync subscribe]]
            [clojure.data :refer [diff]]
            [clojure.walk :as walk]
            [cljs-time.core :as c]
            [cljs.core.async :as async]
            [cljs.core.match :refer-macros [match]]
            [ataru.component-data.value-transformers :refer [update-options-while-keeping-existing-followups]]
            [ataru.virkailija.form-sorting :refer [sort-by-time-and-deletedness]]
            [ataru.virkailija.autosave :as autosave]
            [ataru.component-data.component :as component]
            [ataru.component-data.person-info-module :as pm]
            [ataru.virkailija.dev.lomake :as dev]
            [ataru.virkailija.editor.components.followup-question :as followup]
            [ataru.virkailija.editor.editor-macros :refer-macros [with-form-key]]
            [ataru.virkailija.editor.handlers-macros :refer-macros [with-path-and-index]]
            [ataru.virkailija.routes :refer [set-history!]]
            [ataru.virkailija.virkailija-ajax :refer [http post put dispatch-flasher-error-msg]]
            [ataru.util :as util]
            [ataru.cljs-util :as cu]
            [taoensso.timbre :refer-macros [spy debug]]
            [ataru.virkailija.temporal :as temporal]
            [ataru.virkailija.editor.form-diff :as form-diff]
            [cljs-time.core :as t])
  (:require-macros [ataru.async-macros :as asyncm]
                   [cljs.core.async.macros :refer [go-loop]]))

(defn get-user-info [db _]
  (http
    :get
    "/lomake-editori/api/user-info"
    :editor/handle-user-info)
  db)

(reg-event-db :editor/get-user-info get-user-info)

(reg-event-db
  :editor/set-save-snapshot
  (fn [db [_ form]]
    (assoc-in db [:editor :save-snapshot] form)))

(defn- current-form-content-path
  [db & further-path]
  (-> [:editor :forms (-> db :editor :selected-form-key) :content]
      (concat further-path)
      (flatten)))

(defn- set-non-koodisto-option-values
  [field-descriptor]
  (if (nil? (:koodisto-source field-descriptor))
    (update field-descriptor :options
            #(vec (map-indexed (fn [i option]
                                 (assoc option :value (str i)))
                               %)))
    field-descriptor))

(reg-event-db
  :editor/remove-dropdown-option
  (fn [db [_ & path]]
    (let [option-path (current-form-content-path db [path])]
      (-> db
          (update-in (drop-last option-path) util/remove-nth (last option-path))
          (update-in (drop-last 2 option-path) set-non-koodisto-option-values)))))

(reg-event-db
  :editor/add-dropdown-option
  (fn [db [_ & path]]
    (let [dropdown-path (current-form-content-path db [path :options])
          component     (ataru.component-data.component/dropdown-option)]
      (-> db
          (update-in dropdown-path into [component])
          (update-in (drop-last dropdown-path) set-non-koodisto-option-values)))))

(reg-event-db
  :editor/set-ordered-by-user
  (fn [db [_ value & path]]
    (let [component-path (current-form-content-path db [path])]
      (update-in db component-path assoc :koodisto-ordered-by-user (not value)))))

(reg-event-db
  :editor/set-dropdown-option-value
  (fn [db [_ value & path]]
    (let [label-path (current-form-content-path db [path])]
      (assoc-in db label-path value))))

(defn- swap-vector [v i1 i2]
  (assoc v i2 (v i1) i1 (v i2)))

(reg-event-db
  :editor/move-option-up
  (fn [db [_ path index]]
    (if (= 0 index)
      db
      (let [options-path (current-form-content-path db [path :options])]
        (-> db
            (update-in options-path swap-vector index (dec index))
            (update-in (drop-last options-path) set-non-koodisto-option-values))))))

(reg-event-db
  :editor/move-option-down
  (fn [db [_ path index]]
    (let [options-path    (current-form-content-path db [path :options])
          options         (get-in db options-path)
          is-last-option? (= (inc index) (count options))]
      (if is-last-option?
        db
        (-> db
            (update-in options-path swap-vector index (inc index))
            (update-in (drop-last options-path) set-non-koodisto-option-values))))))

(reg-event-db
  :editor/select-custom-multi-options
  (fn [db [_ & path]]
    (let [dropdown-path (current-form-content-path db [path])]
      (-> db
          (update-in dropdown-path dissoc :koodisto-source)
          (update-in dropdown-path assoc :options [])))))

(reg-event-db
  :editor/select-koodisto-options
  (fn [db [_ uri version title & path]]
    (let [dropdown-path (current-form-content-path db [path])]
      (update-in db dropdown-path assoc :koodisto-source {:uri uri :version version :title title} :options []))))

(defn- update-modified-by
  [db path]
  (let [metadata-path (current-form-content-path db (conj (first path) :metadata :modified-by))
        user-info     (-> db :editor :user-info)]
    (assoc-in db metadata-path {:oid  (:oid user-info)
                                :name (:name user-info)
                                :date (temporal/datetime-now)})))

(defn add-validator
  [db [_ validator & path]]
  (let [content-path (current-form-content-path db [path :validators])]
    (-> db
        (update-in content-path (fn [validators]
                                  (when-not (some #(= % validator) validators)
                                    (conj validators validator))))
        (update-modified-by path))))

(reg-event-db :editor/add-validator add-validator)

(defn remove-validator
  [db [_ validator & path]]
  (let [content-path (current-form-content-path db [path :validators])
        validators   (get-in db content-path)]
    (if-not (nil? validators)
      (-> db
          (update-in content-path (fn [validators]
                                    (remove #(= % validator) validators)))
          (update-modified-by path))
      db)))

(reg-event-db :editor/remove-validator remove-validator)

(reg-event-db
  :editor/set-component-value
  (fn [db [_ value & path]]
    (-> db
        (assoc-in (current-form-content-path db [path]) value)
        (update-modified-by path))))

(defn generate-component
  [db [_ generate-fn sub-path]]
  (let [parent-component-path (cond-> (current-form-content-path db)
                                      (not (number? sub-path))
                                      (concat (butlast sub-path)))
        user-info             (-> db :editor :user-info)
        metadata-element      {:oid  (:oid user-info)
                               :name (:name user-info)
                               :date (temporal/datetime-now)}
        metadata              {:created-by  metadata-element
                               :modified-by metadata-element}
        components            (if (vector? generate-fn)
                                (map #(apply % [metadata]) generate-fn)
                                [(generate-fn metadata)])
        first-component-idx   (cond-> sub-path
                                      (not (number? sub-path))
                                      (last))]
    (as-> db db'
      (update-in db' parent-component-path (cu/vector-of-length (count components)))
      (reduce (fn [db' [idx component]]
                (let [path (flatten [parent-component-path (+ first-component-idx idx)])]
                  (assoc-in db' path component)))
              db'
              (map vector (range) components))
      (assoc-in db' [:editor :ui (-> components first :id) :focus?] true))))

(reg-event-db :generate-component generate-component)

(defn- remove-component
  [db path]
  (with-form-key [db form-key]
    (let [remove-index (last path)
          path-vec     (-> [:editor :forms form-key :content [path]]
                         flatten
                         butlast)]
      (->> (get-in db path-vec)
           (keep-indexed (fn [index element]
                           (when-not (= index remove-index) element)))
           (into [])
           (assoc-in db path-vec)))))

(reg-event-db
  :editor/remove-component
  (fn [db [_ path]]
    (-> db
        (update-in [:editor :forms-meta] assoc path :removed)
        (update-in [:editor :ui :remove-component-button-state] dissoc path)
        (remove-component path))))

(reg-event-fx
  :editor/confirm-remove-component
  (fn [{db :db} [_ path]]
    {:db (-> db
             (assoc-in [:editor :forms-meta path] :fade-out)
             (assoc-in [:editor :ui :remove-component-button-state path] :disabled))
     :dispatch-later [{:ms 310 :dispatch [:editor/remove-component path]}]}))

(reg-event-db
  :editor/unstart-remove-component
  (fn [db [_ path]]
    (cond-> db
      (= :confirm (get-in db [:editor :ui :remove-component-button-state path]))
      (assoc-in [:editor :ui :remove-component-button-state path] :active))))

(reg-event-fx
  :editor/start-remove-component
  (fn [{db :db} [_ path]]
    {:db (assoc-in db [:editor :ui :remove-component-button-state path] :confirm)
     :dispatch-later [{:ms 2000 :dispatch [:editor/unstart-remove-component path]}]}))

(defn- on-haku-data-fetched [hakukohteet-promise hakukohderyhmat-promise]
  (async/go
    (try
      (let [hakukohderyhmat (asyncm/<? hakukohderyhmat-promise)
            haut            (asyncm/<? hakukohteet-promise)
            haun-ryhmaliitokset (set (mapcat #(:ryhmaliitokset %) (mapcat #(:hakukohteet %) (vals haut))))]
        (dispatch [:editor/set-used-by-haut
                   haut
                   (filter #(contains? haun-ryhmaliitokset (:oid %)) hakukohderyhmat)]))
      (catch js/Error e
        (dispatch [:editor/unset-used-by-haut])
        (throw e)))))

(reg-event-fx
  :editor/refresh-used-by-haut
  (fn [{db :db} _]
    (when-let [form-key (get-in db [:editor :selected-form-key])]
      (let [haku-oids (map (comp :haku-oid second) (get-in db [:editor :forms-in-use (keyword form-key)]))
            organization-oids (map :oid (get-in db [:editor :user-info :organizations] []))
            hakukohderyhmat-promise (async/promise-chan)
            hakukohteet-promise (async/promise-chan)]
        (on-haku-data-fetched hakukohteet-promise hakukohderyhmat-promise)
        {:db (-> db
               (assoc-in [:editor :used-by-haut :fetching?] true)
               (assoc-in [:editor :used-by-haut :error?] false))
         :fetch-haut-with-hakukohteet [hakukohteet-promise organization-oids haku-oids]
         :fetch-hakukohde-groups [hakukohderyhmat-promise]}))))

(reg-event-db
  :editor/handle-user-info
  (fn [db [_ user-info-response]]
    (assoc-in db [:editor :user-info] user-info-response)))

(defn- languages->kwd [form]
  (update form :languages
    (partial mapv keyword)))

(defn- parse-form-created-times
  [form]
  (assoc form :created-time (temporal/str->googdate (:created-time form))))

(defn- keep-selected-form-as-is [db]
  (if-let [selected-form (get-in db [:editor :forms (get-in db [:editor :selected-form-key])])]
    (fn [form]
      (if (= (:key selected-form) (:key form))
        selected-form
        form))
    identity))

(defn refresh-forms-for-editor []
  (http
   :get
   "/lomake-editori/api/forms"
   (fn [db {:keys [forms]}]
     (assoc-in db [:editor :forms] (->> forms
                                        (mapv parse-form-created-times)
                                        (mapv languages->kwd)
                                        (mapv (keep-selected-form-as-is db))
                                        (util/group-by-first :key)
                                        (sort-by-time-and-deletedness))))
   :skip-parse-times? true))

(reg-event-db
  :editor/refresh-forms-for-editor
  (fn [db _]
    (autosave/stop-autosave! (-> db :editor :autosave))
    (refresh-forms-for-editor)
    db))

(reg-event-db
  :editor/refresh-forms-if-empty
  (fn [db [_ form-key]]
    (if (-> db :editor :forms (get form-key))
      db
      (do
        (autosave/stop-autosave! (-> db :editor :autosave))
        (refresh-forms-for-editor)
        db))))

(defn- editor-autosave-predicate [current prev]
  (match [current (merge {:content []} prev)]
    [_ {:content []}]
    false

    :else
    (not=
      ; :id changes when new version is created,
      ; :key remains the same across versions
      (dissoc prev :created-time :id)
      (dissoc current :created-time :id))))

(reg-event-db
  :editor/set-used-by-haut
  (fn [db [_ haut hakukohderyhmat]]
    (-> db
      (assoc-in [:editor :used-by-haut :fetching?] false)
      (assoc-in [:editor :used-by-haut :error?] false)
      (assoc-in [:editor :used-by-haut :haut] haut)
      (assoc-in [:editor :used-by-haut :hakukohderyhmat] hakukohderyhmat))))

(reg-event-db
  :editor/unset-used-by-haut
  (fn [db [_ haut]]
    (-> db
      (assoc-in [:editor :used-by-haut :fetching?] false)
      (assoc-in [:editor :used-by-haut :error?] true)
      (update-in [:editor :used-by-haut] dissoc :haut)
      (update-in [:editor :used-by-haut] dissoc :hakukohderyhmat))))

(reg-event-db
  :editor/handle-fetch-form
  (fn [db [_ {:keys [key deleted] :as response} _]]
    (if deleted
      db
      (let [new-form (-> response
                       (languages->kwd)
                       (update :created-time temporal/str->googdate))]
      (-> db
          (update :editor dissoc :ui)
          (assoc-in [:editor :forms key] new-form)
          (assoc-in [:editor :save-snapshot] new-form)
          (assoc-in [:editor :autosave]
                    (autosave/interval-loop {:subscribe-path    [:editor :forms key]
                                             :changed-predicate editor-autosave-predicate
                                             :handler           (fn [form previous-autosave-form-at-time-of-dispatch]
                                                                  (dispatch [:editor/save-form]))})))))))

(defn- fetch-form-content-fx
  [form-id]
  {:http {:method :get
          :path (str "/lomake-editori/api/forms/" form-id)
          :skip-parse-times? true
          :handler-or-dispatch :editor/handle-fetch-form}})

(reg-event-fx
  :editor/select-form
  (fn [{db :db} [_ form-key]]
    (with-form-key [db previous-form-key]
      (merge
       {:db (cond-> db
              (not (nil? previous-form-key)) (update-in [:editor :forms previous-form-key] assoc :content [])
              true (assoc-in [:editor :selected-form-key] form-key))}
       {:dispatch [:editor/refresh-used-by-haut]}
       (when (and (some? previous-form-key)
                  (not= previous-form-key form-key))
         {:stop-autosave (get-in db [:editor :autosave])})
       (when-let [id (get-in db [:editor :forms form-key :id])]
         (fetch-form-content-fx id))))))

(def save-chan (async/chan (async/sliding-buffer 1)))

(defn fragment-updates-from-difference [form]
  (let [prev-form @(subscribe [:editor/last-save-snapshot])]
    (seq (form-diff/as-operations prev-form form))))

(defn update-form-with-fragment [form fragments]
  (let [response-promise (async/promise-chan)]
    (put (str "/lomake-editori/api/forms/" (:id form)) fragments
      (fn [db response] (do (async/put! response-promise response)
                            db))
      :override-args {:skip-parse-times? true
                      :error-handler (fn [error]
                                       (async/put! response-promise (js/Error. error)))})
    response-promise))

(defn save-loop [save-chan]
  (go-loop [_ (async/<! save-chan)]
    (let [form (-> @(subscribe [:editor/selected-form])
                   (dissoc :created-time))]
      (when (not-empty (:content form))
        (try
          (if-let [fragments (fragment-updates-from-difference form)]
            (do
              (asyncm/<? (update-form-with-fragment form fragments))
              (dispatch [:editor/set-save-snapshot form])))
          (catch js/Error error
            (do
              (prn error)
              (dispatch [:snackbar-message
                         [(str "Toinen käyttäjä teki muutoksen lomakkeeseen \"" (some #(get-in form [:name %])
                                                                                  [:fi :sv :en]) "\"")
                          "Lataa sivu uudelleen ja tarkista omat muutokset"]])
              (dispatch-flasher-error-msg :post error))))))
    (recur (async/<! save-chan))))

(save-loop save-chan)

(defn save-form
  [db _]
  (async/put! save-chan true)
  db)

(reg-event-db :editor/save-form save-form)

(defn- post-new-form
  [form]
  (post "/lomake-editori/api/forms"
        form
        (fn [db form]
          (let [stop-fn (get-in db [:editor :autosave])
                path (str "/lomake-editori/editor/" (:key (languages->kwd form)))]
            (autosave/stop-autosave! stop-fn)
            (set-history! path)
            (assoc-in db [:editor :new-form-created?] true)))))

(reg-event-db
  :editor/add-form
  (fn [db _]
    (post-new-form
      {:name      {:fi "Uusi lomake"}
       :content   [(component/hakukohteet) (pm/person-info-module)]
       :languages [:fi]
       :locked    nil
       :locked-by nil})
    db))

(defn- copy-form [db _]
  (let [form-id (get-in db [:editor :selected-form-key])
        form    (-> (get-in db [:editor :forms form-id])
                    (update :name (fn [name]
                                    (reduce-kv #(assoc %1 %2 (str %3 " - KOPIO"))
                                               {}
                                               name))))]
    (post-new-form (select-keys form [:name :content :languages :organization-oid :locked :locked-by]))
    db))

(reg-event-db :editor/copy-form copy-form)

(defn- removed-application-review-active? [db]
  (let [selected-form-key (get-in db [:editor :selected-form-key])
        form-in-review    (get-in db [:application :selected-application-and-form :form])]
    (= selected-form-key (:key form-in-review))))

(defn- reset-application-review-state [db]
  (assoc db :application {}))

(reg-event-fx
  :editor/confirm-remove-form
  (fn [{:keys [db]} _]
    (let [form-key (get-in db [:editor :selected-form-key])
          form-id  (get-in db [:editor :forms form-key :id])]
      (-> {:db   (cond-> (-> db
                             (update :editor dissoc :selected-form-key)
                             (assoc-in [:editor :ui :remove-form-button-state] :disabled))
                   (removed-application-review-active? db)
                   (reset-application-review-state))
           :http {:method              :delete
                  :path                (str "/lomake-editori/api/forms/" form-id)
                  :handler-or-dispatch :editor/refresh-forms-for-editor}}))))

(reg-event-db
  :editor/unstart-remove-form
  (fn [db _]
    (cond-> db
      (= :confirm (get-in db [:editor :ui :remove-form-button-state]))
      (assoc-in [:editor :ui :remove-form-button-state] :active))))

(reg-event-fx
  :editor/start-remove-form
  (fn [{db :db} _]
    {:db (assoc-in db [:editor :ui :remove-form-button-state] :confirm)
     :dispatch-later [{:ms 2000 :dispatch [:editor/unstart-remove-form]}]}))

(reg-event-fx
  :editor/refresh-forms-in-use
  (fn [_ _]
    {:http {:method              :get
            :path                "/lomake-editori/api/forms-in-use"
            :handler-or-dispatch :editor/update-forms-in-use
            :skip-parse-times?   true}}))

(reg-event-fx
  :editor/update-forms-in-use
  (fn [{db :db} [_ result]]
    {:db (assoc-in db [:editor :forms-in-use] result)
     :dispatch [:editor/refresh-used-by-haut]}))

(reg-event-db
  :editor/change-form-name
  (fn [db [_ lang new-form-name]]
    (with-form-key [db selected-form-key]
      (assoc-in db [:editor :forms selected-form-key :name lang] new-form-name))))

(reg-event-db
  :editor/change-form-organization
  (fn [db [_ new-form-organization-oid]]
    (with-form-key [db selected-form-key]
      (update-in db [:editor :forms selected-form-key]
                 assoc :organization-oid
                 new-form-organization-oid))))

(defn- remove-component-from-list
  [db source-path]
  (with-path-and-index [db source-path component-list-path remove-idx]
    (update-in db component-list-path
      (fn [components]
        (vec
          (concat
            (subvec components 0 remove-idx)
            (subvec components (inc remove-idx))))))))

(defn- add-component-to-list
  [db component target-path]
  (with-path-and-index [db target-path component-list-path add-idx]
    (update-in db component-list-path
      (fn [components]
        (vec
          (concat
            (subvec components 0 add-idx)
            [component]
            (subvec components add-idx)))))))

(defn- recalculate-target-path-prevent-oob
  [source-path target-path]
  (match [source-path target-path]
    ; moving-top-level components
    [[n :guard integer?] [nn :guard integer?]]
    (or (when (> nn n)
          [(dec nn)])
      [(max 0 nn)])

    [[a :children xa] [b :children xb]]
    (or
      (when (and (> xb xa) (= a b))
       [b :children (dec xb)])  ; moving within same component-group, index out of bounds prevention
      [b :children (max 0 xb)]) ; moving between component-groups

    ; moving component from root-level into a component-group
    [[a] [b :children xb]]
    (if (-> b (< a))
      [b :children xb]        ; topwards
      [(dec b) :children xb]) ; bottomwards

    :else target-path))

(defn move-component
  [db [_ source-path target-path]]
  (with-form-key [db form-key]
    (let [component                (get-in db (concat [:editor :forms form-key :content] source-path))
          recalculated-target-path (recalculate-target-path-prevent-oob source-path target-path)
          result-is-nested-component-group? (and
                                              (contains?
                                                (set recalculated-target-path) :children)
                                              (= "wrapperElement" (:fieldClass component)))]
      (if result-is-nested-component-group?
        db ; Nesting is not allowed/supported
        (-> db
          (remove-component-from-list source-path)
          (add-component-to-list component recalculated-target-path))))))

(reg-event-db :editor/move-component move-component)

(def ^:private lang-order
  [:fi :sv :en])

(defn- index-of [coll t]
  (let [length (count coll)]
    (loop [idx 0]
      (cond
        (<= length idx) -1
        (= (get coll idx) t) idx
        :else (recur (inc idx))))))

(defn- toggle [x set]
  (if (contains? set x)
    (disj set x)
    (conj set x)))

(defn- if-empty [default seq]
  (if (empty? seq) default seq))

(defn- toggle-language [db [_ lang]]
  (let [selected (get-in db [:editor :selected-form-key])]
    (-> db
        (update-in [:editor :forms selected :languages]
                   (fn [languages]
                     (->> (set languages)
                          (toggle lang)
                          (if-empty languages)
                          (sort-by (partial index-of lang-order)))))
        (update-in [:editor :forms selected :name]
                   (fn [name]
                     (->> (if (contains? name lang)
                            (dissoc name lang)
                            (assoc name lang ""))
                          (if-empty name))))
        (update-in [:editor :ui]
                   (fn [ui]
                     (clojure.walk/prewalk
                      (fn [x]
                        (if (= [:focus? true] x)
                          [:focus? false]
                          x))
                      ui))))))

(reg-event-db :editor/toggle-language toggle-language)

(reg-event-fx
  :editor/fetch-koodisto-for-component-with-id
  (fn [{db :db} [_ id {:keys [uri version]}]]
    {:http {:method              :get
            :path                (str "/lomake-editori/api/koodisto/" uri "/" version)
            :handler-or-dispatch :editor/set-new-koodisto-while-keeping-existing-followups
            :handler-args        {:id id :uri uri :version version}}}))

(reg-event-db
  :editor/set-new-koodisto-while-keeping-existing-followups
  (fn [db [_ new-koodisto {:keys [id uri version]}]]
    (let [key                       (get-in db [:editor :selected-form-key])
          form                      (get-in db [:editor :forms key :content])
          new-options               (mapv #(select-keys % [:value :label])
                                          new-koodisto)
          update-koodisto-component (fn [component]
                                      (assoc component :options
                                             (update-options-while-keeping-existing-followups new-options (:options component))))
          find-koodisto-component   (fn [component]
                                      (if (and (= id (:id component))
                                               (= uri (get-in component [:koodisto-source :uri]))
                                               (= version (get-in component [:koodisto-source :version])))
                                        (update-koodisto-component component)
                                        component))
          updated-form              (clojure.walk/prewalk find-koodisto-component form)]
      (assoc-in db [:editor :forms key :content] updated-form))))

(reg-event-fx
  :editor/show-belongs-to-hakukohteet-modal
  (fn [{db :db} [_ id]]
    (cond-> {:db (assoc-in db [:editor :ui id :belongs-to-hakukohteet :modal :show] true)}
      (get-in db [:editor :used-by-haut :error?])
      (assoc :dispatch [:editor/refresh-used-by-haut]))))

(reg-event-db
  :editor/hide-belongs-to-hakukohteet-modal
  (fn [db [_ id]]
    (assoc-in db [:editor :ui id :belongs-to-hakukohteet :modal :show] false)))

(reg-event-db
  :editor/belongs-to-hakukohteet-modal-show-more
  (fn [db [_ id haku-oid]]
      (update-in db [:editor :ui id :belongs-to-hakukohteet :modal haku-oid :show-more-value] + 15)))

(reg-event-db
  :editor/set-belongs-to-hakukohteet-modal-search-term
  (fn [db [_ id search-term]]
    (if (< (count search-term) 3)
      (update-in db [:editor :ui id :belongs-to-hakukohteet :modal] dissoc :search-term)
      (assoc-in db [:editor :ui id :belongs-to-hakukohteet :modal :search-term] search-term))))

(reg-event-fx
  :editor/on-belongs-to-hakukohteet-modal-search-term-change
  (fn [{db :db} [_ id search-term]]
    {:db (assoc-in db [:editor :ui id :belongs-to-hakukohteet :modal :search-term-value]
                   search-term)
     :dispatch-debounced {:timeout 200
                          :id [:belongs-to-hakukohteet-search id]
                          :dispatch [:editor/set-belongs-to-hakukohteet-modal-search-term
                                     id search-term]}}))

(reg-event-db
  :editor/add-to-belongs-to-hakukohteet
  (fn [db [_ path oid]]
    (let [content-path (conj (vec (current-form-content-path db path))
                        :belongs-to-hakukohteet)]
      (-> db
          (update-in content-path (fnil (comp vec #(conj % oid) set) []))
          (update-modified-by [path])))))

(reg-event-db
  :editor/remove-from-belongs-to-hakukohteet
  (fn [db [_ path oid]]
    (let [content-path (conj (vec (current-form-content-path db path))
                        :belongs-to-hakukohteet)]
      (-> db
          (update-in content-path (fnil (comp vec #(disj % oid) set) []))
          (update-modified-by [path])))))

(reg-event-db
  :editor/add-to-belongs-to-hakukohderyhma
  (fn [db [_ path oid]]
    (let [content-path (conj (vec (current-form-content-path db path))
                        :belongs-to-hakukohderyhma)]
      (-> db
          (update-in content-path (fnil (comp vec #(conj % oid) set) []))
          (update-modified-by [path])))))

(reg-event-db
  :editor/remove-from-belongs-to-hakukohderyhma
  (fn [db [_ path oid]]
    (let [content-path (conj (vec (current-form-content-path db path))
                        :belongs-to-hakukohderyhma)]
      (-> db
          (update-in content-path (fnil (comp vec #(disj % oid) set) []))
          (update-modified-by [path])))))

(defn- fold [db id]
  (assoc-in db [:editor :ui id :folded?] true))

(defn- unfold [db id]
  (assoc-in db [:editor :ui id :folded?] false))

(reg-event-db
  :editor/fold
  (fn [db [_ id]]
    (fold db id)))

(reg-event-db
  :editor/unfold
  (fn [db [_ id]]
    (unfold db id)))

(reg-event-db
  :editor/fold-all
  (fn [db _]
    (->> (get-in db (vec (current-form-content-path db)))
         (filter #(= "fieldset" (:fieldType %)))
         (map :id)
         (reduce fold db))))

(reg-event-db
  :editor/unfold-all
  (fn [db _]
    (reduce unfold db (keys (get-in db [:editor :ui])))))

(reg-event-fx
  :editor/toggle-email-template-editor
  (fn [{db :db} _]
    (let [now-visible? (-> db
                           (get-in [:editor :ui :template-editor-visible?])
                           (not))]
      (cond-> {:db (assoc-in db [:editor :ui :template-editor-visible?] now-visible?)}
              now-visible? (merge {:dispatch [:editor/load-email-template]})))))

(reg-event-db
  :editor/update-form-lock
  (fn [db [_ response args]]
    (let [form-key (-> args :form-key)]
      (-> db
          (assoc-in [:editor :forms form-key :locked] (:locked response))
          (assoc-in [:editor :forms form-key :locked-by] (-> db :editor :user-info :name))
          (assoc-in [:editor :forms form-key :id] (:id response))))))

(reg-event-fx
  :editor/toggle-form-editing-lock
  (fn [{db :db} _]
    (let [form-key  (-> db :editor :selected-form-key)
          form      (get-in db [:editor :forms form-key])
          operation (if (some? (get-in db [:editor :forms form-key :locked]))
                      "open"
                      "close")]
      {:http {:method              :put
              :path                (str "/lomake-editori/api/forms/" (:id form) "/lock/" operation)
              :handler-or-dispatch :editor/update-form-lock
              :handler-args        {:form-key form-key}}})))

(defn- add-stored-content-to-templates
  [previews]
  (map #(assoc % :stored-content (dissoc % :stored-content)) previews))

(defn- preview-map-to-list
  [previews]
  (let [contents              (->> previews
                                   (vals)
                                   (map #(select-keys % [:lang :content :content-ending :subject])))
        contents-changed      @(subscribe [:editor/email-templates-altered])
        only-changed-contents (filter #(get contents-changed (:lang %)) contents)]
    only-changed-contents))

(defn- preview-list-to-map
  [previews]
  (util/group-by-first :lang previews))

(defn- stored-preview-list-to-map
  [previews]
  (->> previews
      (add-stored-content-to-templates)
      (util/group-by-first :lang)))

(reg-event-fx
  :editor/update-email-preview-immediately
  (fn [{db :db} [_]]
    (let [form-key              (get-in db [:editor :selected-form-key])
          contents              (preview-map-to-list (get-in db [:editor :email-template form-key]))]
      {:http {:method              :post
              :params              {:contents contents}
              :handler-args        {:form-key form-key}
              :path                (str "/lomake-editori/api/email-template/" form-key "/previews")
              :handler-or-dispatch :editor/update-email-template-preview}})))

(reg-event-fx
  :editor/update-email-preview
  (fn [{db :db} [_ lang path content]]
    {:db                 (assoc-in db [:editor :email-template (get-in db [:editor :selected-form-key]) lang path] content)
     :dispatch-debounced {:timeout  500
                          :id       :email-template-preview
                          :dispatch [:editor/update-email-preview-immediately]}}))

(reg-event-db
  :editor/update-email-template-preview
  (fn [db [_ contents {form-key :form-key}]]
    (update-in db
               [:editor :email-template form-key]
               (partial merge-with merge)
               (preview-list-to-map contents))))

(reg-event-db
  :editor/update-saved-email-template-preview
  (fn [db [_ contents {form-key :form-key}]]
    (assoc-in db
              [:editor :email-template form-key]
              (stored-preview-list-to-map contents))))

(reg-event-fx
  :editor/save-email-template
  (fn [{db :db} [_]]
    (let [contents (preview-map-to-list @(subscribe [:editor/email-template]))
          form-key (get-in db [:editor :selected-form-key])]
      {:http {:method              :post
              :params              {:contents contents}
              :handler-args        {:form-key form-key}
              :path                (str "/lomake-editori/api/email-templates/" form-key)
              :handler-or-dispatch :editor/update-saved-email-template-preview}})))

(reg-event-fx
  :editor/load-email-template
  (fn [{db :db} [_]]
    (let [form-key (get-in db [:editor :selected-form-key])]
      {:http {:method              :get
              :handler-args        {:form-key form-key}
              :path                (str "/lomake-editori/api/email-templates/" form-key)
              :handler-or-dispatch :editor/update-saved-email-template-preview}})))

(reg-event-fx
  :editor/update-organization-select-query
  (fn [{db :db} [_ query]]
    {:db (assoc-in db [:editor :organizations :query] query)
     :dispatch-debounced {:timeout 500
                          :id [:organization-query]
                          :dispatch [:editor/do-organization-query]}}))

(reg-event-fx
  :editor/do-organization-query
  (fn [{db :db} [_]]
    {:http {:method :get
            :path (str "/lomake-editori/api/organization/user-organizations?query=" (-> db :editor :organizations :query))
            :handler-or-dispatch :editor/update-organization-query-results}}))

(reg-event-fx
  :editor/update-organization-query-results
  (fn [{db :db} [_ results]]
    {:db (assoc-in db [:editor :organizations :matches] results)}))

(reg-event-fx
  :editor/select-organization
  (fn [{db :db} [_ oid]]
    {:http {:method              :post
            :path                (str "/lomake-editori/api/organization/user-organization/"
                                      oid
                                      "?rights="
                                      (clojure.string/join "&rights=" ["edit-applications" "view-applications" "form-edit"]))
            :handler-or-dispatch :editor/update-selected-organization}
     :db   (assoc-in db [:editor :user-info :selected-organization :rights] [:edit-applications :view-applications :form-edit])}))

(reg-event-fx
  :editor/update-selected-organization
  (fn [{db :db} [_ selected-organization]]
    {:db         (assoc-in db
                           [:editor :user-info :selected-organization]
                           (not-empty selected-organization))
     :navigate   "/lomake-editori/editor"}))

(reg-event-fx
  :editor/remove-selected-organization
  (fn [_]
    {:http {:method              :delete
            :path                "/lomake-editori/api/organization/user-organization"
            :handler-or-dispatch :editor/update-selected-organization}}))

(reg-event-fx
  :editor/update-selected-organization-rights
  (fn [{db :db} [_ right selected?]]
    (let [db (if selected?
               (update-in db [:editor :user-info :selected-organization :rights] conj right)
               (update-in db [:editor :user-info :selected-organization :rights] (partial remove #{right})))
          rights (->> db :editor :user-info :selected-organization :rights
                      not-empty)]
      {:db   db
       :http {:method              :post
              :path                (str "/lomake-editori/api/organization/user-organization/"
                                        (-> db :editor :user-info :selected-organization :oid)
                                        (when rights
                                          (str "?rights=" (clojure.string/join "&rights=" rights))))
              :handler-or-dispatch :editor/update-selected-organization}})))
