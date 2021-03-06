(ns ataru.hakija.application-view
  (:require [clojure.string :refer [trim]]
            [ataru.hakija.banner :refer [banner]]
            [ataru.hakija.application-form-components :refer [editable-fields]]
            [ataru.hakija.hakija-readonly :as readonly-view]
            [ataru.cljs-util :as util :refer [get-translation]]
            [re-frame.core :refer [subscribe dispatch]]
            [cemerick.url :as url]
            [cljs.core.match :refer-macros [match]]
            [cljs-time.core :refer [to-default-time-zone now after?]]
            [cljs-time.format :refer [unparse unparse-local formatter]]
            [cljs-time.coerce :refer [from-long]]
            [goog.string :as gstring]
            [reagent.ratom :refer [reaction]]
            [reagent.core :as r]))

(def ^:private language-names
  {:fi "Suomeksi"
   :sv "På svenska"
   :en "In English"})

(def date-format (formatter "d.M.yyyy HH:mm" "Europe/Helsinki"))

(defn- millis->str
  [millis]
  (->> millis
    (from-long)
    (to-default-time-zone)
    (unparse-local date-format)))

(defn application-header [form]
  (let [selected-lang     (or (:selected-language form) :fi)
        languages         (filter
                            (partial not= selected-lang)
                            (:languages form))
        submit-status     (subscribe [:state-query [:application :submit-status]])
        application       (subscribe [:state-query [:application]])
        secret            (:modify (util/extract-query-params))
        virkailija-secret (subscribe [:state-query [:application :virkailija-secret]])]
    (fn [form]
      (let [hakutoiveet         (set (:hakukohde @application))
            applied-hakukohteet (filter #(contains? hakutoiveet (:oid %))
                                        (get-in form [:tarjonta :hakukohteet]))
            longest-open        (->> (get-in form [:tarjonta :hakukohteet])
                                     (map :hakuaika)
                                     (filter :on)
                                     (sort-by :end >)
                                     first)
            next-open           (->> (get-in form [:tarjonta :hakukohteet])
                                     (map :hakuaika)
                                     (remove :on)
                                     (filter #(after? (from-long (:start %)) (now)))
                                     (sort-by :start <)
                                     first)
            last-open           (->> (get-in form [:tarjonta :hakukohteet])
                                     (map :hakuaika)
                                     (sort-by :end >)
                                     first)
            apply-dates         (when-let [hakuaika (or longest-open
                                                        next-open
                                                        last-open)]
                                  (if (:jatkuva-haku? hakuaika)
                                    (get-translation :continuous-period)
                                    (str (get-translation :application-period)
                                         " "
                                         (millis->str (:start hakuaika))
                                         " - "
                                         (millis->str (:end hakuaika))
                                         (when (and (not (:on hakuaika))
                                                    (nil? @virkailija-secret))
                                           (str " (" (get-translation :not-within-application-period) ")")))))]
        [:div
         [:div.application__header-container
          [:span.application__header (or (-> form :tarjonta :haku-name selected-lang)
                                         (-> form :name selected-lang))]
          (when (and (not= :submitted @submit-status)
                     (> (count languages) 0)
                     (nil? secret))
            [:span.application__header-text
             (map-indexed (fn [idx lang]
                            (cond-> [:span {:key (name lang)}
                                     [:a {:href (-> (.. js/window -location -href)
                                                    (url/url)
                                                    (assoc-in [:query "lang"] (name lang))
                                                    str)}
                                      (get language-names lang)]]
                              (> (dec (count languages)) idx)
                              (conj [:span.application__header-language-link-separator " | "])))
                          languages)])]
         (when (not-empty apply-dates)
           [:div.application__sub-header-container
            [:span.application__sub-header-dates apply-dates]])
         (when (and (:cannot-edit-because-in-processing @application)
                    (not @virkailija-secret))
           [:div.application__sub-header-container
            [:span.application__sub-header-modifying-prevented
             (get-translation :application-processed-cant-modify)]])]))))

(defn readonly-fields [form]
  (let [application (subscribe [:state-query [:application]])]
    (fn [form]
      [readonly-view/readonly-fields form @application])))

(defn- render-fields [form]
  (let [submit-status    (subscribe [:state-query [:application :submit-status]])
        preview-enabled? (subscribe [:state-query [:application :preview-enabled]])]
    (fn [form]
      (if (or (= :submitted @submit-status)
              @preview-enabled?)
        [readonly-fields form]
        (do
          (dispatch [:application/run-rules])                ; wtf
          (when form
            [editable-fields form submit-status]))))))

(defn application-contents []
  (let [form            (subscribe [:state-query [:form]])
        can-apply?      (subscribe [:application/can-apply?])
        editing?        (subscribe [:state-query [:application :editing?]])
        expired         (subscribe [:state-query [:application :secret-expired?]])
        delivery-status (subscribe [:state-query [:application :secret-delivery-status]])]
    (fn []
      [:div.application__form-content-area
       (when @expired
         [:div.application__secret-expired
          [:div.application__secret-expired-icon
           [:i.zmdi.zmdi-lock-outline]]
          [:h2 (get-translation :expired-secret-heading)]
          [:p (get-translation :expired-secret-paragraph)]
          [:button.application__secret-resend-button
           {:disabled (some? @delivery-status)
            :on-click #(dispatch [:application/send-new-secret])}
           (if (= :completed @delivery-status)
             (get-translation :expired-secret-sent)
             (get-translation :expired-secret-button))]
          [:p (get-translation :expired-secret-contact)]])

       ^{:key (:id @form)}
       [application-header @form]

       (when (or @can-apply? @editing?)
         ^{:key "form-fields"}
         [render-fields @form])])))

(defn- star-number-from-event
  [event]
  (-> event
      (aget "target" "dataset" "starN")
      (js/parseInt 10)))

(defn feedback-form
  []
  (let [submit-status  (subscribe [:state-query [:application :submit-status]])
        star-hovered   (subscribe [:state-query [:application :feedback :star-hovered]])
        stars          (subscribe [:state-query [:application :feedback :stars]])
        hidden?        (subscribe [:state-query [:application :feedback :hidden?]])
        rating-status  (subscribe [:state-query [:application :feedback :status]])
        virkailija-secret (subscribe [:state-query [:application :virkailija-secret]])
        show-feedback? (reaction (and (= :submitted @submit-status)
                                      (not @hidden?)))]
    (fn []
      (let [rated?       (= :rating-given @rating-status)
            submitted?   (= :feedback-submitted @rating-status)]
        (when (and @show-feedback? (nil? @virkailija-secret))
          [:div.application-feedback-form
           [:a.application-feedback-form__close-button
            {:on-click #(dispatch [:application/rating-form-toggle])}
            [:i.zmdi.zmdi-close.close-details-button-mark]]
           [:div.application-feedback-form-container
            (when (not submitted?)
              [:h2.application-feedback-form__header (get-translation :feedback-header)])
            (when (not submitted?)
              [:div.application-feedback-form__rating-container.animated.zoomIn
               {:on-click      #(dispatch [:application/rating-submit (star-number-from-event %)])
                :on-mouse-out  #(dispatch [:application/rating-hover 0])
                :on-mouse-over #(dispatch [:application/rating-hover (star-number-from-event %)])}
               (let [stars-active (or @stars @star-hovered 0)]
                 (map (fn [n]
                        (let [star-classes (if (< n stars-active)
                                             :i.application-feedback-form__rating-star.application-feedback-form__rating-star--active.zmdi.zmdi-star
                                             :i.application-feedback-form__rating-star.application-feedback-form__rating-star--inactive.zmdi.zmdi-star-outline)]
                          [star-classes
                           {:key         (str "rating-star-" n)
                            :data-star-n (inc n)}])) (range 5)))])
            (when (not submitted?)
              [:div.application-feedback-form__rating-text
               (let [stars-selected (or @stars @star-hovered)]
                 (if (and (int? stars-selected)
                          (< 0 stars-selected 6))
                   (get (get-translation :feedback-ratings) stars-selected)
                   (gstring/unescapeEntities "&nbsp;")))])
            (when (not submitted?)
              [:div.application-feedback-form__text-feedback-container
               [:textarea.application__form-text-input.application__form-text-area.application__form-text-area__size-medium
                {:on-change   #(dispatch [:application/rating-update-feedback (.-value (.-target %))])
                 :placeholder (get-translation :feedback-text-placeholder)
                 :max-length  2000}]])
            (when (and (not submitted?)
                     rated?)
              [:a.application__send-feedback-button.application__send-feedback-button--enabled
               {:on-click (fn [evt]
                            (.preventDefault evt)
                            (dispatch [:application/rating-feedback-submit]))}
               (get-translation :feedback-send)])
            (when (and (not submitted?)
                       (not rated?))
              [:a.application__send-feedback-button.application__send-feedback-button--disabled
               (get-translation :feedback-send)])
            (when (not submitted?)
              [:div.application-feedback-form__disclaimer (get-translation :feedback-disclaimer)])
            (when submitted?
              [:div.application__thanks
               [:i.zmdi.zmdi-thumb-up.application__thanks-icon]
               [:span.application__thanks-text (get-translation :feedback-thanks)]])]])))))

(defn error-display []
  (let [error-message (subscribe [:state-query [:error :message]])
        detail (subscribe [:state-query [:error :detail]])]
    (fn [] (if @error-message
             [:div.application__error-display @error-message (str @detail)]
             nil))))

(defn form-view []
  [:div
   [banner]
   [error-display]
   [application-contents]
   [feedback-form]])
