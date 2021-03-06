(ns ataru.feature-config
  #?(:clj
     (:require [ataru.config.core :refer [config]])))

(defn feature-enabled? [feature]
  (or #?(:clj  (get-in config [:public-config :features feature])
         :cljs (when (exists? js/config)
                 (-> js/config
                     js->clj
                     (get-in ["features" (name feature)]))))
      false))
