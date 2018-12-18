(ns ataru.tarjonta-service.tarjonta-protocol)

(defprotocol TarjontaService
  (get-hakukohde [this hakukohde-oid])
  (get-hakukohteet [this hakukohde-oids])
  (get-hakukohde-name [this hakukohde-oid])
  (hakukohde-search [this haku-oid organization-oid])
  (get-haku [this haku-oid])
  (get-haku-name [this haku-oid])
  (get-koulutus [this haku-oid])
  (get-koulutukset [this koulutus-oids]))

(defprotocol VirkailijaTarjontaService
  (get-forms-in-use [this session]))
