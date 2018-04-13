(ns ataru.virkailija.application.application-search-control-handlers
  (:require
   [ataru.cljs-util :as cljs-util]
   [ataru.dob :as dob]
   [ataru.email :as email]
   [ataru.ssn :as ssn]
   [ataru.virkailija.application.handlers :as handlers]
   [re-frame.core :refer [reg-event-fx reg-event-db]]))

(def show-path [:application :search-control :show])

(reg-event-fx
  :application/show-incomplete-haut-list
  (fn [{:keys [db]} _]
    {:db       (-> db
                   handlers/clear-selection
                   (assoc-in show-path :incomplete))
     :dispatch [:application/refresh-haut-and-hakukohteet]}))

(reg-event-fx
  :application/show-complete-haut-list
  (fn [{:keys [db]} _]
    {:db       (-> db
                   handlers/clear-selection
                   (assoc-in show-path :complete))
     :dispatch [:application/refresh-haut-and-hakukohteet]}))

(reg-event-db
  :application/close-search-control
  (fn [db]
    (assoc-in db show-path nil)))

(defn- set-search-term
  [db search-term]
  (assoc-in db [:application :search-control :search-term :value] search-term))

(defn- person-oid?
  [maybe-oid]
  (re-matches #"^1\.2\.246\.562\.24\.\d+$" maybe-oid))

(defn- application-oid?
  [maybe-oid]
  (re-matches #"^1\.2\.246\.562\.11\.\d+$" maybe-oid))

(reg-event-fx
  :application/navigate-to-search
  (fn [{:keys [db]} [_ search-term]]
    (when (= search-term (get-in db [:application :search-control :search-term :value]))
      {:navigate (str "/lomake-editori/applications/search?term=" search-term)})))

(reg-event-fx
  :application/search-term-changed
  (fn [{:keys [db]} [_ search-term]]
    {:db             (set-search-term db search-term)
     :dispatch-later [{:ms 500 :dispatch [:application/navigate-to-search search-term]}]}))

(reg-event-fx
  :application/clear-search-term
  (fn [_ _]
    {:navigate (str "/lomake-editori/applications/search")}))

(defn- show-search-term
  [db search-term filters]
  (-> db
      handlers/clear-selection
      (handlers/set-application-filters filters)
      (set-search-term search-term)
      (assoc-in show-path :search-term)))

(reg-event-fx
  :application/search-by-term
  (fn [{:keys [db]} [_ search-term application-key filters]]
    (let [search-term-ucase  (-> search-term
                                 clojure.string/trim
                                 clojure.string/upper-case)
          [term query-param] (cond (application-oid? search-term-ucase)
                                   [search-term-ucase "applicationOid"]

                                   (person-oid? search-term-ucase)
                                   [search-term-ucase "personOid"]

                                   (ssn/ssn? search-term-ucase)
                                   [search-term-ucase "ssn"]

                                   (dob/dob? search-term-ucase)
                                   [search-term-ucase "dob"]

                                   (email/email? search-term)
                                   [search-term "email"]

                                   (< 2 (count search-term))
                                   [search-term "name"])]
      (if (some? query-param)
        (handlers/fetch-applications-fx
         (show-search-term db search-term filters)
         (str "/lomake-editori/api/applications/list?" query-param "=" term)
         application-key)
        {:db (show-search-term db search-term filters)}))))
