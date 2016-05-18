(ns ataru.hakija.hakija-routes
  (:require [compojure.core :refer [routes context GET]]
            [ring.util.http-response :refer [ok]]
            [compojure.route :as route]))

(def hakija-routes
  (-> (routes
        (context "/hakemus" []
           (GET "/" [] {:status 200
                        :headers {"Content-Type" "text/html"}
                        :body "<h1>Hakija ui placeholder</h1>"}))
        (route/not-found "<h1>Page not found</h1>"))))
