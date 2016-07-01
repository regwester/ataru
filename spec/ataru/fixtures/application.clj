(ns ataru.fixtures.application
  (:require [clj-time.core :as c]))


(def form {
 :id 703,
 :name "Test fixture what is this",
 :modified-by "DEVELOPER",
 :modified-time (c/now)
 :content
 [{:id "G__31",
   :label {:fi "Osion nimi joo on", :sv "Avsnitt namn"},
   :children
   [{:id "G__19",
     :label {:fi "tekstiä", :sv ""},
     :required false,
     :fieldType "textField",
     :fieldClass "formField"}
    {:id "G__17",
     :label {:fi "ghj", :sv ""},
     :params {},
     :required false,
     :fieldType "textField",
     :fieldClass "formField"}
    {:id "G__17",
     :label {:fi "a", :sv ""},
     :params {},
     :required false,
     :fieldType "textField",
     :fieldClass "formField"}
    {:id "G__24",
     :label {:fi "gg", :sv ""},
     :params {},
     :required false,
     :fieldType "textField",
     :fieldClass "formField"}
    {:id "G__36",
     :label {:fi "c", :sv ""},
     :params {},
     :required false,
     :fieldType "textField",
     :fieldClass "formField"}],
   :fieldType "fieldset",
   :fieldClass "wrapperElement"}
  {:id "G__14",
   :label {:fi "aef", :sv ""},
   :params {},
   :required false,
   :fieldType "textField",
   :fieldClass "formField"}
  {:id "G__47",
   :label {:fi "freFD", :sv ""},
   :params {},
   :required false,
   :fieldType "textField",
   :fieldClass "formField"}]})

(def applications
  [{:key "c58df586-fdb9-4ee1-b4c4-030d4cfe9f81",
  :lang "fi",
  :modified-time (c/now)
  :form 703,
  :answers
  [{:key "G__19", :label "tekstiä", :value "1", :fieldType "textField"}
   {:key "G__17", :label "a", :value "2", :fieldType "textField"}
   {:key "G__24", :label "gg", :value "3", :fieldType "textField"}
   {:key "G__36", :label "c", :value "4", :fieldType "textField"}
   {:key "G__14", :label "aef", :value "5", :fieldType "textField"}
   {:key "G__47", :label "freFD", :value "6", :fieldType "textField"}]}
 {:key "956ae57b-8bd2-42c5-90ac-82bd0a4fd31f",
  :lang "fi",
  :modified-time (c/now)
  :form 703,
  :answers
  [{:key "G__19", :label "tekstiä", :value "Vastaus", :fieldType "textField"}
   {:key "G__17", :label "a", :value "lomakkeeseen", :fieldType "textField"}
   {:key "G__24", :label "gg", :value "asiallinen", :fieldType "textField"}
   {:key "G__36", :label "c", :value "vastaus", :fieldType "textField"}
   {:key "G__14", :label "aef", :value "joo", :fieldType "textField"}
   {:key "G__47", :label "freFD", :value "jee", :fieldType "textField"}]}
 {:key "9d24af7d-f672-4c0e-870f-3c6999f105e0",
  :lang "fi",
  :modified-time (c/now)
  :form 703,
  :answers
  [{:key "G__19", :label "tekstiä", :value "a", :fieldType "textField"}
   {:key "G__17", :label "a", :value "b", :fieldType "textField"}
   {:key "G__24", :label "gg", :value "d", :fieldType "textField"}
   {:key "G__36", :label "c", :value "e", :fieldType "textField"}
   {:key "G__14", :label "aef", :value "f", :fieldType "textField"}
   {:key "G__47", :label "freFD", :value "g", :fieldType "textField"}]}])
