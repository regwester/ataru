(ns ataru.hakija.application-form-components
  (:require [clojure.string :refer [trim]]
            [re-frame.core :refer [subscribe dispatch dispatch-sync]]
            [reagent.ratom :refer-macros [reaction]]
            [markdown.core :refer [md->html]]
            [cljs.core.match :refer-macros [match]]
            [ataru.cljs-util :as cljs-util :refer [get-translation]]
            [ataru.application-common.application-field-common
             :refer
             [answer-key
              required-hint
              scroll-to-anchor
              is-required-field?
              group-spacer
              markdown-paragraph]]
            [ataru.hakija.application-hakukohde-component :as hakukohde]
            [ataru.hakija.pohjakoulutusristiriita :as pohjakoulutusristiriita]
            [ataru.util :as util]
            [reagent.core :as r]
            [taoensso.timbre :refer-macros [spy debug]]
            [ataru.feature-config :as fc]
            [clojure.string :as string]
            [ataru.hakija.person-info-fields :refer [editing-forbidden-person-info-field-ids]]))

(declare render-field)

(defn- visible? [ui field-descriptor]
  (and (get-in @ui [(keyword (:id field-descriptor)) :visible?] true)
       (or (empty? (:children field-descriptor))
           (some (partial visible? ui) (:children field-descriptor)))))

(defn- text-field-size->class [size]
  (match size
         "S" "application__form-text-input__size-small"
         "M" "application__form-text-input__size-medium"
         "L" "application__form-text-input__size-large"
         :else "application__form-text-input__size-medium"))

(defn- textual-field-change [field-descriptor evt]
  (let [value (-> evt .-target .-value)]
    (dispatch [:application/set-application-field field-descriptor value])))

(defn- multi-value-field-change [field-descriptor data-idx question-group-idx event]
  (let [value (some-> event .-target .-value)]
    (dispatch [:application/set-repeatable-application-field field-descriptor value data-idx question-group-idx])))

(defn- field-id [field-descriptor]
  (str "field-" (:id field-descriptor)))

