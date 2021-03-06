(ns ataru.kayttooikeus-service.kayttooikeus-service
  (:require [ataru.cas.client :as cas]
            [ataru.config.url-helper :as url]
            [cheshire.core :as json]
            [com.stuartsierra.component :as component]))

(defprotocol KayttooikeusService
  (virkailija-by-username [this username]))

(defrecord HttpKayttooikeusService [cas-client]
  component/Lifecycle
  (start [this]
    (if (nil? cas-client)
      (assoc this :cas-client (cas/new-client "/kayttooikeus-service"))
      this))

  (stop [this]
    (assoc this :cas-client nil))

  KayttooikeusService
  (virkailija-by-username [_ username]
    (let [url                   (url/resolve-url :kayttooikeus-service.kayttooikeus.kayttaja
                                                 {"username" username})
          {:keys [status body]} (cas/cas-authenticated-get cas-client url)]
      (if (= 200 status)
        (if-let [virkailija (first (json/parse-string body true))]
          virkailija
          (throw (new RuntimeException
                      (str "No virkailija found by username " username))))
        (throw (new RuntimeException
                    (str "Could not get virkailija by username " username
                         ", status: " status
                         ", body: " body)))))))

(def fake-virkailija-value
  {"DEVELOPER"
   {:oidHenkilo    "1.2.246.562.11.11111111012"
    :organisaatiot [{:organisaatioOid "1.2.246.562.10.0439845"
                     :kayttooikeudet  [{:palvelu "ATARU_EDITORI"
                                        :oikeus  "CRUD"}
                                       {:palvelu "ATARU_HAKEMUS"
                                        :oikeus  "CRUD"}]}
                    {:organisaatioOid "1.2.246.562.28.1"
                     :kayttooikeudet  [{:palvelu "ATARU_EDITORI"
                                        :oikeus  "CRUD"}
                                       {:palvelu "ATARU_HAKEMUS"
                                        :oikeus  "CRUD"}]}]}
   "USER-WITH-HAKUKOHDE-ORGANIZATION"
   {:oidHenkilo    "1.2.246.562.11.11111111000"
    :organisaatiot [{:organisaatioOid "1.2.246.562.10.0439846"
                     :kayttooikeudet  [{:palvelu "ATARU_EDITORI"
                                        :oikeus  "CRUD"}
                                       {:palvelu "ATARU_HAKEMUS"
                                        :oikeus  "CRUD"}]}
                    {:organisaatioOid "1.2.246.562.28.2"
                     :kayttooikeudet  [{:palvelu "ATARU_EDITORI"
                                        :oikeus  "CRUD"}
                                       {:palvelu "ATARU_HAKEMUS"
                                        :oikeus  "CRUD"}]}]}})

(defrecord FakeKayttooikeusService []
  KayttooikeusService
  (virkailija-by-username [_ username]
    (get fake-virkailija-value username (get fake-virkailija-value "DEVELOPER"))))
