(ns ataru.virkailija.editor.core
  (:require [ataru.feature-config :as fc]
            [ataru.virkailija.dev.lomake :as l]
            [ataru.virkailija.editor.component :as ec]
            [ataru.virkailija.editor.components.toolbar :as toolbar]
            [ataru.virkailija.editor.components.followup-question :as followup]
            [ataru.util :as util]
            [re-frame.core :refer [subscribe dispatch dispatch-sync reg-sub]]
            [reagent.ratom :refer-macros [reaction]]
            [reagent.core :as r]
            [cljs.core.match :refer-macros [match]]
            [cljs-uuid-utils.core :as uuid]
            [taoensso.timbre :refer-macros [spy debug error]]))

(reg-sub
  :editor/get-component-value
  (fn [db [_ & path]]
    (get-in db
      (flatten
        (concat
          [:editor :forms (-> db :editor :selected-form-key) :content]
          path)))))

(defn soresu->reagent [content path & args]
  (fn [content path & args]
    (let [children  (map-indexed
                     (fn [index child]
                       ^{:key index}
                       [soresu->reagent
                        child
                        (conj (vec path) :children index)
                        :question-group-element? (= (:fieldClass content)
                                                    "questionGroup")])
                     (:children content))
          followups (map-indexed
                     (fn [option-index option]
                       (map-indexed
                        (fn [followup-index followup]
                          ^{:key (str "followup-" option-index "-" followup-index)}
                          [soresu->reagent
                           followup
                           (vec (concat path [:options option-index :followups followup-index]))])
                        (:followups option)))
                     (:options content))]
      (when-let [component
                 (match content
                   {:module module}
                   [ec/module path]

                   {:fieldClass "wrapperElement"
                    :fieldType  "adjacentfieldset"}
                   [ec/adjacent-fieldset content path children]

                   {:fieldClass "wrapperElement"}
                   [ec/component-group content path children]

                   {:fieldClass "questionGroup"
                    :fieldType  "fieldset"}
                   [ec/component-group content path children]

                   {:fieldClass "formField" :fieldType "textField"
                    :params     {:adjacent true}}
                   [ec/adjacent-text-field content path]

                   {:fieldClass "formField" :fieldType "textField"}
                   [ec/text-field content path]

                   {:fieldClass "formField" :fieldType "textArea"}
                   [ec/text-area content path]

                   {:fieldClass "formField" :fieldType "dropdown"}
                   [ec/dropdown content followups path args]

                   {:fieldClass "formField" :fieldType "multipleChoice"}
                   [ec/dropdown content followups path args]

                   {:fieldClass "pohjakoulutusristiriita"
                    :fieldType  "pohjakoulutusristiriita"}
                   [ec/pohjakoulutusristiriita content path]

                   {:fieldClass "infoElement"}
                   [ec/info-element content path]

                   {:fieldClass "formField"
                    :fieldType  "singleChoice"}
                   [ec/dropdown content followups path args]

                   {:fieldClass "formField"
                    :fieldType  "attachment"}
                   [ec/attachment content path]

                   {:fieldClass "formField"
                    :fieldType  "hakukohteet"}
                   [ec/hakukohteet-module path]

                   :else (do
                           (error content)
                           (throw "error" content)))]
        [:div
         [ec/drag-n-drop-spacer path content]
         component]))))

(defn editor []
  (let [content (:content @(subscribe [:editor/selected-form]))]
    [:section.editor-form
     (doall
      (keep-indexed (fn [index element]
                      (when-not @(subscribe [:editor/belongs-to-other-organization? element])
                        ^{:key index}
                        [soresu->reagent element [index]]))
                    content))
     [ec/drag-n-drop-spacer [(count content)]]
     [toolbar/add-component (count content)]]))

