(ns ataru.hakija.application-email-confirmation
  "Application-specific email confirmation init logic"
  (:require
    [taoensso.timbre :as log]
    [selmer.parser :as selmer]
    [ataru.applications.application-store :as application-store]
    [ataru.background-job.job :as job]
    [ataru.hakija.background-jobs.hakija-jobs :as hakija-jobs]
    [ataru.hakija.background-jobs.email-job :as email-job]
    [ataru.translations.email-confirmation :as translations]
    [oph.soresu.common.config :refer [config]]))

(defn- get-translations [lang]
  (clojure.walk/prewalk (fn [x]
                          (cond-> x
                            (and (map? x)
                                 (contains? x lang))
                            (get lang)))
                        translations/email-confirmation-translations))

(defn- get-differing-translations [raw-translations translation-mappings]
  (into {} (map (fn [[key value]] [value (key raw-translations)]) translation-mappings)))

(defn create-email [application-id translation-mappings]
  (let [application      (application-store/get-application application-id)
        raw-translations (get-translations (keyword (:lang application)))
        translations     (merge
                           raw-translations
                           (get-differing-translations raw-translations translation-mappings))
        subject          (:subject translations)
        recipient        (-> (filter #(= "email" (:key %)) (:answers application)) first :value)
        service-url      (get-in config [:public-config :applicant :service_url])
        body             (selmer/render-file
                           "templates/email_confirmation_template.html"
                           (merge {:service-url service-url
                                   :secret      (:secret application)}
                             translations))]
    {:from       "no-reply@opintopolku.fi"
     :recipients [recipient]
     :subject    subject
     :body       body}))

(defn start-email-job [application-id translation-mappings]
  (let [email    (create-email
                  application-id
                  translation-mappings)
        job-type (:type email-job/job-definition)
        job-id   (job/start-job
                  hakija-jobs/job-definitions
                  job-type
                  email)]
    (log/info "Started application confirmation email job (to viestintäpalvelu) with job id" job-id ":")
    (log/info email)))

(defn start-email-submit-confirmation-job [application-id]
  (start-email-job application-id {:application-received-text    :application-action-text
                                   :application-received-subject :subject}))

(defn start-email-edit-confirmation-job [application-id]
  (start-email-job application-id {:application-edited-text    :application-action-text
                                   :application-edited-subject :subject}))
