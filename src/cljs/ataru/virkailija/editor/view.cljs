(ns ataru.virkailija.editor.view
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [re-frame.core :refer [subscribe dispatch dispatch-sync]]
            [reagent.core :as r]
            [ataru.cljs-util :refer [wrap-scroll-to]]
            [ataru.virkailija.editor.core :as c]
            [ataru.virkailija.editor.subs]
            [ataru.component-data.component :as component]
            [ataru.virkailija.temporal :refer [time->str]]
            [ataru.virkailija.routes :as routes]
            [taoensso.timbre :refer-macros [spy debug]]
            [ataru.virkailija.temporal :as temporal]))

(defn form-row [form selected? used-in-haku-count]
  [:a.editor-form__row
   {:class (when selected? "editor-form__selected-row")
    :on-click (partial routes/navigate-to-click-handler (str "/lomake-editori/editor/" (:key form)))}
   [:span.editor-form__list-form-name (some #(get-in form [:name %])
                                            [:fi :sv :en])]
   [:span.editor-form__list-form-time (time->str (:created-time form))]
   [:span.editor-form__list-form-editor (:created-by form)]
   (when (:locked form)
     [:i.zmdi.zmdi-lock.editor-form__list-form-locked])
   (when (< 0 used-in-haku-count)
     [:span.editor-form__list-form-used-in-haku-count used-in-haku-count])])

(defn form-list []
  (let [forms             (subscribe [:state-query [:editor :forms]])
        selected-form-key (subscribe [:state-query [:editor :selected-form-key]])
        forms-in-use      (subscribe [:state-query [:editor :forms-in-use]])]
    (fn []
      (into (if @selected-form-key
              [:div.editor-form__list]
              [:div.editor-form__list.editor-form__list_expanded])
            (for [[key form] @forms
                  :when (not (:deleted form))
                  :let [selected?          (= key @selected-form-key)
                        used-in-haku-count (count (keys (get @forms-in-use (keyword key))))]]
              ^{:key (str "form-list-item-" key)}
              (if selected?
                [wrap-scroll-to [form-row form selected? used-in-haku-count]]
                [form-row form selected? used-in-haku-count]))))))

(defn- add-form []
  [:button.editor-form__control-button.editor-form__control-button--enabled
   {:on-click (fn [evt]
                (.preventDefault evt)
                (dispatch [:editor/add-form]))}
   "Uusi lomake"])

(defn- copy-form []
  (let [form-key    (subscribe [:state-query [:editor :selected-form-key]])
        disabled?   (reaction (nil? @form-key))]
    (fn []
      [:button.editor-form__control-button
       {:on-click (fn [event]
                    (.preventDefault event)
                    (dispatch [:editor/copy-form]))
        :disabled @disabled?
        :class    (if @disabled?
                    "editor-form__control-button--disabled"
                    "editor-form__control-button--enabled")}
       "Kopioi lomake"])))

