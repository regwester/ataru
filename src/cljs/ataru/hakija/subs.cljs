(ns ataru.hakija.subs
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [re-frame.core :as re-frame]
            [ataru.hakija.application :refer [answers->valid-status
                                              wrapper-sections-with-validity
                                              applying-possible?]]))

(re-frame/reg-sub
  :state-query
  (fn [db [_ path]]
    (get-in db path)))

(re-frame/reg-sub
  :application/valid-status
  (fn [db]
    (answers->valid-status (-> db :application :answers)
                           (-> db :application :ui)
                           (-> db :form :content))))

(re-frame/reg-sub
  :application/wrapper-sections
  (fn [db]
    (wrapper-sections-with-validity
      (:wrapper-sections db)
      (-> db :application :answers))))

(re-frame/reg-sub
 :application/can-apply?
 (fn [db]
   (applying-possible? (:form db) (:application db))))

(re-frame/reg-sub
  :application/hakukohde-count
  (fn [db]
    (count (-> db :tarjonta :hakukohteet))))

(re-frame/reg-sub
  :application/form-language
  (fn [db]
    (or
      (get-in db [:form :selected-language])
      :fi))) ; When user lands on the page, there isn't any language set until the form is loaded)

(re-frame/reg-sub
  :application/default-language
  (fn [db]
    (-> db
        (get-in [:form :languages])
        first)))

(re-frame/reg-sub
  :application/get-i18n-text
  (fn [db [_ translations]]
    (get translations @(re-frame/subscribe [:application/form-language]))))

(re-frame/reg-sub
  :application/adjacent-field-row-amount
  (fn [db [_ field-descriptor]]
    (let [child-id   (-> (:children field-descriptor) first :id keyword)
          row-amount (-> (get-in db [:application :answers child-id :values] [])
                         count)]
      (if (= row-amount 0)
        1
        row-amount))))

(re-frame/reg-sub
  :application/multiple-choice-option-checked?
  (fn [db [_ parent-id option-value]]
    (let [options (get-in db [:application :answers parent-id :options])]
      (true? (get options option-value)))))

(re-frame/reg-sub
  :application/single-choice-option-checked?
  (fn [db [_ parent-id option-value]]
    (let [value (get-in db [:application :answers parent-id :value])]
      (= option-value value))))

(defn- hakukohteet-field [db]
  (->> (get-in db [:form :content] [])
       (filter #(= "hakukohteet" (:id %)))
       first))

(re-frame/reg-sub
  :application/hakukohde-options
  (fn [db _]
    (:options (hakukohteet-field db))))

(re-frame/reg-sub
  :application/hakukohde-options-by-oid
  (fn [db _]
    (into {} (map (juxt :value identity)
                  @(re-frame/subscribe [:application/hakukohde-options])))))

(re-frame/reg-sub
  :application/selected-hakukohteet
  (fn [db _]
    (map :value (get-in db [:application :answers :hakukohteet :values] []))))

(re-frame/reg-sub
  :application/hakukohteet-editable?
  (fn [db _] (< 1 (count @(re-frame/subscribe [:application/hakukohde-options])))))

(re-frame/reg-sub
  :application/hakukohde-query
  (fn [db _] (get-in db [:application :hakukohde-query])))

(re-frame/reg-sub
  :application/hakukohde-hits
  (fn [db _]
    (get-in db [:application :hakukohde-hits])))

(re-frame/reg-sub
  :application/hakukohde-selected?
  (fn [db [_ hakukohde-oid]]
    (some #(= % hakukohde-oid)
          @(re-frame/subscribe [:application/selected-hakukohteet]))))

(re-frame/reg-sub
  :application/max-hakukohteet
  (fn [db _]
    (get-in (hakukohteet-field db)
            [:params :max-hakukohteet]
            nil)))

(re-frame/reg-sub
  :application/hakukohteet-full?
  (fn [_ _]
    (if-let [max-hakukohteet @(re-frame/subscribe [:application/max-hakukohteet])]
      (<= max-hakukohteet
          (count @(re-frame/subscribe [:application/selected-hakukohteet])))
      false)))

(re-frame/reg-sub
  :application/hakukohde-label
  (fn [db [_ hakukohde-oid]]
    @(re-frame/subscribe [:application/get-i18n-text
                 (get-in @(re-frame/subscribe [:application/hakukohde-options-by-oid])
                         [hakukohde-oid :label])])))

(re-frame/reg-sub
  :application/hakukohde-description
  (fn [db [_ hakukohde-oid]]
    @(re-frame/subscribe [:application/get-i18n-text
                 (get-in @(re-frame/subscribe [:application/hakukohde-options-by-oid])
                         [hakukohde-oid :description])])))

(re-frame/reg-sub
  :application/hakukohteet-header
  (fn [db _]
    (let [label-translations (:label (hakukohteet-field db))
          selected-hakukohteet @(re-frame/subscribe [:application/selected-hakukohteet])
          max-hakukohteet @(re-frame/subscribe [:application/max-hakukohteet])
          counter (str "(" (count selected-hakukohteet) "/" max-hakukohteet ")")]
      @(re-frame/subscribe [:application/get-i18n-text
                            (if max-hakukohteet
                              (into {} (for [[k label] label-translations]
                                         [k (str label " " counter)]))
                              label-translations)]))))

(re-frame/reg-sub
  :application/max-hakukohteet-reached-label
  (fn [db _]
    (let [max-hakukohteet @(re-frame/subscribe [:application/max-hakukohteet])]
      @(re-frame/subscribe [:application/get-i18n-text
                   {:fi (str "Lisätty " max-hakukohteet "/" max-hakukohteet)
                    :sv ""
                    :en ""}]))))

(re-frame/reg-sub
  :application/show-hakukohde-search
  (fn [db _]
    (get-in db [:application :show-hakukohde-search])))
