(ns ataru.virkailija.editor.handlers
  (:require [re-frame.core :refer [reg-event-db reg-event-fx dispatch dispatch-sync subscribe]]
            [clojure.data :refer [diff]]
            [clojure.walk :as walk]
            [cljs-time.core :as c]
            [cljs.core.async :as async]
            [cljs.core.match :refer-macros [match]]
            [ataru.virkailija.form-sorting :refer [sort-by-time-and-deletedness]]
            [ataru.virkailija.autosave :as autosave]
            [ataru.component-data.component :as component]
            [ataru.component-data.person-info-module :as pm]
            [ataru.virkailija.dev.lomake :as dev]
            [ataru.virkailija.editor.components.followup-question :as followup]
            [ataru.virkailija.editor.editor-macros :refer-macros [with-form-key]]
            [ataru.virkailija.editor.handlers-macros :refer-macros [with-path-and-index]]
            [ataru.virkailija.routes :refer [set-history!]]
            [ataru.virkailija.virkailija-ajax :refer [http post dispatch-flasher-error-msg]]
            [ataru.util :as util]
            [ataru.cljs-util :as cu]
            [taoensso.timbre :refer-macros [spy debug]]
            [ataru.virkailija.temporal :as temporal])
  (:require-macros [ataru.async-macros :as asyncm]
                   [cljs.core.async.macros :refer [go-loop]]))

(defn get-user-info [db _]
  (http
    :get
    "/lomake-editori/api/user-info"
    :editor/handle-user-info)
  db)

(reg-event-db :editor/get-user-info get-user-info)

(defn- current-form-content-path
  [db & further-path]
  (-> [:editor :forms (-> db :editor :selected-form-key) :content]
      (concat further-path)
      (flatten)))

(reg-event-fx
  :editor/remove-dropdown-option
  (fn [cofx [_ & path]]
    (let [option-path (current-form-content-path (:db cofx) [path])]
      {:db (update-in (:db cofx) (drop-last option-path) util/remove-nth (last option-path))
       :dispatch [:editor/remove-followup-question (first path)]})))

(reg-event-db
  :editor/add-dropdown-option
  (fn [db [_ & path]]
    (let [dropdown-path (current-form-content-path db [path :options])
          component     (ataru.component-data.component/dropdown-option)]
      (->
        (update-in db dropdown-path into [component])
        (assoc-in [:editor :ui (:id component) :focus?] true)))))

(reg-event-db
  :editor/set-dropdown-option-value
  (fn [db [_ value & path]]
    (let [label-path (current-form-content-path db [path])
          this-option-path (drop-last 2 label-path)
          value-path (flatten [this-option-path :value])
          option-updated-db (-> db
                                (assoc-in label-path value)
                                (assoc-in value-path value))]
      option-updated-db)))

(reg-event-db
  :editor/select-custom-multi-options
  (fn [db [_ & path]]
    (let [dropdown-path (current-form-content-path db [path])]
      (update-in db dropdown-path dissoc :koodisto-source))))

(reg-event-db
  :editor/select-koodisto-options
  (fn [db [_ uri version title & path]]
    (let [dropdown-path (current-form-content-path db [path])]
      (update-in db dropdown-path assoc :koodisto-source {:uri uri :version version :title title}))))

