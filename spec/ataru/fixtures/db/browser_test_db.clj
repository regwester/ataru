(ns ataru.fixtures.db.browser-test-db
  "Database fixture, insert test-data to DB"
  (:require [yesql.core :refer [defqueries]]
            [clojure.java.jdbc :as jdbc]
            [ataru.forms.form-store :as form-store]
            [ataru.applications.application-store :as application-store]
            [ataru.db.db :as db]
            [ataru.component-data.component :as component]
            [ataru.component-data.person-info-module :as person-info-module]
            [ataru.config.core :refer [config]]
            [ataru.db.migrations :as migrations]))

(defqueries "sql/form-queries.sql")

(def metadata {:created-by  {:oid  "1.2.246.562.24.1000000"
                             :date "2018-03-21T15:45:29.23+02:00"
                             :name "Teppo Testinen"}
               :modified-by {:oid  "1.2.246.562.24.1000000"
                             :date "2018-03-22T07:55:08Z"
                             :name "Teppo Testinen"}})

(def form1 {:id               1
            :key              "foobar1"
            :name             {:fi "Selaintestilomake1"}
            :created-by       "DEVELOPER"
            :organization-oid "1.2.246.562.10.0439845"
            :content          [{:fieldClass "wrapperElement"
                                :id         "G__31"
                                :metadata   metadata
                                :fieldType  "fieldset"
                                :children   [{:label      {:fi "Kengännumero" :sv ""}
                                              :fieldClass "formField"
                                              :id         "c2e4536c-1cdb-4450-b019-1b38856296ae"
                                              :metadata   metadata
                                              :params     {}
                                              :fieldType  "textField"}]
                                :label      {:fi "Jalat" :sv "Avsnitt namn"}}]
            :locked           nil
            :locked-by        nil})

(def form2 {:id               2
            :key              "foobar2"
            :name             {:fi "Selaintestilomake2"}
            :created-by       "DEVELOPER"
            :organization-oid "1.2.246.562.10.0439845"
            :content          [{:fieldClass "wrapperElement"
                                :metadata   metadata
                                :id         "d5cd3c63-02a3-4c19-a61e-35d85e46602f"
                                :fieldType  "fieldset"
                                :children   [{:label      {:fi "Pään ympärys" :sv ""}
                                              :fieldClass "formField"
                                              :metadata   metadata
                                              :id         "e257afce-ff30-40e1-ad6f-c224a1537d01"
                                              :params     {}
                                              :fieldType  "textField"}]
                                :label      {:fi "Pää" :sv "Avsnitt namn"}}]
            :locked           nil
            :locked-by        nil})

(def form3 {:id               3
            :key              "41101b4f-1762-49af-9db0-e3603adae3ad"
            :name             {:fi "Selaintestilomake3"}
            :created-by       "DEVELOPER"
            :organization-oid "1.2.246.562.10.0439845"
            :languages        ["fi" "en"]
            :content          [(component/hakukohteet)
                               (person-info-module/person-info-module)
                               {:fieldClass "wrapperElement"
                                :metadata   metadata
                                :id         "d5cd3c63-02a3-4c19-a61e-35d85e46602f"
                                :fieldType  "fieldset"
                                :children   [{:label      {:fi "Pään ympärys" :sv ""}
                                              :fieldClass "formField"
                                              :metadata   metadata
                                              :id         "e257afce-ff30-40e1-ad6f-c224a1537d01"
                                              :params     {}
                                              :fieldType  "textField"}]
                                :label      {:fi "Pää" :sv "Avsnitt namn"}}]
            :locked           nil
            :locked-by        nil})

(def ssn-testform {:id               4
                   :key              "41101b4f-1762-49af-9db0-e3603adae656"
                   :name             {:fi "SSN_testilomake"}
                   :created-by       "DEVELOPER"
                   :organization-oid "1.2.246.562.10.0439845"
                   :languages        ["fi"]
                   :content          [(component/hakukohteet)
                                      (person-info-module/person-info-module)]
                   :locked           nil
                   :locked-by        nil})

