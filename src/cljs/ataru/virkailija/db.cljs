(ns ataru.virkailija.db
  (:require
   [ataru.application.review-states :as review-states]
   [ataru.virkailija.application-sorting :as application-sorting]))

(def default-db
  {:editor               {:forms               nil
                          :autosave            nil                  ; autosave stop function, see autosave.cljs
                          :selected-form-key   nil
                          :used-by-haut        {:fetching? false
                                                :error?    false}
                          :email-template-lang "fi"}
   ; Initial active panel on page load.
   :active-panel         :editor
   :application          {:review                     {}
                          :attachment-state-filter    (mapv first review-states/attachment-hakukohde-review-types-with-no-requirements)
                          :processing-state-filter    (mapv first review-states/application-hakukohde-processing-states)
                          :selection-state-filter     (mapv first review-states/application-hakukohde-selection-states)
                          :sort                       application-sorting/initial-sort
                          :application-list-expanded? true
                          :filters                    {:language-requirement {:unreviewed  true
                                                                              :fulfilled   true
                                                                              :unfulfilled true}
                                                       :degree-requirement   {:unreviewed  true
                                                                              :fulfilled   true
                                                                              :unfulfilled true}
                                                       :eligibility-state    {:unreviewed true
                                                                              :eligible   true
                                                                              :uneligible true}
                                                       :payment-obligation   {:unreviewed    true
                                                                              :obligated     true
                                                                              :not-obligated true}
                                                       :only-identified      {:identified   true
                                                                              :unidentified true}
                                                       :base-education       {:pohjakoulutus_kk_ulk                     true
                                                                              :pohjakoulutus_lk                         true
                                                                              :pohjakoulutus_kk                         true
                                                                              :pohjakoulutus_amt                        true
                                                                              :pohjakoulutus_ulk                        true
                                                                              :pohjakoulutus_muu                        true
                                                                              :pohjakoulutus_avoin                      true
                                                                              :pohjakoulutus_yo_ammatillinen            true
                                                                              :pohjakoulutus_am                         true
                                                                              :pohjakoulutus_yo_ulkomainen              true
                                                                              :pohjakoulutus_yo                         true
                                                                              :pohjakoulutus_yo_kansainvalinen_suomessa true}}}
   :haut                 {}
   :hakukohteet          {}
   :fetching-haut        0
   :fetching-hakukohteet 0
   :banner               {:type :in-flow}})
