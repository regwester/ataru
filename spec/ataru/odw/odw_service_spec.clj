(ns ataru.odw.odw-service-spec
  (:require [ataru.odw.odw-service :as odw-service]
            [speclj.core :refer [describe it run-specs should= tags]]))

; required by APIs and some logging, irrelevant for functionality (for now)
; if further tests require this to be a real id, see into application-store and how it functions
(def application-key :odw_service_unit_test)

(def vocational-identifier {:higher-completed-base-education {:value ["pohjakoulutus_am"]}})

(defn select-year-for
  ([answers]
   (select-year-for nil answers))
  ([haku answers]
   (#'ataru.odw.odw-service/get-kk-pohjakoulutus haku answers application-key)))

(describe "vocational degree / pohjakoulutus_am completion year selection when"
  (tags :unit :odw :OY-342)

  (it "vocational degree has been completed"
      (should= [{:pohjakoulutuskklomake "pohjakoulutus_am" :suoritusvuosi 2016}]
               (select-year-for (merge vocational-identifier {:pohjakoulutus_am--year-of-completion {:value "2016"}}))))

  (it "vocational degree has been completed between 2017 and 2020 (ODW hardcodings)"
      ; 1. yhteishaku
      (should= [{:pohjakoulutuskklomake "pohjakoulutus_am" :suoritusvuosi 2017}]
               (select-year-for (merge vocational-identifier {:5e5a0f04-f04d-478c-b093-3f47d33ba1a4 {:value "2017"}})))
      ; 2. yhteishaku
      (should= [{:pohjakoulutuskklomake "pohjakoulutus_am" :suoritusvuosi 2018}]
               (select-year-for (merge vocational-identifier {:75d3d13c-5865-4924-8a69-d22b8a8aea65 {:value "2018"}}))))

  (it "vocational degree is completed during 2020 (ODW special hardcoding)"
      ; 1. yhteishaku
      (should= [{:pohjakoulutuskklomake "pohjakoulutus_am" :suoritusvuosi 2020}]
               (select-year-for
                {:hakukausiVuosi 2020}
                (merge vocational-identifier {:f9340e89-4a1e-4626-9246-2a77a32b22ed {:value "1"}})))
      ; 2. yhteishaku
      (should= [{:pohjakoulutuskklomake "pohjakoulutus_am" :suoritusvuosi 2020}]
               (select-year-for
                {:hakukausiVuosi 2020}
                (merge vocational-identifier {:b6fa0257-c1fd-4107-b151-380e02c56fa9 {:value "1"}})))))

(def double-degree-identifier {:higher-completed-base-education {:value ["pohjakoulutus_yo_ammatillinen"]}})
(def double-degree-matriculation-completed {:pohjakoulutus_yo_ammatillinen--marticulation-year-of-completion {:value "2008"}})
(def double-degree-matriculation-completed-odw {:487bea81-a6bc-43a2-8802-d6d57bbbe8cb {:value "2012"}})
(def double-degree-vocational-completed {:pohjakoulutus_yo_ammatillinen--vocational-completion-year {:value "2009"}})
(def double-degree-vocational-completed-odw {:60ce79f9-b37a-4b7e-a7e0-f25ba430f055 {:value "2010"}})

(describe "secondary level double degree (kaksoistutkinto) / pohjakoulutus_yo_ammatillinen completion year selection when"
  (tags :unit :odw :OY-342)

  (it "both matriculation and vocational parts have been completed"
    (let [answers (merge double-degree-identifier
                         double-degree-matriculation-completed
                         double-degree-vocational-completed)]
      (should= [{:pohjakoulutuskklomake "pohjakoulutus_yo_ammatillinen" :suoritusvuosi 2009}]
               (select-year-for answers))))

  (it "both matriculation and vocational parts have been completed (ODW hardcodings)"
    (let [answers (merge double-degree-identifier
                         double-degree-matriculation-completed-odw
                         double-degree-vocational-completed-odw)]
      (should= [{:pohjakoulutuskklomake "pohjakoulutus_yo_ammatillinen" :suoritusvuosi 2012}]
               (select-year-for answers))))

  (it "only matriculation part has been completed"
    (let [answers     (merge double-degree-identifier double-degree-matriculation-completed)
          odw-answers (merge double-degree-identifier double-degree-matriculation-completed-odw)]
      (should= [{:pohjakoulutuskklomake "pohjakoulutus_yo_ammatillinen"}] (select-year-for answers))
      (should= [{:pohjakoulutuskklomake "pohjakoulutus_yo_ammatillinen"}] (select-year-for odw-answers))))

  (it "only vocational part has been completed"
    (let [answers     (merge double-degree-identifier double-degree-vocational-completed)
          odw-answers (merge double-degree-identifier double-degree-vocational-completed-odw)]
      (should= [{:pohjakoulutuskklomake "pohjakoulutus_yo_ammatillinen"}] (select-year-for answers))
      (should= [{:pohjakoulutuskklomake "pohjakoulutus_yo_ammatillinen"}] (select-year-for odw-answers))))

  (it "applicant should complete education in future (ODW hardcodings)"
    ; 1. yhteishaku, 2017-nyt
    (should= [{:pohjakoulutuskklomake "pohjakoulutus_yo_ammatillinen" :suoritusvuosi 2099}]
             (select-year-for (merge double-degree-identifier {:0a6ba6b1-616c-492b-a501-8b6656900ebd {:value "2099"}})))
    ; 1. yhteishaku, nyt
    (should= [{:pohjakoulutuskklomake "pohjakoulutus_yo_ammatillinen" :suoritusvuosi 2098}]
             (select-year-for
              {:hakukausiVuosi 2098}
              (merge double-degree-identifier {:22df6790-588f-4c45-8238-3ecfccdf6d93 {:value "1"}})))
    ; 2. yhteishaku, 2017-nyt
    (should= [{:pohjakoulutuskklomake "pohjakoulutus_yo_ammatillinen" :suoritusvuosi 2097}]
             (select-year-for (merge double-degree-identifier {:86c7cc27-e1b3-4b3a-863c-1719b424370f {:value "2097"}})))
    ; 2. yhteishaku, nyt
    (should= [{:pohjakoulutuskklomake "pohjakoulutus_yo_ammatillinen" :suoritusvuosi 2096}]
             (select-year-for
              {:hakukausiVuosi 2096}
              (merge double-degree-identifier {:dfeb9d56-4d53-4087-9473-1b2d9437e47f {:value "1"}})))))

(def international-matriculation-fi-identifier {:higher-completed-base-education {:value ["pohjakoulutus_yo_kansainvalinen_suomessa"]}})

(describe "international matriculation examination in Finland / pohjakoulutus_yo_kansainvalinen_suomessa completion year selection when"
  (tags :unit :odw :OY-342)

  (it "applicant will complete degree this year"
    ; This is a regression: All these were "0" before 2020 but somewhere along the line the form answers were reordered
    ; causing the values to change
    (let [completion-year-output [{:pohjakoulutuskklomake "pohjakoulutus_yo_kansainvalinen_suomessa" :suoritusvuosi 2020}]
          application-form       {:hakukausiVuosi 2020}
          degrees                {:pohjakoulutus_yo_kansainvalinen_suomessa--ib--year-of-completion-this-year "1"
                                  :pohjakoulutus_yo_kansainvalinen_suomessa--eb--year-of-completion-this-year "1"
                                  :pohjakoulutus_yo_kansainvalinen_suomessa--rb--year-of-completion-this-year "1"
                                  :32b5f6a9-1ccb-4227-8c68-3c0a82fb0a73                                       "0"
                                  :64d561e2-20f7-4143-9ad8-b6fa9a8f6fed                                       "0"
                                  :6b7119c9-42ec-467d-909c-6d1cc555b823                                       "0"}]
      (doall (map (fn [[degree value]]
                    (should= completion-year-output
                             (select-year-for
                              application-form
                              (merge international-matriculation-fi-identifier {degree {:value value}}))))
                  degrees)))))

(def international-matriculation-identifier {:higher-completed-base-education {:value ["pohjakoulutus_yo_ulkomainen"]}})

(describe "international matriculation examination / pohjakoulutus_yo_kansainvalinen completion year selection when"
  (tags :unit :odw :OY-342)

  (it "applicant will complete degree this year"
    ; Same as above, this is also a regression for the exact same reason.
    (let [completion-year-output [{:pohjakoulutuskklomake "pohjakoulutus_yo_ulkomainen" :suoritusvuosi 2020}]
          application-form       {:hakukausiVuosi 2020}
          degrees                {:pohjakoulutus_yo_ulkomainen--ib--year-of-completion-this-year "1"
                                  :pohjakoulutus_yo_ulkomainen--eb--year-of-completion-this-year "1"
                                  :pohjakoulutus_yo_ulkomainen--rb--year-of-completion-this-year "1"
                                  :d037fa56-6354-44fc-87d6-8b774b95dcdf                          "0"
                                  :6e980e4d-257a-49ba-a5e6-5424220e6f08                          "0"
                                  :220c3b47-1ca6-47e7-8af2-2f6ff823e07b                          "0"}]
      (doall (map (fn [[degree value]]
                    (should= completion-year-output
                             (select-year-for
                              application-form
                              (merge international-matriculation-identifier {degree {:value value}}))))
                  degrees)))))

(run-specs)
