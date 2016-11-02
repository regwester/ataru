(ns ataru.applications.application-access-control
  (:require [ataru.forms.form-access-control :as form-access-control]
            [ataru.applications.application-store :as application-store]
            [ataru.middleware.user-feedback :refer [user-feedback-exception]]))

(defn check-form-access [form-key session organization-service]
  (when-not
    (form-access-control/form-allowed-by-key? form-key session organization-service)
    (throw (user-feedback-exception (str "Lomake " form-key " ei ole sallittu")))))

(defn check-application-access [application-key session organization-service]
  (when-not
    (form-access-control/organization-allowed?
      session
      organization-service
      #(application-store/get-application-organization-oid application-key))
    (throw (user-feedback-exception (str "Hakemus " application-key " ei ole sallittu")))))