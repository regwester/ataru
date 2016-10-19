(ns ataru.virkailija.editor.subs
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [re-frame.core :as re-frame]
            [taoensso.timbre :refer-macros [spy debug]]))

(re-frame/reg-sub-raw
  :editor/selected-form
  (fn [db _]
    (reaction
      (get-in @db [:editor :forms (get-in @db [:editor :selected-form-key])]))))

(re-frame/reg-sub-raw
  :editor/languages
  (fn [db]
    (reaction
      (let [lang-path [:editor :forms (get-in @db [:editor :selected-form-key]) :languages]
            languages (map keyword
                           (or (get-in @db lang-path) [:fi]))]
        languages))))
