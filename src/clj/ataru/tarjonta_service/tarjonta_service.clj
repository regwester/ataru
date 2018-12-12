(ns ataru.tarjonta-service.tarjonta-service
  (:require
    [ataru.tarjonta-service.tarjonta-client :as client]
    [ataru.organization-service.session-organizations :refer [select-organizations-for-rights]]
    [ataru.organization-service.organization-service :as organization-protocol]
    [ataru.organization-service.organization-client :refer [oph-organization]]
    [com.stuartsierra.component :as component]
    [ataru.config.core :refer [config]]
    [ataru.cache.cache-service :as cache]
    [ataru.tarjonta-service.tarjonta-protocol :refer [TarjontaService VirkailijaTarjontaService get-hakukohde]]
    [ataru.tarjonta-service.mock-tarjonta-service :refer [->MockTarjontaService ->MockVirkailijaTarjontaService]]))

(defn- parse-multi-lang-text
  [text]
  (reduce-kv (fn [m lang s]
               (if (or (nil? s) (clojure.string/blank? s))
                 m
                 (assoc m lang s)))
    {}
    (clojure.set/rename-keys text {:kieli_fi :fi
                                   :kieli_sv :sv
                                   :kieli_en :en})))

(defn- haku-name-and-oid [haku-names-and-oids haku]
  (assoc haku-names-and-oids
         (:oid haku)
         {:haku-oid  (:oid haku)
          :haku-name (parse-multi-lang-text (:nimi haku))}))

(defn- hakus-by-form-key [hakus {:keys [avain haut]}]
  (update hakus avain merge (reduce haku-name-and-oid {} haut)))

(defn- forms-in-use
  [forms-in-use-cache organization-service session]
  (->> (select-organizations-for-rights organization-service
                                        session
                                        [:form-edit])
       (map :oid)
       ((fn [oids] (if (and (empty? oids)
                            (get-in session [:identity :superuser]))
                     [oph-organization]
                     oids)))
       (mapcat (partial cache/get-from forms-in-use-cache))
       (reduce hakus-by-form-key {})))

(defn- epoch-millis->zoned-date-time
  [millis]
  (java.time.ZonedDateTime/ofInstant
   (.truncatedTo (java.time.Instant/ofEpochMilli millis)
                 (java.time.temporal.ChronoUnit/SECONDS))
   (java.time.ZoneId/of "Europe/Helsinki")))

(defn- parse-hakuaika
  [hakuaika]
  (cond-> {:start (epoch-millis->zoned-date-time (:alkuPvm hakuaika))}
    (contains? hakuaika :loppuPvm)
    (assoc :end (epoch-millis->zoned-date-time (:loppuPvm hakuaika)))))

(defn yhteishaku? [haku]
  (= (:hakutapaUri haku) "hakutapa_01#1"))

(defn parse-haku
  [haku]
  {:oid                    (:oid haku)
   :name                   (parse-multi-lang-text (:nimi haku))
   :prioritize-hakukohteet (:usePriority haku)
   :yhteishaku             (yhteishaku? haku)
   :hakuajat               (mapv parse-hakuaika (:hakuaikas haku))})

(defn- parse-hakukohde
  [hakukohde]
  {:oid (:oid hakukohde)
   :haku-oid (:hakuOid hakukohde)
   :name (parse-multi-lang-text (:hakukohteenNimet hakukohde))
   :tarjoaja-name (parse-multi-lang-text (:tarjoajaNimet hakukohde))
   :tarjoaja-oids (:tarjoajaOids hakukohde)
   :ryhmaliitokset (some->> (:ryhmaliitokset hakukohde)
                     (map #(:ryhmaOid %)))})

(defn- parse-search-result
  [search-result]
  (doall (mapcat :tulokset (:tulokset search-result))))

(def allowed-hakukohde-tilas #{"VALMIS" "JULKAISTU"})

(defn fetch-or-cached-hakukohde-search
  [hakukohde-search-cache haku-oid organization-oid]
  (some->> (cache/get-from
            hakukohde-search-cache
            {:haku-oid         haku-oid
             :organization-oid organization-oid})
           parse-search-result))

(defn stamp-user-organization [is-user-organization-fn hakukohde]
  (merge hakukohde
         {:user-organization? (boolean (is-user-organization-fn (:oid hakukohde)))}))

(defrecord CachedTarjontaService [koulutus-cache
                                  hakukohde-cache
                                  haku-cache
                                  hakukohde-search-cache]
  TarjontaService
  (get-hakukohde [this hakukohde-oid]
    (when-let [hakukohde (cache/get-from hakukohde-cache hakukohde-oid)]
      (when (contains? allowed-hakukohde-tilas (:tila hakukohde))
        ;; Serialization breaks boxed booleans, as it doesn't return the
        ;; canonical instance
        (update hakukohde
                :kaytetaanHakukohdekohtaistaHakuaikaa #(.booleanValue %)))))

  (get-hakukohteet [this hakukohde-oids]
    (remove #(or (nil? %)
                 (not (contains? allowed-hakukohde-tilas (:tila %))))
            (vals (cache/get-many-from hakukohde-cache hakukohde-oids))))

  (get-hakukohde-name [this hakukohde-oid]
    (when-let [hakukohde (.get-hakukohde this hakukohde-oid)]
      (parse-multi-lang-text (:hakukohteenNimet hakukohde))))

  (hakukohde-search [this haku-oid organization-oid]
    (let [result                  (some->> (fetch-or-cached-hakukohde-search hakukohde-search-cache haku-oid oph-organization)
                                           (map :oid)
                                           (.get-hakukohteet this)
                                           (mapv parse-hakukohde))
          user-organization-oids  (delay (some->> (fetch-or-cached-hakukohde-search hakukohde-search-cache
                                                                                    haku-oid
                                                                                    organization-oid)
                                                  (map :oid)
                                                  (set)))
          oph-organization?       (= oph-organization organization-oid)
          is-user-organization-fn (if oph-organization?
                                    (constantly true)
                                    #(contains? @user-organization-oids %))]
      (some->> result
               (map #(stamp-user-organization is-user-organization-fn %)))))

  (get-haku [this haku-oid]
    ;; Serialization breaks boxed booleans, as it doesn't return the
    ;; canonical instance
    (some-> (cache/get-from haku-cache haku-oid)
            (update :canSubmitMultipleApplications #(.booleanValue %))
            (update :usePriority #(.booleanValue %))))

  (get-haku-name [this haku-oid]
    (when-let [haku (.get-haku this haku-oid)]
      (parse-multi-lang-text (:nimi haku))))

  (get-koulutus [this koulutus-oid]
    (cache/get-from koulutus-cache koulutus-oid))

  (get-koulutukset [this koulutus-oids]
    (cache/get-many-from koulutus-cache koulutus-oids)))

(defrecord VirkailijaTarjontaFormsService [forms-in-use-cache
                                           organization-service]
  component/Lifecycle
  VirkailijaTarjontaService

  (start [this] this)
  (stop [this] this)

  (get-forms-in-use [this session]
    (forms-in-use forms-in-use-cache organization-service session)))

(defn new-tarjonta-service
  []
  (if (-> config :dev :fake-dependencies)
    (->MockTarjontaService)
    (->CachedTarjontaService nil nil nil nil)))

(defn new-virkailija-tarjonta-service
  []
  (if (-> config :dev :fake-dependencies)
    (->MockVirkailijaTarjontaService)
    (->VirkailijaTarjontaFormsService nil nil)))
