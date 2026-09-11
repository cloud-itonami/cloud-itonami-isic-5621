(ns cateringops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean catering order-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-logging, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then an event-scheduling request, a
  low-cost supply-order coordination (auto-commit clean at phase 3),
  then a high-cost supply-order coordination (ALWAYS escalates
  regardless of phase, above the cost threshold), then a food-safety
  concern flag (ALWAYS escalates, at any phase -- approve, then
  commit), then HARD-hold scenarios: an unregistered order, an order
  registered but not yet verified, a proposal whose own `:effect` is
  not `:propose`, and a proposal that has drifted into the
  permanently-excluded food-safety-authority-finalization scope
  (allergen-exclusion override / kitchen safety certification)."
  (:require [langgraph.graph :as g]
            [cateringops.advisor :as advisor]
            [cateringops.store :as store]
            [cateringops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "catering-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :catering-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :catering-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-catering-order-record order-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-catering-order-record :order-id "order-1"
                                  :patch {:menu "seasonal tasting menu" :headcount 120 :allergens ["peanut" "shellfish"]}} coordinator-phase-1)]
      (println r)
      (println "-- human catering coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-catering-order-record order-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-catering-order-record :order-id "order-1"
                                  :patch {:headcount 122 :notes "two additional vegan guests"}} coordinator-phase-3))

    (println "\n== schedule-catering-event order-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-catering-event :order-id "order-1"
                                  :patch {:prep-start "2026-07-19T06:00" :delivery "2026-07-19T11:00" :venue "Grand Hall"}} coordinator-phase-3))

    (println "\n== coordinate-supply-order order-1, low cost (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-supply-order :order-id "order-1"
                                  :patch {:item "chafing dishes" :quantity 20 :estimated-cost 800}} coordinator-phase-3))

    (println "\n== coordinate-supply-order order-1, HIGH cost above threshold (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :coordinate-supply-order :order-id "order-1"
                                 :patch {:item "premium seafood tower ingredients" :quantity 4 :estimated-cost 7500}} coordinator-phase-3)]
      (println r)
      (println "-- human procurement lead reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== flag-food-safety-concern order-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t6" {:op :flag-food-safety-concern :order-id "order-1"
                                 :patch {:concern "shellfish garnish observed on a dish tagged shellfish-free" :confidence 0.92}} coordinator-phase-3)]
      (println r)
      (println "-- human catering coordinator reviews & approves --")
      (println (approve! actor "t6")))

    (println "\n== log-catering-order-record order-99 (unregistered order -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-catering-order-record :order-id "order-99"
                                  :patch {:menu "unknown"}} coordinator-phase-3))

    (println "\n== log-catering-order-record order-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :log-catering-order-record :order-id "order-3"
                                  :patch {:menu "buffet"}} coordinator-phase-3))

    (println "\n== schedule-catering-event order-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t9" {:op :schedule-catering-event :order-id "order-1"
                                           :patch {:venue "Riverside Pavilion"}} coordinator-phase-3)))

    (println "\n== log-catering-order-record order-1, advisor drifts into food-safety-authority scope -> HARD hold, permanent ==")
    (println (exec-op actor "t10" {:op :log-catering-order-record :order-id "order-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
