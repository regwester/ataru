(ns ataru.person-service.person-service
  (:require [taoensso.timbre :as log]
            [ataru.cas.client :as cas]
            [ataru.person-service.person-client :as person-client]
            [ataru.person-service.oppijanumerorekisteri-person-extract :as orpe]
            [com.stuartsierra.component :as component]
            [ataru.config.core :refer [config]]
            [ataru.cache.cache-service :as cache]
            [ataru.util :as util]))

(defprotocol PersonService
  (create-or-find-person [this person]
    "Create or find a person in Oppijanumerorekisteri.")

  (get-persons [this oids]
    "Find multiple persons from Oppijanumerorekisteri.")

  (get-person [this oid]
    "Find a person from ONR.")

  (linked-oids [this oids]))

(defrecord IntegratedPersonService [henkilo-cache
                                    oppijanumerorekisteri-cas-client]
  component/Lifecycle
  (start [this] this)
  (stop [this] this)

  PersonService

  (create-or-find-person [_ application]
    (person-client/create-or-find-person
     oppijanumerorekisteri-cas-client
     (orpe/extract-person-from-application application)))

  (get-persons [_ oids] (cache/get-many-from henkilo-cache oids))

  (get-person [_ oid] (cache/get-from henkilo-cache oid))

  (linked-oids [_ oids]
    (person-client/linked-oids oppijanumerorekisteri-cas-client oids)))

(def fake-person-from-creation {:personOid    "1.2.3.4.5.6"
                  :firstName    "Foo"
                  :lastName     "Bar"
                  :email        "foo.bar@mailinator.com"
                  :idpEntitys   []})

(def fake-onr-person {:oidHenkilo   "1.2.3.4.5.6"
                      :hetu         "020202A0202"
                      :etunimet     "Testi"
                      :kutsumanimi  "Testi"
                      :sukunimi     "Ihminen"
                      :syntymaaika  "1941-06-16"
                      :sukupuoli    "2"
                      :kansalaisuus [{:kansalaisuusKoodi "246"}]
                      :aidinkieli   {:id          "742310"
                                     :kieliKoodi  "fi"
                                     :kieliTyyppi "suomi"}
                      :turvakielto  false
                      :yksiloity    false
                      :yksiloityVTJ false})

(defrecord FakePersonService []
  component/Lifecycle
  PersonService

  (start [this] this)
  (stop [this] this)

  (create-or-find-person [this person] fake-person-from-creation)

  (get-persons [this oids]
    (reduce #(assoc %1 %2 (.get-person this %2))
            {}
            oids))

  (get-person [this oid]
    (condp = oid
      "2.2.2" (merge fake-onr-person
                     {:oidHenkilo "2.2.2"
                      :turvakielto true
                      :yksiloity   true
                      :etunimet    "Ari"
                      :kutsumanimi "Ari"
                      :sukunimi    "Vatanen"
                      :hetu         "141196-933S"})
      (merge fake-onr-person
             {:oidHenkilo oid})))

  (linked-oids [this oids]
    {}))

(defn new-person-service []
  (if (-> config :dev :fake-dependencies) ;; Ui automated test mode
    (->FakePersonService)
    (map->IntegratedPersonService {})))