(defn- remove-form []
  (case @(subscribe [:editor/remove-form-button-state])
    :active
    [:button.editor-form__control-button--enabled.editor-form__control-button
     {:on-click #(dispatch [:editor/start-remove-form])}
     "Poista lomake"]
    :confirm
    [:button.editor-form__control-button--confirm.editor-form__control-button
     {:on-click #(dispatch [:editor/confirm-remove-form])}
     "Vahvista poisto"]
    :disabled
    [:button.editor-form__control-button--disabled.editor-form__control-button
     {:disabled true}
     "Poista lomake"]))

(defn- form-controls []
  [:div.editor-form__form-controls-container
   [add-form]
   [copy-form]
   [remove-form]])

(defn- form-header-row []
  [:div.editor-form__form-header-row
   [:h1.editor-form__form-heading "Lomakkeet"]
   [form-controls]])

(defn- editor-name-input [lang focus?]
  (let [form              (subscribe [:editor/selected-form])
        new-form-created? (subscribe [:state-query [:editor :new-form-created?]])
        form-locked       (subscribe [:editor/current-form-locked])]
    (r/create-class
     {:component-did-update (fn [this]
                              (when (and focus? @new-form-created?)
                                (do
                                  (.focus (r/dom-node this))
                                  (.select (r/dom-node this)))))
      :reagent-render (fn [lang focus?]
                        [:input.editor-form__form-name-input
                         {:type        "text"
                          :value       (get-in @form [:name lang])
                          :disabled    (some? @form-locked)
                          :placeholder "Lomakkeen nimi"
                          :on-change   #(do (dispatch [:editor/change-form-name lang (.-value (.-target %))])
                                            (dispatch [:set-state [:editor :new-form-created?] false]))
                          :on-blur     #(dispatch [:set-state [:editor :new-form-created?] false])}])})))

(defn- editor-name-wrapper [lang focus? lang-tag?]
  [:div.editor-form__form-name-input-wrapper
   [editor-name-input lang focus?]
   (when lang-tag?
     [:div.editor-form__form-name-input-lang
      (clojure.string/upper-case (name lang))])])

(defn- editor-name []
  (let [[l & ls] @(subscribe [:editor/languages])]
    [:div
     ^{:key (str "editor-name-" l)}
     [editor-name-wrapper l true (not-empty ls)]
     (doall (for [l ls]
              ^{:key (str "editor-name-" l)}
              [editor-name-wrapper l false true]))]))

(def ^:private lang-versions
  {:fi "Suomi"
   :sv "Ruotsi"
   :en "Englanti"})

(defn- lang-checkbox [lang-kwd checked?]
  (let [id (str "lang-checkbox-" (name lang-kwd))]
    [:div.editor-form__checkbox-with-label
     {:key id}
     [:input.editor-form__checkbox
      {:id      id
       :checked checked?
       :type    "checkbox"
       :on-change (fn [_]
                    (dispatch [:editor/toggle-language lang-kwd]))}]
     [:label.editor-form__checkbox-label.editor-form__language-checkbox-label
      {:for id}
      (get lang-versions lang-kwd)]]))

(defn- get-org-name [org]
  (str
   (get-in org [:name :fi])
   (if (= "group" (:type org))
     " (ryhmä)"
     "")))

(defn- get-org-name-for-oid [oid orgs] (get-org-name (first (filter #(= oid (:oid %)) orgs))))

(defn form-owner-organization [form]
  (let [organizations (subscribe [:state-query [:editor :user-info :organizations]])
        many-orgs     (fn [orgs] (> (count orgs) 1))
        opened?       (r/atom false)
        toggle-open   (fn [evt] (swap! opened? not))]
    (fn [form]
      (let [selected-org-name (get-org-name-for-oid (:organization-oid form) @organizations)]
        ;; If name is not available, selected org is a suborganization. Currently it's unsure
        ;; if we want to show the form's organization at all in that case. If we do, we'll have to pass
        ;; organization name from server with the form and fetch it from organization service
        ;; and probably start caching those
        (when (not-empty selected-org-name)
          [:div.editor-form__owner-control
           [:span.editor-form__owner-label "Omistaja: "]
           [:a
            {:on-click toggle-open
             :class (if (many-orgs @organizations) "" "editor-form__form-owner-selection-disabled-link")}
            selected-org-name]
           (when (and @opened? (many-orgs @organizations))
             [:div.editor-form__form-owner-selection-anchor
              [:div.editor-form__owner-selection-arrow-up]
              (into [:div.editor-form__form-owner-selection--opened
                     {:on-click toggle-open}]
                    (map (fn [org]
                           [:div.editor-form__owner-selection-row
                            {:on-click (fn [evt] (dispatch [:editor/change-form-organization (:oid org)]))}
                            (get-org-name org)])
                         @organizations))])])))))

(defn- fold-all []
  [:div
   [:span.editor-form__fold-clickable-text
    {:on-click #(dispatch [:editor/fold-all])}
    "sulje"]
   [:span.editor-form__fold-description-text " / "]
   [:span.editor-form__fold-clickable-text
    {:on-click #(dispatch [:editor/unfold-all])}
    "avaa"]
   [:span.editor-form__fold-description-text " osiot"]])

(defn- preview-link [form-key lang-kwd]
  [:a.editor-form__preview-button-link
   {:key    (str "preview-" (name lang-kwd))
    :href   (str "/lomake-editori/api/preview/form/" form-key
                 "?lang=" (name lang-kwd))
    :target "_blank"}
   [:i.zmdi.zmdi-open-in-new]
   [:span.editor-form__preview-button-text
    (clojure.string/upper-case (name lang-kwd))]])

(defn- lock-form-editing []
  (let [form-locked (subscribe [:editor/current-form-locked])]
    (fn []
      (let [locked? (some? (:locked @form-locked))]
        [:div.editor-form__preview-buttons
         (when locked?
           [:div.editor-form__form-editing-locked
            "Lomakkeen muokkaus on estetty "
            [:i.zmdi.zmdi-lock.editor-form__form-editing-lock-icon]
            [:div.editor-form__form-editing-locked-by
             (str "(" (:locked-by @form-locked) " " (-> @form-locked :locked temporal/time->short-str) ")")]])
         [:div#lock-form.editor-form__fold-clickable-text
          {:on-click #(dispatch [:editor/toggle-form-editing-lock])}
          (if locked?
            "Poista lukitus"
            "Lukitse lomake")]]))))

(defn- form-toolbar [form]
  (let [languages @(subscribe [:editor/languages])]
    [:div.editor-form__toolbar
     [:div.editor-form__toolbar-left
      [:div.editor-form__language-controls
       (map (fn [lang-kwd]
              (lang-checkbox lang-kwd (some? (some #{lang-kwd} languages))))
            (keys lang-versions))]
      [:div.editor-form__preview-buttons
       [:a.editor-form__email-template-editor-link
        {:on-click #(dispatch [:editor/toggle-email-template-editor])}
        "Muokkaa sähköpostipohjia"]]
      [lock-form-editing]]
     [:div.editor-form__toolbar-right
      [fold-all]]]))

(defn form-in-use-warning
  [form]
  (let [forms-in-use (subscribe [:state-query [:editor :forms-in-use]])
        languages    (subscribe [:editor/languages])]
    (fn [form]
      (if-let [form-used-in-hakus (get @forms-in-use (keyword (:key form)))]
        [:div.editor-form__form-link-container.animated.flash
         [:h3.editor-form__form-link-heading
          [:i.zmdi.zmdi-alert-circle-o]
          (if (empty? (rest (vals form-used-in-hakus)))
            " Tämä lomake on haun käytössä"
            " Tämä lomake on seuraavien hakujen käytössä")]
         [:ul.editor-form__used-in-haku-list
          (for [haku (vals form-used-in-hakus)]
            ^{:key (str "haku-" (:haku-oid haku))}
            [:li
             [:div.editor-form__used-in-haku-list-haku-name
              [:span
               (str (some #(get (:haku-name haku) %) [:fi :sv :en]) " ")
               [:a.editor-form__haku-admin-link
                {:href   (str "/tarjonta-app/index.html#/haku/"
                           (:haku-oid haku))
                 :target "_blank"}
                [:i.zmdi.zmdi-open-in-new]]]]
             [:div.editor-form__haku-preview-link
              [:a {:href   (str "/lomake-editori/api/preview/haku/"
                                (:haku-oid haku)
                                "?lang=fi")
                   :target "_blank"}
               "Testihakemus / Virkailijatäyttö"]
              [:span " | "]
              [:a {:href   (str js/config.applicant.service_url
                                "/hakemus/haku/" (:haku-oid haku)
                                "?lang=fi")
                   :target "_blank"}
               "Lomake"]]])]]
        [:div.editor-form__form-link-container
         [:h3.editor-form__form-link-heading
          [:i.zmdi.zmdi-alert-circle-o]
          " Linkki lomakkeeseen"]
         [:a.editor-form__form-preview-link
          {:href   (str js/config.applicant.service_url
                        "/hakemus/" (:key form)
                        "?lang=fi")
           :target "_blank"}
          "Lomake"]
         [:span " | "]
         [:a.editor-form__form-admin-preview-link "Testihakemus / Virkailijatäyttö:"]
         (map (partial preview-link (:key form)) @languages)]))))

(defn- close-form []
  [:a {:on-click (fn [event]
                   (dispatch [:set-state [:editor :selected-form-key] nil])
                   (routes/navigate-to-click-handler "/lomake-editori/editor"))}
   [:div.close-details-button
    [:i.zmdi.zmdi-close.close-details-button-mark]]])

(defn- editor-panel [form]
  [:div.editor-form__panel-container
   [close-form]
   [:div
    [:input#editor-form__copy-question-id-container
     {:value ""}]
    [editor-name]
    [form-in-use-warning form]]
   [c/editor]])

(defn editor []
  (let [form @(subscribe [:editor/selected-form])]
    [:div
     [:div.editor-form__container.panel-content
      [form-header-row]
      [form-list]]
     (when form
       ^{:key "editor-panel"}
       [editor-panel form])
     (when form
       ^{:key "form-toolbar"}
       [form-toolbar form])]))