(def field-types-supporting-label-for
  "These field types can use the <label for=..> syntax, others will use aria-labelled-by"
  #{"textField" "textArea" "dropdown"})

(defn- id-for-label
  [field-descriptor]
  (when-not (contains? field-types-supporting-label-for (:fieldType field-descriptor))
    (str "application-form-field-label-" (:id field-descriptor))))

(defn- label [field-descriptor]
  (let [languages  (subscribe [:application/default-languages])
        label-meta (if-let [label-id (id-for-label field-descriptor)]
                     {:id label-id}
                     {:for (:id field-descriptor)})]
    (fn [field-descriptor]
      (let [label (util/non-blank-val (:label field-descriptor) @languages)]
        [:label.application__form-field-label
         label-meta
         [:span (str label (required-hint field-descriptor))]
         [scroll-to-anchor field-descriptor]]))))

(defn- show-text-field-error-class?
  [field-descriptor value valid?]
  (and
    (false? valid?)
    (or (is-required-field? field-descriptor)
        (-> field-descriptor :params :numeric))
    (if (string? value)
      (not (clojure.string/blank? value))
      (not (empty? value)))))

(defn info-text [field-descriptor]
  (let [languages (subscribe [:application/default-languages])]
    (fn [field-descriptor]
      (when-let [info (util/non-blank-val (-> field-descriptor :params :info-text :label) @languages)]
        [markdown-paragraph info (-> field-descriptor :params :info-text-collapse)]))))

(defn question-hakukohde-names
  ([field-descriptor]
   (question-hakukohde-names field-descriptor :question-for-hakukohde))
  ([field-descriptor translation-key]
   (let [show-hakukohde-list? (r/atom false)]
     (fn [field-descriptor]
       (let [lang                           @(subscribe [:application/form-language])
             selected-hakukohteet-for-field @(subscribe [:application/selected-hakukohteet-for-field field-descriptor])]
         [:div.application__question_hakukohde_names_container
          [:a.application__question_hakukohde_names_info
           {:on-click #(swap! show-hakukohde-list? not)}
           (str (get-translation translation-key) " (" (count selected-hakukohteet-for-field) ")")]
          (when @show-hakukohde-list?
            [:ul.application__question_hakukohde_names
             (for [hakukohde selected-hakukohteet-for-field
                   :let [name          (util/non-blank-val (:name hakukohde) [lang :fi :sv :en])
                         tarjoaja-name (util/non-blank-val (:tarjoaja-name hakukohde) [lang :fi :sv :en])]]
               [:li {:key (str (:id field-descriptor) "-" (:oid hakukohde))}
                name " - " tarjoaja-name])])])))))

(defn- belongs-to-hakukohde-or-ryhma? [field]
  (seq (concat (:belongs-to-hakukohteet field)
               (:belongs-to-hakukohderyhma field))))

(defn text-field [field-descriptor & {:keys [div-kwd disabled editing idx] :or {div-kwd :div.application__form-field disabled false editing false}}]
  (let [id           (keyword (:id field-descriptor))
        answer       (if (and (contains? editing-forbidden-person-info-field-ids id)
                              @(subscribe [:application/cannot-edit? id]))
                       {:value @(subscribe [:state-query
                                            [:application :person id]])
                        :valid true}
                       @(subscribe [:state-query
                                    (cond-> [:application :answers id]
                                      idx (concat [:values idx 0]))]))
        languages           (subscribe [:application/default-languages])
        size         (get-in field-descriptor [:params :size])
        size-class   (text-field-size->class size)
        on-blur      #(dispatch [:application/textual-field-blur field-descriptor])
        on-change    (if idx
                       (partial multi-value-field-change field-descriptor 0 idx)
                       (partial textual-field-change field-descriptor))
        show-error?  (show-text-field-error-class? field-descriptor
                                                   (:value answer)
                                                   (:valid answer))]
    [div-kwd
     [label field-descriptor]
     (when (belongs-to-hakukohde-or-ryhma? field-descriptor)
       [question-hakukohde-names field-descriptor])
     [:div.application__form-text-input-info-text
      [info-text field-descriptor]]
     [:div.application__form-text-input-and-validation-errors
      [:input.application__form-text-input
       (merge {:id           id
               :type         "text"
               :placeholder  (when-let [input-hint (-> field-descriptor :params :placeholder)]
                               (util/non-blank-val input-hint @languages))
               :class        (str size-class
                                  (if show-error?
                                    " application__form-field-error"
                                    " application__form-text-input--normal"))
               :value        (if @(subscribe [:application/cannot-view? id])
                               "***********"
                               (:value answer))
               :on-blur      on-blur
               :on-change    on-change
               :required     (is-required-field? field-descriptor)
               :aria-invalid @(subscribe [:application/answer-invalid? id])}
              (when (or disabled
                        @(subscribe [:application/cannot-edit? id]))
                {:disabled true}))]
      (when (not-empty (:errors answer))
        [:div.application__validation-error-dialog
         [:div.application__validation-error-dialog__arrow]
         [:div.application__validation-error-dialog__box
          (doall
           (map-indexed (fn [idx error]
                          (with-meta (util/non-blank-val error @languages)
                            {:key (str "error-" idx)}))
                        (:errors answer)))]])]]))

(defn repeatable-text-field [field-descriptor & {:keys [div-kwd] :or {div-kwd :div.application__form-field}}]
  (let [id           (keyword (:id field-descriptor))
        size-class   (text-field-size->class (get-in field-descriptor [:params :size]))
        cannot-edit? (subscribe [:application/cannot-edit? id])]
    (fn [field-descriptor & {div-kwd :div-kwd question-group-idx :idx :or {div-kwd :div.application__form-field}}]
      (let [values    (subscribe [:state-query [:application :answers id :values question-group-idx]])
            on-blur   (fn [evt]
                        (let [data-idx (int (.getAttribute (.-target evt) "data-idx"))]
                          (dispatch [:application/remove-repeatable-application-field-value field-descriptor data-idx question-group-idx])))
            on-change (fn [evt]
                        (let [value    (some-> evt .-target .-value)
                              data-idx (int (.getAttribute (.-target evt) "data-idx"))]
                          (dispatch [:application/set-repeatable-application-field field-descriptor value data-idx question-group-idx])))]
        (into [div-kwd
               [label field-descriptor]
               (when (belongs-to-hakukohde-or-ryhma? field-descriptor)
                 [question-hakukohde-names field-descriptor])
               [:div.application__form-text-input-info-text
                [info-text field-descriptor]]]
          (cons
            (let [{:keys [value valid]} (first @values)]
              [:div.application__form-repeatable-text-wrap.application__form-repeatable-text-wrap--padded
               [:input.application__form-text-input
                (merge
                 {:type         "text"
                  :class        (str size-class (if (show-text-field-error-class? field-descriptor value valid)
                                                  " application__form-field-error"
                                                  " application__form-text-input--normal"))
                  :value        value
                  :data-idx     0
                  :on-change    on-change
                  :required     (is-required-field? field-descriptor)
                  :aria-invalid @(subscribe [:application/answer-invalid? id])}
                 (when (empty? value)
                   {:on-blur on-blur})
                 (when @cannot-edit?
                   {:disabled true}))]])
            (map-indexed
              (let [first-is-empty? (empty? (first (map :value @values)))]
                (fn [idx {:keys [value last?]}]
                  [:div.application__form-repeatable-text-wrap
                   {:class (when last? "application__form-repeatable-text-wrap--padded")}
                   [:input.application__form-text-input
                    (merge
                      {:type      "text"
                       :class     (str
                                    size-class " application__form-text-input--normal"
                                    (when-not value " application__form-text-input--disabled"))
                       :value     value
                       :data-idx  (inc idx)
                       :on-change on-change}
                      (when (and (not last?) (empty? value))
                        {:on-blur on-blur})
                      (when last?
                        {:placeholder (get-translation :add-more)})
                      (when (or @cannot-edit?
                                (and last? first-is-empty?))
                        {:disabled true}))]
                   (when (and value (not @cannot-edit?))
                     [:a.application__form-repeatable-text--addremove
                      [:i.zmdi.zmdi-close.zmdi-hc-lg
                       {:data-idx (inc idx)
                        :on-click on-blur}]])]))
              (concat (rest @values)
                      [{:value nil :valid true :last? true}]))))))))

(defn- text-area-size->class [size]
  (match size
         "S" "application__form-text-area__size-small"
         "M" "application__form-text-area__size-medium"
         "L" "application__form-text-area__size-large"
         :else "application__form-text-area__size-medium"))

(defn- parse-max-length [field]
  (let [max-length (-> field :params :max-length)]
    (when-not (or (empty? max-length) (= "0" max-length))
      max-length)))

(defn text-area [field-descriptor & {:keys [div-kwd] :or {div-kwd :div.application__form-field}}]
  (let [application  (subscribe [:state-query [:application]])
        size         (-> field-descriptor :params :size)
        max-length   (parse-max-length field-descriptor)
        cannot-edit? (subscribe [:application/cannot-edit? (keyword (:id field-descriptor))])]
    (fn [field-descriptor & {:keys [div-kwd idx] :or {div-kwd :div.application__form-field}}]
      (let [value-path (cond-> [:application :answers (-> field-descriptor :id keyword)]
                         idx (conj :values idx 0)
                         true (conj :value))
            value     (subscribe [:state-query value-path])
            on-change (if idx
                        (partial multi-value-field-change field-descriptor 0 idx)
                        (partial textual-field-change field-descriptor))]
        [div-kwd
         [label field-descriptor]
         (when (belongs-to-hakukohde-or-ryhma? field-descriptor)
           [question-hakukohde-names field-descriptor])
         [:div.application__form-text-area-info-text
          [info-text field-descriptor]]
         [:textarea.application__form-text-input.application__form-text-area
          (merge {:id            (:id field-descriptor)
                  :class         (text-area-size->class size)
                  :maxLength     max-length
                  ; default-value because IE11 will "flicker" on input fields. This has side-effect of NOT showing any
                  ; dynamically made changes to the text-field value.
                  :default-value @value
                  :on-change     on-change
                  :value         @value
                  :required      (is-required-field? field-descriptor)
                  :aria-invalid  @(subscribe [:application/answer-invalid? (-> field-descriptor :id keyword)])}
                 (when @cannot-edit?
                   {:disabled true}))]
         (when max-length
           [:span.application__form-textarea-max-length (str (count @value) " / " max-length)])]))))

(declare render-field)

(defn wrapper-field [field-descriptor children]
  (let [languages           (subscribe [:application/default-languages])]
    (fn [field-descriptor children]
      (let [label (util/non-blank-val (:label field-descriptor) @languages)]
        [:div.application__wrapper-element
         [:div.application__wrapper-heading
          [:h2 label]
          [scroll-to-anchor field-descriptor]]
         (into [:div.application__wrapper-contents]
           (for [child children]
             [render-field child]))]))))

(defn- remove-question-group-button [field-descriptor idx]
  (let [mouse-over? (subscribe [:application/mouse-over-remove-question-group-button
                                field-descriptor
                                idx])
        on-mouse-over (fn [_]
                        (dispatch [:application/remove-question-group-mouse-over
                                   field-descriptor
                                   idx]))
        on-mouse-out (fn [_]
                       (dispatch [:application/remove-question-group-mouse-out
                                  field-descriptor
                                  idx]))
        on-click (fn [_]
                   (dispatch [:application/remove-question-group-row
                              field-descriptor
                              idx]))]
    (fn [_ _]
      [(if @mouse-over?
         :i.zmdi.zmdi-close.application__remove-question-group-row.application__remove-question-group-row-mouse-over
         :i.zmdi.zmdi-close.application__remove-question-group-row)
       {:on-mouse-over on-mouse-over
        :on-mouse-out on-mouse-out
        :on-click on-click}])))

(defn- question-group-row [field-descriptor children idx can-remove?]
  (let [mouse-over? (subscribe [:application/mouse-over-remove-question-group-button
                                field-descriptor
                                idx])]
    [(if @mouse-over?
       :div.application__question-group-row.application__question-group-row-mouse-over
       :div.application__question-group-row)
     [:div.application__question-group-row-content
      (for [child children]
        ^{:key (str (:id child) "-" idx)}
        [render-field child :idx idx])]
     (when can-remove?
       [remove-question-group-button field-descriptor idx])]))

(defn question-group [field-descriptor children]
  (let [languages     (subscribe [:application/default-languages])
        label         (util/non-blank-val (:label field-descriptor) @languages)
        row-count     (subscribe [:state-query [:application :ui (-> field-descriptor :id keyword) :count]])
        cannot-edits? (map #(subscribe [:application/cannot-edit? (keyword (:id %))])
                        (util/flatten-form-fields children))]
    [:div.application__question-group
     (when-not (clojure.string/blank? label)
       [:h3.application__question-group-heading label])
     [scroll-to-anchor field-descriptor]
     [:div
      (doall
       (for [idx (range (or @row-count 1))]
         ^{:key (str "question-group-row-" idx)}
         [question-group-row
          field-descriptor
          children
          idx
          (and (< 1 @row-count) (not (some deref cannot-edits?)))]))]
     (when (not (some deref cannot-edits?))
       [:div.application__add-question-group-row
        [:a {:href     "#"
             :on-click (fn add-question-group-row [event]
                         (.preventDefault event)
                         (dispatch [:application/add-question-group-row (:id field-descriptor)]))}
         [:span.zmdi.zmdi-plus-circle.application__add-question-group-plus-sign]
         (get-translation :add-more-button)]])]))

(defn row-wrapper [children]
  (into [:div.application__row-field-wrapper]
        ; flatten fields here because 'rowcontainer' may
        ; have nested fields because
        ; of validation (for example :one-of validator)
        (for [child (util/flatten-form-fields children)]
          [render-field child :div-kwd :div.application__row-field.application__form-field])))

(defn- dropdown-followups [field-descriptor value]
  (when-let [followups (seq (util/resolve-followups
                             (:options field-descriptor)
                             value))]
    [:div.application__form-dropdown-followups.animated.fadeIn
     (for [followup followups]
       ^{:key (:id followup)}
       [render-field followup])]))

(defn- non-blank-option-label [option langs]
  (util/non-blank-val (:label option) langs))

(defn dropdown [field-descriptor & {:keys [div-kwd editing idx] :or {div-kwd :div.application__form-field editing false}}]
  (let [application  (subscribe [:state-query [:application]])
        languages    (subscribe [:application/default-languages])
        id           (answer-key field-descriptor)
        disabled?    @(subscribe [:application/cannot-edit? id])
        use-onr-info? (contains? (:person application) id)
        value-path   (if (and @editing
                              (contains? editing-forbidden-person-info-field-ids id)
                              use-onr-info?)
                       [:application :person id]
                       (cond-> [:application :answers id]
                         idx (concat [:values idx 0])
                         :always (concat [:value])))
        value        (subscribe [:state-query value-path])
        on-change    (fn [e]
                       (dispatch [:application/dropdown-change
                                  field-descriptor
                                  (.-value (.-target e))
                                  idx]))]
    [div-kwd
     [label field-descriptor]
     (when (belongs-to-hakukohde-or-ryhma? field-descriptor)
       [question-hakukohde-names field-descriptor])
     [:div.application__form-text-input-info-text
      [info-text field-descriptor]]
     [:div.application__form-select-wrapper
      (if disabled?
        [:span.application__form-select-arrow.application__form-select-arrow__disabled
         [:i.zmdi.zmdi-chevron-down]]
        [:span.application__form-select-arrow
         [:i.zmdi.zmdi-chevron-down]])
      [(keyword (str "select.application__form-select" (when (not disabled?) ".application__form-select--enabled")))
       {:id           (:id field-descriptor)
        :value        (or @value "")
        :on-change    on-change
        :disabled     disabled?
        :required     (is-required-field? field-descriptor)
        :aria-invalid @(subscribe [:application/answer-invalid? id])}
       (doall
         (concat
           (when
             (and
               (nil? (:koodisto-source field-descriptor))
               (not (:no-blank-option field-descriptor))
               (not= "" (:value (first (:options field-descriptor)))))
             [^{:key (str "blank-" (:id field-descriptor))} [:option {:value ""} ""]])
           (map-indexed
             (fn [idx option]
               [:option {:value (:value option)
                         :key   idx}
                (non-blank-option-label option @languages)])
             (cond->> (:options field-descriptor)
                      (and (some? (:koodisto-source field-descriptor))
                           (not (:koodisto-ordered-by-user field-descriptor)))
                      (sort-by #(non-blank-option-label % @languages))))))]]
     (when-not idx
       (dropdown-followups field-descriptor @value))]))

(defn- multi-choice-followups [followups]
  [:div.application__form-multi-choice-followups-outer-container
   [:div.application__form-multi-choice-followups-indicator]
   [:div.application__form-multi-choice-followups-container.animated.fadeIn
    (map (fn [followup]
           ^{:key (:id followup)}
           [render-field followup])
         followups)]])

(defn- multiple-choice-option [field-descriptor option parent-id question-group-idx]
  (let [languages    (subscribe [:application/default-languages])
        label        (non-blank-option-label option @languages)
        value        (:value option)
        option-id    (util/component-id)
        cannot-edit? (subscribe [:application/cannot-edit? (keyword (:id field-descriptor))])]
    (fn [field-descriptor option parent-id question-group-idx]
      (let [on-change (fn [_]
                        (dispatch [:application/toggle-multiple-choice-option field-descriptor option question-group-idx]))
            checked?  (subscribe [:application/multiple-choice-option-checked? parent-id value question-group-idx])
            ui        (subscribe [:state-query [:application :ui]])]
        [:div {:key option-id}
         [:input.application__form-checkbox
          (merge {:id        option-id
                  :type      "checkbox"
                  :checked   @checked?
                  :value     value
                  :on-change on-change
                  :role "option"}
                 (when @cannot-edit? {:disabled true}))]
         [:label
          (merge {:for option-id}
                 (when @cannot-edit? {:class "disabled"}))
          label]
         (when (and @checked?
                    (not-empty (:followups option))
                    (some (partial visible? ui) (:followups option))
                    (not question-group-idx))
           [multi-choice-followups (:followups option)])]))))

(defn multiple-choice
  [field-descriptor & {:keys [div-kwd disabled] :or {div-kwd :div.application__form-field disabled false}}]
  (let [id           (answer-key field-descriptor)
        languages    (subscribe [:application/default-languages])]
    (fn [field-descriptor & {:keys [div-kwd disabled idx] :or {div-kwd :div.application__form-field disabled false}}]
      [div-kwd
       [label field-descriptor]
       (when (belongs-to-hakukohde-or-ryhma? field-descriptor)
         [question-hakukohde-names field-descriptor])
       [:div.application__form-text-input-info-text
        [info-text field-descriptor]]
       [:div.application__form-outer-checkbox-container
        {:aria-labelledby (id-for-label field-descriptor)
         :aria-invalid    @(subscribe [:application/answer-invalid? id])
         :role       "listbox"}
        (doall
          (map-indexed (fn [option-idx option]
                         ^{:key (str "multiple-choice-" (:id field-descriptor) "-" option-idx (when idx (str "-" idx)))}
                         [multiple-choice-option field-descriptor option id idx])
                       (cond->> (:options field-descriptor)
                         (and (some? (:koodisto-source field-descriptor))
                              (not (:koodisto-ordered-by-user field-descriptor)))
                         (sort-by #(non-blank-option-label % @languages)))))]])))

(defn- single-choice-option [option parent-id field-descriptor question-group-idx languages use-multi-choice-style?]
  (let [cannot-edit? (subscribe [:application/cannot-edit? (keyword (:id field-descriptor))])
        label        (util/non-blank-val (:label option) @languages)
        option-value (:value option)
        option-id    (util/component-id)
        checked?     (subscribe [:application/single-choice-option-checked? parent-id option-value question-group-idx])
        on-change    (fn [event]
                       (let [value (.. event -target -value)]
                         (dispatch [:application/select-single-choice-button value field-descriptor question-group-idx])))]
    (fn [option parent-id field-descriptor question-group-idx lang use-multi-choice-style?]
      [:div.application__form-single-choice-button-inner-container {:key option-id}
       [:input
        (merge {:id        option-id
                :type      "checkbox"
                :checked   @checked?
                :value     option-value
                :on-change on-change
                :role      "radio"
                :class     (if use-multi-choice-style?
                             "application__form-checkbox"
                             "application__form-single-choice-button")}
               (when @cannot-edit? {:disabled true}))]
       [:label
        (merge {:for option-id}
               (when @cannot-edit? {:class "disabled"}))
        label]
       (when (and @checked?
                  (not-empty (:followups option))
                  (some (partial visible? (subscribe [:state-query [:application :ui]])) (:followups option)))
         (if use-multi-choice-style?
           (multi-choice-followups (:followups option))
           [:div.application__form-single-choice-followups-indicator]))])))

(defn- use-multi-choice-style? [single-choice-field langs]
  (or (< 3 (count (:options single-choice-field)))
      (some (fn [option]
              (let [label (util/non-blank-val (:label option) langs)]
                (< 50 (count label))))
        (:options single-choice-field))))

(defn single-choice-button [field-descriptor & {:keys [div-kwd] :or {div-kwd :div.application__form-field}}]
  (let [button-id               (answer-key field-descriptor)
        validators              (:validators field-descriptor)
        languages               (subscribe [:application/default-languages])
        use-multi-choice-style? (use-multi-choice-style? field-descriptor @languages)]
    (fn [field-descriptor & {:keys [div-kwd idx] :or {div-kwd :div.application__form-field}}]
      (let [single-choice-value (subscribe [:state-query [:application :answers (keyword (:id field-descriptor)) :value]])
            followups           (->> (:options field-descriptor)
                                     (filter (comp (partial = @single-choice-value) :value))
                                     (map :followups)
                                     (first))]
        [div-kwd
         {:class "application__form-single-choice-button-container"}
         [label field-descriptor]
         (when (belongs-to-hakukohde-or-ryhma? field-descriptor)
           [question-hakukohde-names field-descriptor])
         [:div.application__form-text-input-info-text
          [info-text field-descriptor]]
         [:div.application__form-single-choice-button-outer-container
          {:aria-labelledby (id-for-label field-descriptor)
           :aria-invalid    @(subscribe [:application/answer-invalid? button-id])
           :role            "radiogroup"
           :class           (when use-multi-choice-style? "application__form-single-choice-button-container--column")}
          (doall
            (map-indexed (fn [option-idx option]
                           ^{:key (str "single-choice-" (when idx (str idx "-")) (:id field-descriptor) "-" option-idx)}
                           [single-choice-option option button-id field-descriptor idx languages use-multi-choice-style?])
                         (:options field-descriptor)))]
         (when (and (not idx)
                    (not use-multi-choice-style?)
                    (seq followups)
                    (some (partial visible? (subscribe [:state-query [:application :ui]])) followups))
           [:div.application__form-multi-choice-followups-container.animated.fadeIn
            (for [followup followups]
              ^{:key (:id followup)}
              [render-field followup])])]))))

(defonce max-attachment-size-bytes (* 10 1024 1024))

(defn- upload-attachment [field-descriptor component-id attachment-count question-group-idx event]
  (.preventDefault event)
  (let [file-list  (or (some-> event .-dataTransfer .-files)
                       (.. event -target -files))
        files      (->> (.-length file-list)
                        (range)
                        (map #(.item file-list %)))
        file-sizes (map #(.-size %) files)]
    (if (some #(> % max-attachment-size-bytes) file-sizes)
      (dispatch [:application/show-attachment-too-big-error component-id question-group-idx])
      (dispatch [:application/add-attachments field-descriptor component-id attachment-count files question-group-idx]))))

(defn attachment-upload [field-descriptor component-id attachment-count question-group-idx]
  (let [id       (str component-id (when question-group-idx "-" question-group-idx) "-upload-button")]
    [:div.application__form-upload-attachment-container
     [:input.application__form-upload-input
      {:id           id
       :type         "file"
       :multiple     "multiple"
       :key          (str "upload-button-" component-id "-" attachment-count)
       :on-change    (partial upload-attachment field-descriptor component-id attachment-count question-group-idx)
       :required     (is-required-field? field-descriptor)
       :aria-invalid @(subscribe [:application/answer-invalid? id])}]
     [:label.application__form-upload-label
      {:for id}
      [:i.zmdi.zmdi-cloud-upload.application__form-upload-icon]
      [:span.application__form-upload-button-add-text (get-translation :add-attachment)]]
     (let [file-size-info-text (get-translation :file-size-info)
           size-error-path     (if question-group-idx
                                 [:application :answers (keyword component-id) :errors question-group-idx :too-big]
                                 [:application :answers (keyword component-id) :errors :too-big])
           size-error          (subscribe [:state-query size-error-path])]
       (if @size-error
         [:span.application__form-upload-button-error.animated.shake file-size-info-text]
         [:span.application__form-upload-button-info file-size-info-text]))]))

(defn- filename->label [{:keys [filename size]}]
  (str filename " (" (util/size-bytes->str size) ")"))

(defn attachment-view-file [field-descriptor component-id attachment-idx question-group-idx]
  (let [on-click (fn remove-attachment [event]
                   (.preventDefault event)
                   (dispatch [:application/remove-attachment field-descriptor component-id attachment-idx question-group-idx]))]
    [:div.application__form-filename-container
     [:span.application__form-attachment-text
      (filename->label @(subscribe [:state-query [:application :answers (keyword component-id) :values question-group-idx attachment-idx :value]]))
      (when-not @(subscribe [:application/cannot-edit?
                             (keyword (:id field-descriptor))])
        [:a.application__form-upload-remove-attachment-link
         {:href     "#"
          :on-click on-click}
         [:i.zmdi.zmdi-close]])]]))

(defn attachment-view-file-error [field-descriptor component-id attachment-idx question-group-idx]
  (let [attachment @(subscribe [:state-query [:application :answers (keyword component-id) :values question-group-idx attachment-idx]])
        languages  @(subscribe [:application/default-languages])
        on-click   (fn remove-attachment [event]
                     (.preventDefault event)
                     (dispatch [:application/remove-attachment-error field-descriptor component-id attachment-idx question-group-idx]))]
    (fn [field-descriptor component-id attachment-idx]
      [:div
       [:div.application__form-filename-container.application__form-file-error.animated.shake
        [:span.application__form-attachment-text
         (-> attachment :value :filename)
         [:a.application__form-upload-remove-attachment-link
          {:href     "#"
           :on-click on-click}
          [:i.zmdi.zmdi-close.zmdi-hc-inverse]]]]
       [:span.application__form-attachment-error (util/non-blank-val (:error attachment) languages)]])))

(defn attachment-deleting-file [component-id attachment-idx question-group-idx]
  [:div.application__form-filename-container
   [:span.application__form-attachment-text
    (filename->label @(subscribe [:state-query [:application :answers (keyword component-id) :values question-group-idx attachment-idx :value]]))]])

(defn attachment-uploading-file [component-id attachment-idx question-group-idx]
  [:div.application__form-filename-container
   [:span.application__form-attachment-text
    (filename->label @(subscribe [:state-query [:application :answers (keyword component-id) :values question-group-idx attachment-idx :value]]))]
   [:i.zmdi.zmdi-spinner.application__form-upload-uploading-spinner]])

(defn attachment-row [field-descriptor component-id attachment-idx question-group-idx]
  (let [status @(subscribe [:state-query [:application :answers (keyword component-id) :values question-group-idx attachment-idx :status]])]
    [:li.application__attachment-filename-list-item
     (case status
       :ready [attachment-view-file field-descriptor component-id attachment-idx question-group-idx]
       :error [attachment-view-file-error field-descriptor component-id attachment-idx question-group-idx]
       :uploading [attachment-uploading-file component-id attachment-idx question-group-idx]
       :deleting [attachment-deleting-file component-id attachment-idx question-group-idx])]))

(defn attachment [{:keys [id] :as field-descriptor} & {question-group-idx :idx}]
  (let [languages  (subscribe [:application/default-languages])
        text     (reaction (util/non-blank-val (get-in field-descriptor [:params :info-text :value]) @languages))]
    (fn [{:keys [id] :as field-descriptor} & {question-group-idx :idx}]
      (let [attachment-count (reaction (count @(subscribe [:state-query [:application :answers (keyword id) :values question-group-idx]])))]
        [:div.application__form-field
         [label field-descriptor]
         (when (belongs-to-hakukohde-or-ryhma? field-descriptor)
           [question-hakukohde-names field-descriptor :liitepyynto-for-hakukohde])
         (when-not (clojure.string/blank? @text)
           [markdown-paragraph @text (-> field-descriptor :params :info-text-collapse)])
         (when (> @attachment-count 0)
           [:ol.application__attachment-filename-list
            (->> (range @attachment-count)
                 (map (fn [attachment-idx]
                        ^{:key (str "attachment-" (when question-group-idx (str question-group-idx "-")) id "-" attachment-idx)}
                        [attachment-row field-descriptor id attachment-idx question-group-idx])))])
         (when-not @(subscribe [:application/cannot-edit? (keyword id)])
           [attachment-upload field-descriptor id @attachment-count question-group-idx])]))))

(defn info-element [field-descriptor]
  (let [languages  (subscribe [:application/default-languages])
        header   (util/non-blank-val (:label field-descriptor) @languages)
        text     (util/non-blank-val (:text field-descriptor) @languages)]
    [:div.application__form-info-element.application__form-field
     (when (not-empty header)
       [:label.application__form-field-label [:span header]])
     [markdown-paragraph text (-> field-descriptor :params :info-text-collapse)]]))

(defn- adjacent-field-input [{:keys [id] :as child} row-idx question-group-idx]
  (let [on-change (fn [evt]
                    (let [value (-> evt .-target .-value)]
                      (dispatch [:application/set-adjacent-field-answer child row-idx value question-group-idx])))
        answer    (subscribe [:state-query [:application :answers (keyword id)]])
        cannot-edit? (subscribe [:application/cannot-edit? (keyword (:id child))])]
    (fn [{:keys [id]} row-idx]
      (let [value        (get-in @answer (if question-group-idx
                                           [:values question-group-idx row-idx :value]
                                           [:values row-idx :value]))]
        [:input.application__form-text-input.application__form-text-input--normal
         (merge {:id        (str id "-" row-idx)
                 :type      "text"
                 :value     value
                 :on-change on-change}
                (when @cannot-edit? {:disabled true}))]))))

(defn adjacent-text-fields [field-descriptor]
  (let [languages  (subscribe [:application/default-languages])
        remove-on-click (fn remove-adjacent-text-field [event]
                          (let [row-idx (int (.getAttribute (.-currentTarget event) "data-row-idx"))]
                            (.preventDefault event)
                            (dispatch [:application/remove-adjacent-field field-descriptor row-idx])))
        cannot-edits?   (map #(subscribe [:application/cannot-edit? (keyword (:id %))])
                             (util/flatten-form-fields (:children field-descriptor)))]
    (fn [field-descriptor & {question-group-idx :idx}]
      (let [row-amount   (subscribe [:application/adjacent-field-row-amount field-descriptor question-group-idx])
            add-on-click (fn add-adjacent-text-field [event]
                           (.preventDefault event)
                           (dispatch [:application/add-adjacent-fields field-descriptor question-group-idx]))]
        [:div.application__form-field
         [label field-descriptor]
         (when (belongs-to-hakukohde-or-ryhma? field-descriptor)
           [question-hakukohde-names field-descriptor])
         (when-let [info (util/non-blank-val (some-> field-descriptor :params :info-text :label) @languages)]
           [:div.application__form-info-text [markdown-paragraph info (-> field-descriptor :params :info-text-collapse)]])
         [:div
          (->> (range @row-amount)
               (map (fn adjacent-text-fields-row [row-idx]
                      ^{:key (str "adjacent-fields-" row-idx)}
                      [:div.application__form-adjacent-text-fields-wrapper
                       (map-indexed (fn adjacent-text-fields-column [col-idx child]
                                      (let [key (str "adjacent-field-" row-idx "-" col-idx)]
                                        ^{:key key}
                                        [:div.application__form-adjacent-row
                                         [:div (when-not (= row-idx 0)
                                                 {:class "application__form-adjacent-row--mobile-only"})
                                          [label child]]
                                         [adjacent-field-input child row-idx question-group-idx]]))
                                    (:children field-descriptor))
                       (when (and (pos? row-idx) (not (some deref cannot-edits?)))
                         [:a {:data-row-idx row-idx
                              :on-click     remove-on-click}
                          [:span.application__form-adjacent-row--mobile-only (get-translation :remove-row)]
                          [:i.application__form-adjacent-row--desktop-only.i.zmdi.zmdi-close.zmdi-hc-lg]])]))
               doall)]
         (when (and (get-in field-descriptor [:params :repeatable])
                    (not (some deref cannot-edits?)))
           [:a.application__form-add-new-row
            {:on-click add-on-click}
            [:i.zmdi.zmdi-plus-square] (str " " (get-translation :add-row))])]))))

(defn render-field
  [field-descriptor & args]
  (let [ui       (subscribe [:state-query [:application :ui]])
        editing? (subscribe [:state-query [:application :editing?]])]
    (fn [field-descriptor & {:keys [idx] :as args}]
      (if (visible? ui field-descriptor)
        (let [disabled? (get-in @ui [(keyword (:id field-descriptor)) :disabled?] false)]
          (cond-> (match field-descriptor
                         {:fieldClass "wrapperElement"
                          :fieldType  "fieldset"
                          :children   children} [wrapper-field field-descriptor children]
                         {:fieldClass "questionGroup"
                          :fieldType  "fieldset"
                          :children   children} [question-group field-descriptor children]
                         {:fieldClass "wrapperElement"
                          :fieldType  "rowcontainer"
                          :children   children} [row-wrapper children]
                         {:fieldClass "formField" :fieldType "textField" :params {:repeatable true}} [repeatable-text-field field-descriptor]
                         {:fieldClass "formField" :fieldType "textField"} [text-field field-descriptor :disabled disabled? :editing editing?]
                         {:fieldClass "formField" :fieldType "textArea"} [text-area field-descriptor]
                         {:fieldClass "formField" :fieldType "dropdown"} [dropdown field-descriptor :editing editing?]
                         {:fieldClass "formField" :fieldType "multipleChoice"} [multiple-choice field-descriptor]
                         {:fieldClass "formField" :fieldType "singleChoice"} [single-choice-button field-descriptor]
                         {:fieldClass "formField" :fieldType "attachment"} [attachment field-descriptor]
                         {:fieldClass "formField" :fieldType "hakukohteet"} [hakukohde/hakukohteet field-descriptor]
                         {:fieldClass "pohjakoulutusristiriita" :fieldType "pohjakoulutusristiriita"} [pohjakoulutusristiriita/pohjakoulutusristiriita field-descriptor]
                         {:fieldClass "infoElement"} [info-element field-descriptor]
                         {:fieldClass "wrapperElement" :fieldType "adjacentfieldset"} [adjacent-text-fields field-descriptor])
            (or (:idx args)
                (empty? (:children field-descriptor)))
            (into (flatten (seq args)))))
        [:div]))))

(defn editable-fields [form-data]
  (r/create-class
    {:component-did-mount #(dispatch [:application/setup-window-unload])
     :reagent-render      (fn [form-data]
                            (into
                              [:div.application__editable-content.animated.fadeIn]
                              (for [content (:content form-data)]
                                [render-field content])))}))
