(ns ataru.background-job.job-store
  (:require
   [yesql.core :refer [defqueries]]
   [camel-snake-kebab.extras :refer [transform-keys]]
   [camel-snake-kebab.core :refer [->snake_case ->kebab-case-keyword]]
   [clj-time.core :as time]
   [clojure.java.jdbc :as jdbc]
   [oph.soresu.common.db :as db]))

(def in-memory-store (atom {}))
(def id-seq (atom 0))

(defn x_store-new [job-type state]
  (let [new-job-id (swap! id-seq inc)]
    (swap! in-memory-store assoc new-job-id {:id new-job-id :job-type job-type :state state :next-step :initial :status :running})))

(defn x_store [job]
  (swap! in-memory-store assoc (:id job) job))

(defn get-due-jobs []
  (filter #(= :running (:status %)) (vals @in-memory-store)))


(defqueries "sql/background-job-queries.sql")

(defn store-new [job-type state]
  (jdbc/with-db-transaction [data-source {:datasource (db/get-datasource :db)}]
    (let [connection {:connection data-source}
          new-job-id (:id (yesql-add-background-job<! {:job_type job-type} connection))]
      (yesql-add-job-iteration<! {:job_id new-job-id
                                  :step "initial"
                                  :state state
                                  :next_activation (time/now)
                                  :retry_count 0
                                  :executed false
                                  :error nil}
                                  connection)
      new-job-id)))

(defn job-iteration->db-format [job-iteration job-id]
  (assoc (transform-keys ->snake_case job-iteration)
         :step (name (:step job-iteration))
         :job_id job-id))

(defn store-job-result [connection job result-iterations]
  (yesql-update-previous-iteration! {:id (-> job :iteration :iteration-id)} connection)
  (let [result-iterations-db-format (map #(job-iteration->db-format % (:job-id job)) result-iterations)]
    (dorun
     (map #(yesql-add-job-iteration<! % connection) result-iterations-db-format))))

(defn- job->job-with-iteration [job]
  {:job-id (:job-id job)
   :job-type (:job-type job)
   :iteration {:state (:state job)
               :step (keyword (:step job))
               :iteration-id (:iteration-id job)
               :retry-count (:retry-count job)}})

(defn- raw-job->job [raw-job]
  (->> raw-job
       (transform-keys ->kebab-case-keyword)
       job->job-with-iteration))

(defn with-due-job
  "Execute due job in transaction"
  [exec-job-fn job-types]
  (jdbc/with-db-transaction [data-source {:datasource (db/get-datasource :db)}]
    (let [connection {:connection data-source}
          raw-job    (first (yesql-select-job-for-execution {:job_types job-types} connection))]
      (when raw-job
        (let [job               (raw-job->job raw-job)
              result-iterations (exec-job-fn job)]
          (store-job-result connection job result-iterations))))))
