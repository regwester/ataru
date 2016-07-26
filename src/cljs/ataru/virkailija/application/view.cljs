(ns ataru.virkailija.application.view
  (:require [re-frame.core :refer [subscribe dispatch dispatch-sync]]
            [reagent.ratom :refer-macros [reaction]]
            [reagent.core :as r]
            [ataru.virkailija.temporal :as t]
            [ataru.virkailija.application.handlers]
            [ataru.application-common.application-readonly :as readonly-contents]
            [ataru.cljs-util :refer [wrap-scroll-to]]
            [taoensso.timbre :refer-macros [spy debug]]))

(defn toggle-form-list-open [open]
  (reset! open (not @open))
  nil) ;; Returns nil so that React doesn't whine about event handlers returning false

(defn applications []
  (let [applications (subscribe [:state-query [:application :applications]])
        selected-id (subscribe [:state-query [:application :selected-id]])]
    (into [:div.application-handling__list
           ]
          (for [application @applications
                :let        [id       (:id application)
                             time      (t/time->str (:modified-time application))
                             applicant (:applicant-name application)]]
            [:div.application-handling__list-row
             {:on-click #(dispatch [:application/select-application (:id application)])
              :class    (when (= @selected-id id)
                          "application-handling__list-row--selected")}
             [:span.application-handling__list-row--applicant
              (or applicant [:span.application-handling__list-row--applicant-unknown "Tuntematon"])]
             [:span.application-handling__list-row--time time]
             [:span.application-handling__list-row--state
              (case (:state application)
                "received" "Saapunut"
                "Tuntematon")]]))))

(defn application-list []
    [:div
     [:div.application-handling__list-header.application-handling__list-row
      [:span.application-handling__list-row--applicant "Hakija"]
      [:span.application-handling__list-row--time "Saapunut"]
      [:span.application-handling__list-row--state "Tila"]]
     [applications]])

(defn form-list-arrow-up [open]
  [:i.zmdi.zmdi-chevron-up.application-handling__form-list-arrow
   {:on-click #(toggle-form-list-open open)}])

(defn form-list-row [form selected? open]
  [:a.application-handling__form-list-row-link
    {:href  (str "#/applications/" (:id form))}
   (let [row-element [:div.application-handling__form-list-row
                      {:class (if selected? "application-handling__form-list-selected-row" "")
                       :on-click (if (not selected?)
                                   #(do
                                     (toggle-form-list-open open)
                                     (dispatch [:editor/select-form (:id form)]))
                                   #(toggle-form-list-open open))}
                      (:name form)]]
     (if selected? [wrap-scroll-to row-element] row-element))])

(defn form-list-opened [forms selected-form-id open]
  [:div.application-handling__form-list-open-wrapper ;; We need this wrapper to anchor up-arrow to be seen at all scroll-levels of the list
   [form-list-arrow-up open]
   (into [:div.application-handling__form-list-open]
        (for [[id form] forms
              :let [selected? (= id selected-form-id)]]
          ^{:key id}
          [form-list-row form selected? open]))])

(defn form-list-closed [selected-form open]
  [:div.application-handling__form-list-closed
   {:on-click #(toggle-form-list-open open)}
   [:div.application-handling__form-list-row.application-handling__form-list-selected-row (:name selected-form)]
   [:i.zmdi.zmdi-chevron-down.application-handling__form-list-arrow]])

(defn form-list []
  (let [forms            (subscribe [:state-query [:editor :forms]])
        selected-form-id (subscribe [:state-query [:editor :selected-form-id]])
        selected-form    (subscribe [:editor/selected-form])
        open             (r/atom false)]
    (fn []
      [:div.application-handling__form-list-wrapper
       (if @open
        [form-list-opened @forms @selected-form-id open]
        [form-list-closed @selected-form open])])))

(defn excel-download-link []
  (let [applications (subscribe [:state-query [:application :applications]])
        form-id (subscribe [:state-query [:editor :selected-form-id]])]
    (fn []
      (when (> (count @applications) 0)
        [:a.application-handling__excel-download-link
         {:href (str "/lomake-editori/api/applications/excel/" @form-id)}
         (str "Lataa hakemukset Excel-muodossa (" (count @applications) ")")]))))

(defn application-contents []
  (let [selected-form (subscribe [:editor/selected-form])
        selected-application (subscribe [:state-query [:application :selected-application]])]
    (fn []
      (when @selected-application [readonly-contents/readonly-fields @selected-form @selected-application]))))

(defn application []
  [:div
   [:div.application-handling__overview
    [:div.application-handling__container.panel-content
      [:div.application-handling__header
        [form-list]
        [excel-download-link]]
      [application-list]
      [application-contents]]]])
