(ns ataru.person-service.authentication-service-person-extract)

(defn- extract-field [{:keys [answers]} field]
  (some (fn [{:keys [key value]}]
          (when (= key field)
            value))
        answers))

(def finnish-date-regex #"(\d{2})\.(\d{2})\.(\d{4})")

(defn- convert-birth-date [finnish-format-date]
  {:post [(not= % "--")]} ;; When no match for finnish date, this would result in "--"
  (let [[_ day month year] (re-find finnish-date-regex finnish-format-date)]
    (str year "-" month "-" day)))

(defn- extract-birth-date [application]
  (let [finnish-format-date (extract-field application "birth-date")]
    (if-not finnish-format-date (throw (Exception. "Expected a birth-date in application")))
    (convert-birth-date finnish-format-date)))

(defn extract-person-from-application [application]
  (let [email        (extract-field application "email")
        basic-fields {:email          email
                      :firstName      (extract-field application "first-name")
                      :lastName       (extract-field application "last-name")
                      :gender         (extract-field application "gender")
                      :nativeLanguage (extract-field application "language")
                      :nationality    (extract-field application "nationality")
                      :idpEntitys     [{:idpEntityId "oppijaToken" :identifier email}]}
        person-id    (extract-field application "ssn")]
    (if person-id
      (assoc basic-fields :personId (clojure.string/upper-case person-id))
      (assoc basic-fields :birthDate (extract-birth-date application)))))