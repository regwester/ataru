(ns ataru.scripts.generate-schema-diagram
  (:require
    [ataru.db.migrations :as migrations]
    [ataru.schema.form-schema]
    [clojure.java.shell :refer [sh]]
    [environ.core :refer [env]]
    [ataru.config.core :refer [config]]))

(defn generate-db-schema-diagram
  []
  (let [db-config    (:db config)
        return-value (sh "./bin/generate-db-schema-diagram.sh"
                         (:server-name db-config)
                         (str (:port-number db-config))
                         (:database-name db-config)
                         "./target/db-schema"
                         (:ataru-version env)
                         (:username db-config))]
    (println return-value)
    (:exit return-value)))

(defn -main
  []
  (migrations/migrate)
  (generate-db-schema-diagram))
