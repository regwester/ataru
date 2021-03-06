(ns ataru.person-service.person-integration
  (:require
   [clojure.core.match :refer [match]]
   [taoensso.timbre :as log]
   [ataru.applications.application-store :as application-store]))

(defn upsert-and-log-person [person-service application-id]
  (let [application (application-store/get-application application-id)]
    (let [result (.create-or-find-person person-service application)]
      (match result
        {:status :created :oid oid}
        (do
          (log/info "Added person" oid "to person service (oppijanumerorekisteri)")
          (application-store/add-person-oid application-id oid)
          {:transition {:id :final}})

        {:status :exists :oid oid}
        (do
          (log/info "Person" oid "already existed in person service (oppijanumerorekisteri)")
          (application-store/add-person-oid application-id oid)
          {:transition {:id :final}})

        {:status :failed-permanently :message message}
        (do
          (log/error "Failed to send person" message)
          {:transition {:id :final}})

        :else (throw (Exception. (str "Unknown result: " result)))))))

(defn upsert-person
  "Fetch person OID from person service and store it to database"
  [{:keys [application-id]}
   {:keys [person-service]}]
  {:pre [(not (nil? application-id))
         (not (nil? person-service))]}
  (log/info "Trying to add applicant from application" application-id "to person service")
  (upsert-and-log-person person-service application-id))

(def job-type (str (ns-name *ns*)))

(def job-definition {:steps {:initial upsert-person}
                     :type  job-type})