(defn add-validator
  [db [_ validator & path]]
  (let [path (current-form-content-path db [path :validators])]
    (update-in db path (fn [validators]
                         (when-not (some #(= % validator) validators)
                           (conj validators validator))))))

(reg-event-db :editor/add-validator add-validator)

(defn remove-validator
  [db [_ validator & path]]
  (let [path       (current-form-content-path db [path :validators])
        validators (get-in db path)]
    (if-not (nil? validators)
      (update-in db path (fn [validators]
                           (remove #(= % validator) validators)))
      db)))

(reg-event-db :editor/remove-validator remove-validator)

(reg-event-db
  :editor/set-component-value
  (fn [db [_ value & path]]
    (assoc-in db (current-form-content-path db [path]) value)))

(defn generate-component
  [db [_ generate-fn sub-path]]
  (let [parent-component-path (cond-> (current-form-content-path db)
                                (not (number? sub-path))
                                (concat (butlast sub-path)))
        components            (if (vector? generate-fn)
                                (map #(apply % []) generate-fn)
                                [(generate-fn)])
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
      (catch js/Error e #(do (dispatch [:editor/unset-used-by-haut])
                             (.log js/console e))))))

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
         :fetch-hakukohde-groups [hakukohderyhmat-promise]
         }))))

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
      (-> db
          (update :editor dissoc :ui)
          (assoc-in [:editor :forms key] (-> response
                                             (languages->kwd)
                                             (update :created-time temporal/str->googdate)))
          (assoc-in [:editor :autosave]
                    (autosave/interval-loop {:subscribe-path    [:editor :forms key]
                                             :changed-predicate editor-autosave-predicate
                                             :handler           (fn [form previous-autosave-form-at-time-of-dispatch]
                                                                  (dispatch [:editor/save-form]))}))))))

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

(defn save-loop [save-chan]
  (go-loop [_ (async/<! save-chan)]
    (let [form (-> @(subscribe [:editor/selected-form])
                   (dissoc :created-time))
          response-chan (async/chan)]
      (when (not-empty (:content form))
        (do
          (post "/lomake-editori/api/forms" form
            (fn [db response] (do (async/put! response-chan response)
                                  db))
            :override-args {:error-handler (fn [error]
                                             (async/close! response-chan)
                                             (dispatch-flasher-error-msg :post error))})
          (when-let [updated-form (some->
                                    (async/<! response-chan)
                                    (languages->kwd))]
            (dispatch-sync
              [:state-update
               (fn [db]
                 ; Merge updated form without content, because
                 ; user might have typed something between requests and
                 ; updated-form would replace the newer content
                 (do (async/close! response-chan)
                     (update-in db [:editor :forms (:key updated-form)]
                       merge (dissoc updated-form :content :name))))])))))
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
     {:name             {:fi "Uusi lomake"}
      :content          [(component/hakukohteet) (pm/person-info-module)]
      :languages        [:fi]})
    db))

(defn- copy-form [db _]
  (let [form-id (get-in db [:editor :selected-form-key])
        form    (-> (get-in db [:editor :forms form-id])
                    (update :name (fn [name]
                                    (reduce-kv #(assoc %1 %2 (str %3 " - KOPIO"))
                                               {}
                                               name))))]
    (post-new-form (select-keys form [:name :content :languages :organization-oid]))
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
      [b :children xb]       ; topwards
      [(dec b) :children xb] ; bottomwards
      )

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
    (let [path (conj (vec (current-form-content-path db path))
                     :belongs-to-hakukohteet)]
      (update-in db path (fnil (comp vec #(conj % oid) set) [])))))

(reg-event-db
  :editor/remove-from-belongs-to-hakukohteet
  (fn [db [_ path oid]]
    (let [path (conj (vec (current-form-content-path db path))
                     :belongs-to-hakukohteet)]
      (update-in db path (fnil (comp vec #(disj % oid) set) [])))))

(reg-event-db
  :editor/add-to-belongs-to-hakukohderyhma
  (fn [db [_ path oid]]
    (let [path (conj (vec (current-form-content-path db path))
                 :belongs-to-hakukohderyhma)]
      (update-in db path (fnil (comp vec #(conj % oid) set) [])))))

(reg-event-db
  :editor/remove-from-belongs-to-hakukohderyhma
  (fn [db [_ path oid]]
    (let [path (conj (vec (current-form-content-path db path))
                 :belongs-to-hakukohderyhma)]
      (update-in db path (fnil (comp vec #(disj % oid) set) [])))))

(reg-event-db
  :editor/fold-all
  (fn [db _]
    (assoc-in db [:editor :ui :all-folded] true)))

(reg-event-db
  :editor/unfold-all
  (fn [db _]
    (assoc-in db [:editor :ui :all-folded] false)))
