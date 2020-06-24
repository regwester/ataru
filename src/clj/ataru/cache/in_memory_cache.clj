(ns ataru.cache.in-memory-cache
  (:require [com.stuartsierra.component :as component]
            [ataru.cache.cache-service :as cache])
  (:import java.util.concurrent.TimeUnit
           [com.github.benmanes.caffeine.cache
            CacheLoader
            Caffeine
            LoadingCache]))

(defrecord InMemoryCache [loader
                          name
                          size
                          expires-after
                          refresh-after
                          ^LoadingCache caffeine]
  component/Lifecycle
  (start [this]
    (if (nil? caffeine)
      (assoc this :caffeine
             (cond-> (Caffeine/newBuilder)
                     (some? size)
                     (.maximumSize size)
                     (some? expires-after)
                     (.expireAfterWrite (first expires-after) (second expires-after))
                     (some? refresh-after)
                     (.refreshAfterWrite (first refresh-after) (second refresh-after))
                     true
                     (.build (reify CacheLoader
                               (load [this key] (cache/load loader key))
                               (loadAll [this keys] (cache/load-many loader keys))))))
      this))
  (stop [this]
    (assoc this :caffeine nil))

  cache/Stats
  (stats [_]
    (let [caffeine-stats (.stats caffeine)]
      {:average-load-penalty (.toMillis TimeUnit/NANOSECONDS
                                        (.averageLoadPenalty caffeine-stats))
       :hit-rate             (.hitRate caffeine-stats)}))

  cache/Cache
  (get-from [this key]
    (.get caffeine key))
  (get-many-from [this keys]
    (.getAll caffeine keys))
  (remove-from [this key]
    (.invalidate caffeine key))
  (clear-all [this]
    (.invalidateAll caffeine)))
