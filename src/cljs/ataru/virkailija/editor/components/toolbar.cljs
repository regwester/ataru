(ns ataru.virkailija.editor.components.toolbar
  (:require
   [ataru.component-data.component :as component]
   [ataru.component-data.base-education-module :as base-education-module]
   [ataru.component-data.higher-education-base-education-module :as kk-base-education-module]
   [ataru.feature-config :as fc]
   [re-frame.core :refer [dispatch subscribe]]
   [taoensso.timbre :refer-macros [spy debug]]))

(def ^:private toolbar-elements
  [["Lomakeosio" component/form-section]
   ["Pudotusvalikko" component/dropdown]
   ["Painikkeet, yksi valittavissa" component/single-choice-button]
   ["Lista, monta valittavissa" component/multiple-choice]
   ["Tekstikenttä" component/text-field]
   ["Tekstialue" component/text-area]
   ["Vierekkäiset tekstikentät" component/adjacent-fieldset]
   ["Liitepyyntö" component/attachment]
   ["Kysymysryhmä" component/question-group]
   ["Infoteksti" component/info-element]
   ["Pohjakoulutusmoduuli" base-education-module/module]
   ["Pohjakoulutusmoduuli (kk-yhteishaku)" kk-base-education-module/module]
   ["Ilmoitus riittämättömästä pohjakoulutuksesta" component/pohjakoulutusristiriita]
   ["Koulutusmarkkinointilupa" component/koulutusmarkkinointilupa]
   ["Valintatuloksen julkaisulupa" component/valintatuloksen-julkaisulupa]
   ["Sähköisen asioinnin lupa" component/lupa-sahkoiseen-asiointiin]
   ["Asiointikieli" component/asiointikieli]])

(def followup-toolbar-element-names
  #{"Tekstikenttä"
    "Tekstialue"
    "Pudotusvalikko"
    "Painikkeet, yksi valittavissa"
    "Lista, monta valittavissa"
    "Infoteksti"
    "Liitepyyntö"
    "Vierekkäiset tekstikentät"
    "Kysymysryhmä"})

(def question-group-toolbar-element-names
  #{"Tekstikenttä"
    "Tekstialue"
    "Pudotusvalikko"
    "Painikkeet, yksi valittavissa"
    "Lista, monta valittavissa"
    "Infoteksti"
    "Liitepyyntö"
    "Vierekkäiset tekstikentät"})

(def ^:private followup-toolbar-elements
  (filter
    (fn [[el-name _]] (contains? followup-toolbar-element-names el-name))
    toolbar-elements))

(def ^:private question-group-toolbar-elements
  (filter
    (fn [[el-name _]] (contains? question-group-toolbar-element-names el-name))
    toolbar-elements))

(def ^:private adjacent-fieldset-toolbar-elements
  {"Tekstikenttä" (comp (fn [text-field] (assoc text-field :params {:adjacent true}))
                    component/text-field)})

(defn- component-toolbar [path elements generator]
  (fn [path elements generator]
    (let [base-education-module-exists?   (subscribe [:editor/base-education-module-exists?])
          pohjakoulutusristiriita-exists? (subscribe [:editor/pohjakoulutusristiriita-exists?])]
      (into [:ul.form__add-component-toolbar--list]
            (for [[component-name generate-fn] elements
                  :when (and (not (and (vector? path)
                                       (= :children (second path))
                                       (= "Lomakeosio" component-name)))
                             (not (and @base-education-module-exists?
                                       (contains? #{"Pohjakoulutusmoduuli" "Pohjakoulutusmoduuli (kk-yhteishaku)"} component-name)))
                             (not (and @pohjakoulutusristiriita-exists?
                                       (= "Ilmoitus riittämättömästä pohjakoulutuksesta" component-name))))]
              [:li.form__add-component-toolbar--list-item
               [:a {:on-click (fn [evt]
                                (.preventDefault evt)
                                (generator generate-fn))}
                component-name]])))))


(defn add-component [path]
  (let [form-locked (subscribe [:editor/current-form-locked])]
    [:div.editor-form__add-component-toolbar
     {:class (when @form-locked "disabled")}
     (when-not @form-locked
       [component-toolbar path toolbar-elements
        (fn [generate-fn]
          (dispatch [:generate-component generate-fn path]))])
     [:div.plus-component
      {:class (when @form-locked "disabled")}
      [:span "+"]]]))

(defn custom-add-component [toolbar path generator]
  (let [form-locked (subscribe [:editor/current-form-locked])]
    [:div.editor-form__add-component-toolbar
     {:class (when @form-locked "disabled")}
     (when-not @form-locked
       [component-toolbar path toolbar generator])
     [:div.plus-component
      {:class (when @form-locked "disabled")}
      [:span "+"]]]))

(defn followup-toolbar [option-path generator]
  [custom-add-component followup-toolbar-elements option-path generator])

(defn question-group-toolbar [option-path generator]
  [custom-add-component question-group-toolbar-elements option-path generator])

(defn adjacent-fieldset-toolbar [path generator]
  [custom-add-component adjacent-fieldset-toolbar-elements path generator])
