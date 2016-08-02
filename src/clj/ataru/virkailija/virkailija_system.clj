(ns ataru.virkailija.virkailija-system
  (:require [com.stuartsierra.component :as component]
            [ataru.cas.client :as cas]
            [ataru.codes-service.postal-code-client :as postal-code-client]
            [ataru.db.migrations :as migrations]
            [ataru.http.server :as server]
            [ataru.person-service.client :as person-service]
            [ataru.virkailija.virkailija-routes :as handler]))

(defn new-system
  ([http-port]
   (component/system-map
     :handler        (component/using
                       (handler/new-handler)
                       [:person-service
                        :postal-code-client])

     :server-setup   {:port http-port :repl-port 3333}

     :migration      (migrations/new-migration)

     :cas-client     (cas/new-client)

     :person-service (component/using
                       (person-service/new-client)
                       [:cas-client])

     :postal-code-client (postal-code-client/new-postal-code-client)

     :server         (component/using
                       (server/new-server)
                       [:server-setup :handler])))
  ([]
   (new-system 8350)))
