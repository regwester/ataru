(ns ataru.schema.soresu-schema
  (:require [schema.core :as s]))

(s/defschema LocalizedString {:fi                  s/Str
                              (s/optional-key :sv) s/Str
                              (s/optional-key :en) s/Str})

(s/defschema Option {:value                  s/Str
                     (s/optional-key :label) LocalizedString})

(s/defschema Button {:fieldClass              (s/eq "button")
                     :id                      s/Str
                     (s/optional-key :label)  LocalizedString
                     (s/optional-key :params) s/Any
                     :fieldType               s/Keyword})

(defn create-form-schema [custom-wrapper-element-types custom-form-element-types custom-info-element-types]

  (let [default-form-element-types ["textField"
                                    "textArea"
                                    "nameField"
                                    "emailField"
                                    "moneyField"
                                    "finnishBusinessIdField"
                                    "iban"
                                    "bic"
                                    "dropdown"
                                    "radioButton"
                                    "checkboxButton"
                                    "namedAttachment"
                                    "koodistoField"]
        form-element-types (into custom-form-element-types default-form-element-types)
        default-wrapper-element-types ["theme" "fieldset" "growingFieldset" "growingFieldsetChild" ]
        wrapper-element-types (into custom-wrapper-element-types default-wrapper-element-types)
        all-answer-element-types (into form-element-types wrapper-element-types)
        default-info-element-types ["h1"
                                    "h3"
                                    "link"
                                    "p"
                                    "bulletList"
                                    "dateRange"
                                    "endOfDateRange"]
        info-element-types (into custom-info-element-types default-info-element-types)]
    (s/defschema FormField {:fieldClass (s/eq "formField")
                            :id s/Str
                            :required s/Bool
                            (s/optional-key :label) LocalizedString
                            (s/optional-key :helpText) LocalizedString
                            (s/optional-key :initialValue) (s/cond-pre LocalizedString
                                                             s/Int)
                            (s/optional-key :params) s/Any
                            (s/optional-key :options) [Option]
                            :fieldType (apply s/enum form-element-types)})

    (s/defschema InfoElement {:fieldClass (s/eq "infoElement")
                              :id s/Str
                              :fieldType (apply s/enum info-element-types)
                              (s/optional-key :params) s/Any
                              (s/optional-key :label) LocalizedString
                              (s/optional-key :text) LocalizedString})

    (s/defschema BasicElement (s/conditional
                                #(= "formField" (:fieldClass %)) FormField
                                #(= "button" (:fieldClass %)) Button
                                :else InfoElement))

    (s/defschema WrapperElement {:fieldClass              (s/eq "wrapperElement")
                                 :id                      s/Str
                                 :fieldType               (apply s/enum wrapper-element-types )
                                 :children                [(s/conditional #(= "wrapperElement" (:fieldClass %))
                                                             (s/recursive #'WrapperElement)
                                                             :else
                                                             BasicElement)]
                                 (s/optional-key :params) s/Any
                                 (s/optional-key :label)  LocalizedString
                                 (s/optional-key :helpText) LocalizedString})))
