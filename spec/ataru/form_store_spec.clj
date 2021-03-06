(ns ataru.form-store-spec
  (:require [speclj.core :refer :all]
            [clj-time.core :as t]
            [ataru.fixtures.person-info-form :refer [form]]
            [ataru.forms.form-store :as store]
            [taoensso.timbre :refer [spy debug]])
  (:import
   (clojure.lang ExceptionInfo)))

(def org-id  "1.2.246.562.10.2.45")
(def id-less (-> form (dissoc :id) (assoc :organization_oid org-id)))

(describe "form versioning"
  (tags :unit :form-versioning)

  (it "should be saved as new form"
      (let [{:keys [id key] :as new-form} (store/create-new-form! id-less)]
        (should id)
        (should key)))

  (it "should version subsequent forms"
      (let [{:keys [id key created-time] :as version-one} (store/create-form-or-increment-version! (assoc id-less :organization-oid org-id))
            version-two (store/create-form-or-increment-version! (assoc version-one :organization-oid org-id))]
        (should= key (:key version-two))
        (should-not= id (:id version-two))
        (should (t/after? (:created-time version-two) created-time))))

  (it "should retrieve latest version with old version"
      (let [version-one (store/create-form-or-increment-version! (assoc id-less :organization-oid org-id))
            version-two (store/create-form-or-increment-version! (assoc version-one :organization-oid org-id))]
        (should= (:id version-two) (:id (store/fetch-latest-version (:id version-one))))
        (should= (:id version-two) (:id (store/fetch-latest-version (:id version-two))))))

  (it "should throw when later version already exists"
      (let [{:keys [id key created-time] :as version-one} (store/create-form-or-increment-version! (assoc id-less :organization-oid org-id))
            version-two                                   (store/create-form-or-increment-version! (assoc  version-one :organization-oid org-id))]
        (should-throw ExceptionInfo "Lomakkeen sisältö on muuttunut. Lataa sivu uudelleen."
                      (keys (store/create-form-or-increment-version! (assoc version-one :organization-oid org-id)))))))
