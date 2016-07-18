(ns ataru.virkailija.component-data.person-info-module
  (:require [ataru.virkailija.component-data.component :as component]))

(defn ^:private first-name-component
  []
  (merge (component/text-field) {:label {:fi "Etunimet" :sv "Förnamn"} :required true}))

(defn ^:private referrer-name-component
  []
  (merge (component/text-field) {:label {:fi "Kutsumanimi" :sv "Smeknamn"} :required true :params {:size "S"}}))

(defn ^:private first-name-section
  []
  (component/row-section [(first-name-component)
                          (referrer-name-component)]))

(defn ^:private last-name-component
  []
  (merge (component/text-field) {:label {:fi "Sukunimi" :sv "Efternamn"} :required true}))

(defn ^:private dropdown-option
  [value labels]
  {:value value :label labels})

(defn ^:private nationality-component
  []
  (merge (component/dropdown) {:label {:fi "Kansalaisuus" :sv "Nationalitet"}
                               :required true
                               :options [(dropdown-option "fi" {:fi "Suomi" :sv "Finland"})
                                         (dropdown-option "sv" {:fi "Ruotsi" :sv "Sverige"})]}))

(defn ^:private ssn-component
  []
  (merge (component/text-field) {:label {:fi "Henkilötunnus" :sv "Personnummer"} :required true :params {:size "S"}}))

(defn ^:private identification-section
  []
  (component/row-section [(nationality-component)
                          (ssn-component)]))

(defn ^:private gender-section
  []
  (merge (component/dropdown) {:label {:fi "Sukupuoli" :sv "Kön"}
                               :required true
                               :options [(dropdown-option "male" {:fi "Mies" :sv "Människa"})
                                         (dropdown-option "female" {:fi "Nainen" :sv "Kvinna"})]}))

(defn ^:private email-component
  []
  (merge (component/text-field) {:label {:fi "Sähköpostiosoite" :sv "E-postadress"} :required true}))

(defn ^:private phone-component
  []
  (merge (component/text-field) {:label {:fi "Matkapuhelin" :sv "Mobiltelefonnummer"} :required true}))

(defn ^:private street-address-component
  []
  (merge (component/text-field) {:label {:fi "Katuosoite" :sv "Adress"} :required true :params {:size "L"}}))

(defn ^:private municipality-component
  []
  (merge (component/text-field) {:label {:fi "Kotikunta" :sv "Bostadsort"} :required true}))

(defn ^:private postal-code-component
  []
  (merge (component/text-field) {:label {:fi "Postinumero" :sv "Postnummer"} :required true :params {:size "S"}}))

(defn ^:private municipality-section
  []
  (component/row-section [(municipality-component)
                          (postal-code-component)]))

(defn ^:private native-language-section
  []
  (merge (component/dropdown) {:label {:fi "Äidinkieli" :sv "Modersmål"}
                               :required true
                               :options [(dropdown-option "fi" {:fi "suomi" :sv "finska"})
                                         (dropdown-option "sv" {:fi "ruotsi" :sv "svenska"})]}))

(defn person-info-module
  []
  (clojure.walk/prewalk
    (fn [x]
      (if (map? x)
        (dissoc x :focus?)
        x))
    (merge (component/form-section) {:label {:fi "Henkilötiedot"
                                             :sv "Personlig information"}
                                     :children [(first-name-section)
                                                (last-name-component)
                                                (identification-section)
                                                (gender-section)
                                                (email-component)
                                                (phone-component)
                                                (street-address-component)
                                                (municipality-section)
                                                (native-language-section)]
                                     :focus? false
                                     :module :person-info})))
