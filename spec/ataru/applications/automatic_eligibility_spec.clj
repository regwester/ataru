(ns ataru.applications.automatic-eligibility-spec
  (:require [speclj.core :refer :all]
            [ataru.applications.automatic-eligibility :as ae]))

(describe "automatic-eligibility-if-ylioppilas"
  (tags :unit)
  (describe "Automatic eligibility not in use"
    (it "if tarjonta not in use"
      (should-be-nil (ae/automatic-eligibility-if-ylioppilas
                      nil
                      nil
                      nil
                      nil
                      nil)))
    (it "if time is pass PH_AHP"
      (should-be-nil (ae/automatic-eligibility-if-ylioppilas
                      {:ylioppilastutkintoAntaaHakukelpoisuuden true}
                      {:PH_AHP {:date 1}}
                      2
                      nil
                      nil))))
  (describe "When automatic eligibility in use"
    (it "set eligibility for hakukohde that has automatic eligibility in use"
      (should== {:action         ae/set-eligible-if-unreviewed
                 :hakukohde-oids ["1.2.246.562.20.23058637367"]}
                (ae/automatic-eligibility-if-ylioppilas
                 {:ylioppilastutkintoAntaaHakukelpoisuuden true}
                 {:PH_AHP {:date 2}}
                 1
                 [{:oid                                     "1.2.246.562.20.23058637366"
                   :ylioppilastutkintoAntaaHakukelpoisuuden false}
                  {:oid                                     "1.2.246.562.20.23058637367"
                   :ylioppilastutkintoAntaaHakukelpoisuuden true}
                  {:oid                                     "1.2.246.562.20.23058637368"
                   :ylioppilastutkintoAntaaHakukelpoisuuden false}]
                 true)))
    (it "set eligible if ylioppilas"
      (should== {:action         ae/set-eligible-if-unreviewed
                 :hakukohde-oids ["1.2.246.562.20.23058637366"]}
                (ae/automatic-eligibility-if-ylioppilas
                 {:ylioppilastutkintoAntaaHakukelpoisuuden true}
                 {:PH_AHP {:date 2}}
                 1
                 [{:oid                                     "1.2.246.562.20.23058637366"
                   :ylioppilastutkintoAntaaHakukelpoisuuden true}]
                 true)))
    (it "set unreviewed if not ylioppilas"
      (should== {:action         ae/set-unreviewed-if-automatically-eligible
                 :hakukohde-oids ["1.2.246.562.20.23058637366"]}
                (ae/automatic-eligibility-if-ylioppilas
                 {:ylioppilastutkintoAntaaHakukelpoisuuden true}
                 {:PH_AHP {:date 2}}
                 1
                 [{:oid                                     "1.2.246.562.20.23058637366"
                   :ylioppilastutkintoAntaaHakukelpoisuuden true}]
                 false))))
  (it "throw if PH_AHP not set when automatic eligibility in use"
    (should-throw Exception (ae/automatic-eligibility-if-ylioppilas
                             {:ylioppilastutkintoAntaaHakukelpoisuuden true}
                             {:PH_AHP nil}
                             nil
                             nil
                             nil))))
