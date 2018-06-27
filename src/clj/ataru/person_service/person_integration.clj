(ns ataru.person-service.person-integration
  (:require
   [clj-time.format :as f]
   [clojure.core.match :refer [match]]
   [clojure.java.jdbc :as jdbc]
   [taoensso.timbre :as log]
   [ataru.applications.application-store :as application-store]
   [ataru.db.db :as db]
   [ataru.person-service.person-service :as person-service]
   [yesql.core :refer [defqueries]]))

(defqueries "sql/person-integration-queries.sql")

(defn upsert-and-log-person [person-service application-id]
  (let [application (application-store/get-application application-id)]
    (try
      (let [{:keys [status oid]} (person-service/create-or-find-person
                                  person-service
                                  application)]
        (match status
          :created
          (log/info "Added person" oid "to oppijanumerorekisteri")
          :exists
          (log/info "Person" oid "already existed in oppijanumerorekisteri"))
        (application-store/add-person-oid application-id oid)
        (log/info "Added person" oid "to application" application-id))
      (catch IllegalArgumentException e
        (log/error e "Failed to create-or-find person for application"
                   application-id)))
    {:transition {:id :final}}))

(defn upsert-person
  [{:keys [application-id]}
   {:keys [person-service]}]
  {:pre [(not (nil? application-id))
         (not (nil? person-service))]}
  (log/info "Trying to add applicant from application"
            application-id
            "to oppijanumerorekisteri")
  (upsert-and-log-person person-service application-id))

(defn- update-person-info-as-in-person
  [person-oid person]
  (pos? (db/exec :db yesql-update-person-info-as-in-person!
                 {:preferred_name (:kutsumanimi person)
                  :last_name      (:sukunimi person)
                  :ssn            (:hetu person)
                  :dob            (:syntymaaika person)
                  :person_oid     person-oid})))

(defn- update-person-info-as-in-application
  [person-oid]
  (pos? (db/exec :db yesql-update-person-info-as-in-application!
                 {:person_oid person-oid})))

(defn update-person-info-job-step
  [{:keys [person-oid]}
   {:keys [person-service]}]
  (let [person (person-service/get-person person-service person-oid)]
    (if (or (:yksiloity person)
            (:yksiloityVTJ person))
      (when (update-person-info-as-in-person person-oid person)
        (log/info "Updated person info of" person-oid
                  "to that on oppijanumerorekisteri"))
      (when (update-person-info-as-in-application person-oid)
        (log/info "Updated person info of" person-oid
                  "to that on application")))
    {:transition {:id :final}}))

(def job-type (str (ns-name *ns*)))

(def job-definition {:steps {:initial upsert-person}
                     :type  job-type})
