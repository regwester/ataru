(ns ataru.applications.automatic-eligibility
  (:require [ataru.background-job.job :as job]
            [ataru.db.db :as db]
            [ataru.log.audit-log :as audit-log]
            [ataru.ohjausparametrit.ohjausparametrit-protocol :as ohjausparametrit-service]
            [ataru.suoritus.suoritus-service :as suoritus-service]
            [ataru.tarjonta-service.tarjonta-protocol :as tarjonta-service]
            [clj-time.coerce :as coerce]
            [clj-time.core :as time]
            [clojure.java.jdbc :as jdbc]
            [taoensso.timbre :as log]))

(defn- get-application
  [application-id]
  (jdbc/with-db-connection [connection {:datasource (db/get-datasource :db)}]
    (let [application (-> (jdbc/query connection
                                      ["SELECT la.key AS key,
                                               la.person_oid AS person_oid,
                                               la.haku AS haku,
                                               la.hakukohde AS hakukohde
                                        FROM latest_applications AS la
                                        JOIN applications AS a ON a.key = la.key
                                        WHERE a.id = ?"
                                       application-id])
                          first
                          (clojure.set/rename-keys {:person_oid :person-oid}))]
      (when (nil? application)
        (throw (new RuntimeException (str "Application " application-id
                                          " not found"))))
      (when (nil? (:person-oid application))
        (throw (new RuntimeException (str "Application " application-id
                                          " is not linked to a person"))))
      application)))

(defn- get-haku
  [tarjonta-service haku-oid]
  (if-let [haku (tarjonta-service/get-haku tarjonta-service haku-oid)]
    haku
    (throw (new RuntimeException (str "Haku " haku-oid " not found")))))

(defn- automatic-eligibility-in-use?
  [haku ohjausparametrit now]
  (and (some? haku)
       (:ylioppilastutkintoAntaaHakukelpoisuuden haku)
       (if-let [ph-ahp (get-in ohjausparametrit [:PH_AHP :date])]
         (time/before? now (coerce/from-long ph-ahp))
         (throw (new RuntimeException (str "Ohjausparametri PH_AHP not set"
                                           " for haku " (:oid haku)))))))

(defn- get-hakukohteet
  [tarjonta-service hakukohde-oids]
  (let [hakukohteet (tarjonta-service/get-hakukohteet tarjonta-service
                                                      hakukohde-oids)]
    (when-let [missing-oids (seq (clojure.set/difference
                                  (set hakukohde-oids)
                                  (set (map :oid hakukohteet))))]
      (throw (new RuntimeException
                  (str "Hakukohteet " (clojure.string/join ", " missing-oids)
                       " not found"))))
    hakukohteet))

(defn- automatically-eligible-if-ylioppilas?
  [hakukohde]
  (:ylioppilastutkintoAntaaHakukelpoisuuden hakukohde))

(defn- upsert-eligible-if-unreviewed
  [connection application-key hakukohde-oid]
  (->> (jdbc/execute! connection
                      ["INSERT INTO application_hakukohde_reviews
                        (application_key, requirement, state, hakukohde)
                        VALUES (?, 'eligibility-state', 'eligible', ?)
                        ON CONFLICT (application_key, hakukohde, requirement)
                        DO UPDATE
                        SET state = EXCLUDED.state,
                            modified_time = DEFAULT
                        WHERE application_hakukohde_reviews.state = 'unreviewed'"
                       application-key
                       hakukohde-oid])
       first
       (= 1)))

(defn- update-unreviewed-if-eligibility-automatically-set
  [connection application-key hakukohde-oid]
  (when (->> (jdbc/query connection ["SELECT event_type = 'eligibility-state-automatically-changed' AS result
                                      FROM application_events
                                      WHERE id = (SELECT max(id)
                                                  FROM application_events
                                                  WHERE application_key = ?
                                                    AND hakukohde = ?
                                                    AND review_key = 'eligibility-state')"
                                     application-key
                                     hakukohde-oid])
             first
             :result)
    (->> (jdbc/execute! connection
                        ["UPDATE application_hakukohde_reviews
                          SET state = 'unreviewed',
                              modified_time = DEFAULT
                          WHERE application_key = ?
                            AND hakukohde = ?
                            AND requirement = 'eligibility-state'
                            AND state = 'eligible'"
                         application-key
                         hakukohde-oid])
         first
         (= 1))))

