(ns ataru.person-service.person-client
  (:require
   [taoensso.timbre :as log]
   [cheshire.core :as json]
   [ataru.config.core :refer [config]]
   [schema.core :as s]
   [clojure.core.match :refer [match]]
   [ataru.cas.client :as cas]
   [ataru.config.url-helper :refer [resolve-url]]
   [ataru.person-service.person-schema :refer [Person]]
   [ataru.person-service.oppijanumerorekisteri-person-extract :as orpe])
  (:import
   [java.net URLEncoder]))

(defn throw-error [msg]
  (throw (Exception. msg)))

(defn create-person [cas-client person]
  (let [result (cas/cas-authenticated-post
                 cas-client
                 (resolve-url :oppijanumerorekisteri-service.person-create) person)]
    (match result
      {:status 201 :body body}
      {:status :created :oid (:oidHenkilo (json/parse-string body true))}

      {:status 200 :body body}
      {:status :exists :oid (:oidHenkilo (json/parse-string body true))}

      {:status 400} ;;Request data was invalid, no reason to retry
      {:status :failed-permanently :message (:body result)}

      ;; Assume a temporary error and throw exception, the job will continue to retry
      :else (throw-error (str
                          "Could not create person, status: "
                          (:status result)
                          "response body: "
                          (:body result))))))

(defn get-persons [cas-client oids]
  (let [result (cas/cas-authenticated-post
                 cas-client
                 (resolve-url :oppijanumerorekisteri-service.get-persons) oids)]
    (match result
      {:status 200 :body body}
      (json/parse-string body true)

      :else (throw-error (str "Could not get persons by oids, status: "
                              (:status result)
                              "response body: "
                              (:body result))))))

(defn get-person [cas-client oid]
  (let [result (cas/cas-authenticated-get
                 cas-client
                 (resolve-url :oppijanumerorekisteri-service.get-person oid))]
    (match result
      {:status 200 :body body}
      (json/parse-string body true)

      :else (throw-error (str "Could not get person by oid " oid ", "
                              "status: " (:status result)
                              "response body: "
                              (:body result))))))

(defn- parse-duplicate-henkilos
  [data query-oid]
  (let [gs (seq (group-by :masterOid data))]
    (cond (empty? gs)
          {:master-oid  query-oid
           :linked-oids #{query-oid}}
          (empty? (rest gs))
          (let [[[master-oid links] & _] gs]
            {:master-oid  master-oid
             :linked-oids (->> links
                               (map :henkiloOid)
                               (cons master-oid)
                               set)})
          :else
          (throw (new RuntimeException
                      (str "Person oid " query-oid
                           " linked to multiple master oids"))))))

(defn linked-oids [cas-client oid]
  (let [result (cas/cas-authenticated-post
                cas-client
                (resolve-url :oppijanumerorekisteri-service.duplicate-henkilos)
                {:henkiloOids [oid]})]
    (match result
      {:status 200 :body body}
      (parse-duplicate-henkilos (json/parse-string body true) oid)
      :else (throw-error (str "Could not get linked oids for oid " oid ", "
                              "status: " (:status result) ", "
                              "response body: " (:body result))))))

(s/defschema Response
  {:status                   s/Keyword
   (s/optional-key :message) (s/maybe s/Str)
   (s/optional-key :oid)     (s/maybe s/Str)})

(s/defn ^:always-validate upsert-person :- Response
  [cas-client :- s/Any
   person     :- Person]
  (log/info "Sending person to oppijanumerorekisteri" person)
  (create-person cas-client person))

(defn create-or-find-person [oppijanumerorekisteri-cas-client application]
  (upsert-person oppijanumerorekisteri-cas-client (orpe/extract-person-from-application application)))