(def belongs-to-hakukohteet-test-form {:id               5
                                       :key              "belongs-to-hakukohteet-test-form"
                                       :name             {:fi "belongs-to-hakukohteet-test-form"}
                                       :created-by       "DEVELOPER"
                                       :organization-oid "1.2.246.562.10.0439845"
                                       :languages        ["fi"]
                                       :content          [(component/hakukohteet)
                                                          (person-info-module/person-info-module)
                                                          {:label      {:fi "Hakukohdekohtainen"}
                                                           :fieldClass "formField"
                                                           :metadata   metadata
                                                           :id         "c2e4536c-1cdb-4450-b019-1b38856296ae"
                                                           :params     {}
                                                           :fieldType  "textField"}]
                                       :locked           nil
                                       :locked-by        nil})

(def hakija-hakukohteen-hakuaika-test-form {:id               6
                                            :key              "hakija-hakukohteen-hakuaika-test-form"
                                            :name             {:fi "hakija-hakukohteen-hakuaika-test-form"}
                                            :created-by       "DEVELOPER"
                                            :organization-oid "1.2.246.562.10.0439845"
                                            :languages        ["fi"]
                                            :content          [(component/hakukohteet)
                                                               (person-info-module/person-info-module)
                                                               {:label                  {:fi "Hakukohteiden hakuajat ohi!"}
                                                                :fieldClass             "formField"
                                                                :metadata               metadata
                                                                :id                     "hakuajat-ohi"
                                                                :params                 {}
                                                                :belongs-to-hakukohteet ["1.2.246.562.20.49028100001"]
                                                                :fieldType              "textField"}
                                                               {:label                  {:fi "Osa hakuajoista voimassa!"}
                                                                :fieldClass             "formField"
                                                                :metadata               metadata
                                                                :id                     "osa-hakuajoista-ohi"
                                                                :params                 {}
                                                                :belongs-to-hakukohteet ["1.2.246.562.20.49028100002" "1.2.246.562.20.49028100001"]
                                                                :fieldType              "textField"}
                                                               {:label                  {:fi "Kaikki hakuajat voimassa!"}
                                                                :fieldClass             "formField"
                                                                :metadata               metadata
                                                                :id                     "kaikki-hakuajat-voimassa"
                                                                :params                 {}
                                                                :belongs-to-hakukohteet ["1.2.246.562.20.49028100002" "1.2.246.562.20.49028100003"]
                                                                :fieldType              "textField"}
                                                               {:label                     {:fi "Assosiaatio hakukohderyhmän kautta"}
                                                                :fieldClass                "formField"
                                                                :metadata                  metadata
                                                                :id                        "assosiaatio-hakukohderyhman-kautta"
                                                                :params                    {}
                                                                :belongs-to-hakukohderyhma ["1.2.246.562.28.00000000001"]
                                                                :fieldType                 "textField"}]
                                            :locked           nil
                                            :locked-by        nil})

(def assosiaatio-hakukohteen-organisaatiosta-form {:id 7
                                                   :key "hakukohteen-organisaatiosta-form"
                                                   :name {:fi "hakukohteen-organisaatiosta"}
                                                   :created-by "DEVELOPER"
                                                   :organization-oid "1.2.246.562.10.01010101"
                                                   :languages ["fi"]
                                                   :content
                                                   [(component/hakukohteet)
                                                    (person-info-module/person-info-module)]
                                                   :locked           nil
                                                   :locked-by        nil})

(def application1 {:form       1
                   :lang       "fi"
                   :key        "application-key1"
                   :person-oid "1.1.1"
                   :answers    [{:key       "c2e4536c-1cdb-4450-b019-1b38856296ae"
                                 :value     "47"
                                 :fieldType "textField"}
                                {:fieldType "textField"
                                 :key       "preferred-name"
                                 :value     "Seija Susanna"}
                                {:fieldType "textField"
                                 :key       "last-name"
                                 :value     "Kuikeloinen"}
                                {:fieldType "textField"
                                 :key       "ssn"
                                 :value     "020202A0202"}
                                {:fieldType "textField"
                                 :key       "email"
                                 :value     "seija.kuikeloinen@gmail.com"}
                                {:fieldType "textField"
                                 :key       "first-name"
                                 :value     "Seija Susanna"}
                                {:fieldType "textField"
                                 :key       "birth-date"
                                 :value     "29.10.1984"}
                                {:fieldType "dropdown"
                                 :key       "gender"
                                 :value     "2"}
                                {:fieldType "dropdown"
                                 :key       "nationality"
                                 :value     [["246"]]}]})