(defn- insert-application-event
  [connection application-key hakukohde-oid new-state]
  (jdbc/execute! connection ["INSERT INTO application_events
                              (new_review_state,
                               time,
                               event_type,
                               application_key,
                               hakukohde,
                               review_key)
                              VALUES
                              (?,
                               DEFAULT,
                               'eligibility-state-automatically-changed',
                               ?,
                               ?,
                               'eligibility-state')"
                             new-state
                             application-key
                             hakukohde-oid]))

(defn- audit-log
  [application-key hakukohde-oid new-state old-state]
  (audit-log/log {:new       {:application_key application-key
                              :requirement     "eligibility-state"
                              :state           new-state
                              :hakukohde       hakukohde-oid}
                  :old       {:application_key application-key
                              :requirement     "eligibility-state"
                              :state           old-state
                              :hakukohde       hakukohde-oid}
                  :id        "automatic-eligibility-check"
                  :operation audit-log/operation-modify}))

(defn set-eligible-if-unreviewed
  [application-key hakukohde-oid]
  (jdbc/with-db-transaction [connection {:datasource (db/get-datasource :db)}]
    (when (upsert-eligible-if-unreviewed connection
                                         application-key
                                         hakukohde-oid)
      (insert-application-event connection
                                application-key
                                hakukohde-oid
                                "eligible")
      (audit-log application-key hakukohde-oid "eligible" "unreviewed"))))

(defn set-unreviewed-if-automatically-eligible
  [application-key hakukohde-oid]
  (jdbc/with-db-transaction [connection {:datasource (db/get-datasource :db)}
                             {:isolation :serializable}]
    (when (update-unreviewed-if-eligibility-automatically-set connection
                                                              application-key
                                                              hakukohde-oid)
      (insert-application-event connection
                                application-key
                                hakukohde-oid
                                "unreviewed")
      (audit-log application-key hakukohde-oid "unreviewed" "eligible"))))

(defn automatic-eligibility-if-ylioppilas
  [haku ohjausparametrit now hakukohteet ylioppilas?]
  (when (automatic-eligibility-in-use? haku ohjausparametrit now)
    {:action         (if ylioppilas?
                       set-eligible-if-unreviewed
                       set-unreviewed-if-automatically-eligible)
     :hakukohde-oids (->> hakukohteet
                          (filter automatically-eligible-if-ylioppilas?)
                          (map :oid))}))

(defn start-automatic-eligibility-check-job
  ([job-definitions application-id]
   (start-automatic-eligibility-check-job job-definitions
                                          application-id
                                          (time/now)))
  ([job-definitions application-id next-activation]
   (job/start-job job-definitions
                  "automatic-eligibility-check"
                  {:application-id application-id}
                  next-activation)))

(defn- schedule-next-check
  [job-definitions application-id]
  (start-automatic-eligibility-check-job job-definitions
                                         application-id
                                         (time/plus (time/now)
                                                    (time/days 1))))

(defn automatic-eligibility-check-job-step
  [{:keys [application-id]}
   {:keys [job-definitions
           ohjausparametrit-service
           tarjonta-service
           suoritus-service]}]
  (let [application             (get-application application-id)
        [haku ohjausparametrit] (when-let [haku-oid (:haku application)]
                                  [(get-haku tarjonta-service haku-oid)
                                   (ohjausparametrit-service/get-parametri
                                    ohjausparametrit-service
                                    haku-oid)])
        hakukohteet             (get-hakukohteet
                                 tarjonta-service
                                 (:hakukohde application))
        ylioppilas?             (suoritus-service/ylioppilas?
                                 suoritus-service
                                 (:person-oid application))]
    (when-let [{:keys [action hakukohde-oids]}
               (automatic-eligibility-if-ylioppilas haku
                                                    ohjausparametrit
                                                    (time/now)
                                                    hakukohteet
                                                    ylioppilas?)]
      (doseq [hakukohde-oid hakukohde-oids]
        (action (:key application) hakukohde-oid))
      (schedule-next-check job-definitions application-id)))
  {:transition {:id :final}})
