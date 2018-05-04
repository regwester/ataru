(ns ataru.suoritus.suoritus-service
  (:require [ataru.cas.client :as cas-client]
            [ataru.config.url-helper :as url]
            [cheshire.core :as json]
            [clojure.core.match :refer [match]]
            [com.stuartsierra.component :as component]))

(def yo-komo "1.2.246.562.5.2013061010184237348007")

(defn- yo-suoritukset
  [cas-client person-oid]
  (match [(cas-client/cas-authenticated-get
           cas-client
           (url/resolve-url "suoritusrekisteri.suoritukset"
                            {"henkilo" person-oid
                             "komo"    yo-komo}))]
    [{:status 200 :body s}]
    (json/parse-string s true)
    [r]
    (throw (new RuntimeException (str "Could not get yo-suoritukset: " r)))))

(defprotocol SuoritusService
  (ylioppilas? [this person-oid]))

(defrecord HttpSuoritusService [cas-client]
  component/Lifecycle
  (start [this]
    (assoc this :cas-client (cas-client/new-client "/suoritusrekisteri")))
  (stop [this]
    (assoc this :cas-client nil))

  SuoritusService
  (ylioppilas? [this person-oid]
    (some #(= "VALMIS" (:tila %)) (yo-suoritukset cas-client person-oid))))

(defn new-suoritus-service [] (->HttpSuoritusService nil))