(def application2 {:form       1
                   :lang       "fi"
                   :key        "application-key2"
                   :person-oid "2.2.2"
                   :answers    [{:key       "c2e4536c-1cdb-4450-b019-1b38856296ae"
                                 :value     "39"
                                 :fieldType "textField"}
                                {:fieldType "textField"
                                 :key       "preferred-name"
                                 :value     "Ari"}
                                {:fieldType "textField"
                                 :key       "last-name"
                                 :value     "Vatanen"}
                                {:fieldType "textField"
                                 :key       "ssn"
                                 :value     "141196-933S"}
                                {:fieldType "textField"
                                 :key       "email"
                                 :value     "ari.vatanen@iki.fi"}
                                {:fieldType "textField"
                                 :key       "first-name"
                                 :value     "Aki"}
                                {:fieldType "textField"
                                 :key       "birth-date"
                                 :value     "29.10.1984"}
                                {:fieldType "dropdown"
                                 :key       "gender"
                                 :value     "1"}
                                {:fieldType "dropdown"
                                 :key       "nationality"
                                 :value     [["246"]]}]})

(def application3 {:form       1
                   :lang       "fi"
                   :key        "application-key3"
                   :person-oid "1.2.3.4.5.6"
                   :answers    [{:key       "c2e4536c-1cdb-4450-b019-1b38856296ae"
                                 :value     "47"
                                 :fieldType "textField"}
                                {:fieldType "textField"
                                 :key       "preferred-name"
                                 :value     "Johanna Irmeli"}
                                {:fieldType "textField"
                                 :key       "last-name"
                                 :value     "Tyrni"}
                                {:fieldType "textField"
                                 :key       "ssn"
                                 :value     "020202A0202"}
                                {:fieldType "textField"
                                 :key       "email"
                                 :value     "seija.kuikeloinen@gmail.com"}
                                {:fieldType "textField"
                                 :key       "first-name"
                                 :value     "Johanna Irmeli"}
                                {:fieldType "textField"
                                 :key       "birth-date"
                                 :value     "29.10.1984"}
                                {:fieldType "dropdown"
                                 :key       "gender"
                                 :value     "2"}
                                {:fieldType "dropdown"
                                 :key       "nationality"
                                 :value     [["246"]]}]})

(defn init-db-fixture []
  (form-store/create-new-form! form1 (:key form1))
  (form-store/create-new-form! form2 (:key form2))
  (form-store/create-new-form! form3 (:key form3))
  (form-store/create-new-form! form3 "41101b4f-1762-49af-9db0-e3603adae3ae")
  (form-store/create-new-form! assosiaatio-hakukohteen-organisaatiosta-form
                               (:key assosiaatio-hakukohteen-organisaatiosta-form))
  (form-store/create-new-form! belongs-to-hakukohteet-test-form
                               (:key belongs-to-hakukohteet-test-form))
  (form-store/create-new-form! hakija-hakukohteen-hakuaika-test-form
                               (:key hakija-hakukohteen-hakuaika-test-form))
  (jdbc/with-db-transaction [conn {:datasource (db/get-datasource :db)}]
    (application-store/add-application application1 [] nil)
    (application-store/add-application application2 [] nil)
    (application-store/add-application application3 [] nil)))

(defn reset-test-db [insert-initial-fixtures?]
  (db/clear-db! :db (-> config :db :schema))
  (migrations/migrate)
  (when insert-initial-fixtures? (init-db-fixture)))

(defn insert-test-form [form-name]
  (if (= form-name "SSN_testilomake")
    (form-store/create-new-form! ssn-testform (:key ssn-testform))
    (println (str "No test form (" form-name ") found. Run virkailija test first!"))))
