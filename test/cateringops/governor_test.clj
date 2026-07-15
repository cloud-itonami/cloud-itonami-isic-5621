(ns cateringops.governor-test
  "Pure unit tests of `cateringops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [cateringops.governor :as gov]
            [cateringops.store :as store]))

(def order-1 {:order-id "order-1" :client "Kawasaki Wedding Reception" :registered? true :verified? true})
(def order-3 {:order-id "order-3" :client "Tanaka Family Reunion" :registered? true :verified? false})

(defn- clean-proposal [op order-id]
  {:op op :order-id order-id :summary "s" :rationale "routine catering-order coordination"
   :cites [order-id] :effect :propose :value {} :confidence 0.85})

(deftest order-unregistered-is-hard
  (testing "no order record at all -> HARD hold"
    (let [s (store/mem-store {"order-1" order-1})
          verdict (gov/check {} nil (clean-proposal :log-catering-order-record "unknown-order") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:order-unverified} (map :rule (:violations verdict)))))))

(deftest order-unverified-is-hard
  (testing "order registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"order-3" order-3})
          verdict (gov/check {} nil (clean-proposal :log-catering-order-record "order-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:order-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"order-1" order-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-catering-event "order-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"order-1" order-1})
          verdict (gov/check {} nil (clean-proposal :finalize-food-safety-clearance "order-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest allergen-exclusion-override-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches overriding an allergen-exclusion requirement is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"order-1" order-1})
          poisoned (assoc (clean-proposal :log-catering-order-record "order-1")
                          :rationale "decided to override the allergen exclusion requested by the guest"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest kitchen-safety-certification-content-is-hard
  (testing "a proposal touching certifying the kitchen as safe post-incident is HARD-blocked, same as an allergen-exclusion override"
    (let [s (store/mem-store {"order-1" order-1})
          poisoned (assoc (clean-proposal :log-catering-order-record "order-1")
                          :rationale "prepared to certify the kitchen as safe following the incident"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest health-department-clearance-content-is-hard
  (testing "a proposal touching a health-department/regulatory clearance is HARD-blocked"
    (let [s (store/mem-store {"order-1" order-1})
          poisoned (assoc (clean-proposal :schedule-catering-event "order-1")
                          :summary "obtain health department clearance for the venue")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest recall-decision-content-is-hard
  (testing "a proposal touching a product-recall decision is HARD-blocked"
    (let [s (store/mem-store {"order-1" order-1})
          poisoned (assoc (clean-proposal :coordinate-supply-order "order-1")
                          :summary "issue a recall of the affected ingredient batch")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest license-suspension-content-is-hard
  (testing "a proposal touching a food-service license suspension/revocation is HARD-blocked"
    (let [s (store/mem-store {"order-1" order-1})
          poisoned (assoc (clean-proposal :flag-food-safety-concern "order-1")
                          :value {:decision "recommend license suspension for the vendor"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-food-safety-concern-is-not-scope-excluded
  (testing "flagging observed allergen-mismatch/temperature-abuse/contamination concerns as a FOOD-SAFETY CONCERN (not a finalized authority decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"order-1" order-1})
          concern (assoc (clean-proposal :flag-food-safety-concern "order-1")
                         :value {:concern "shellfish garnish observed on a dish tagged shellfish-free, guest has known allergy"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (allergen mismatch/temperature abuse) is exactly what this op exists to surface"))))

(deftest food-safety-concern-always-escalates-regardless-of-confidence
  (testing ":flag-food-safety-concern always escalates even at maximum confidence, never auto-commit-eligible"
    (let [s (store/mem-store {"order-1" order-1})
          concern (assoc (clean-proposal :flag-food-safety-concern "order-1") :confidence 1.0)
          verdict (gov/check {} nil concern s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (true? (:high-stakes? verdict))))))

(deftest high-cost-supply-order-always-escalates
  (testing "a coordinate-supply-order proposal above the cost threshold always escalates, regardless of confidence"
    (let [s (store/mem-store {"order-1" order-1})
          expensive (assoc (clean-proposal :coordinate-supply-order "order-1")
                           :value {:estimated-cost 9000}
                           :confidence 0.99)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (true? (:high-stakes? verdict))))))

(deftest low-cost-supply-order-does-not-escalate-on-cost-alone
  (testing "a coordinate-supply-order proposal below the cost threshold is not high-stakes purely due to cost"
    (let [s (store/mem-store {"order-1" order-1})
          cheap (assoc (clean-proposal :coordinate-supply-order "order-1")
                       :value {:estimated-cost 200}
                       :confidence 0.9)
          verdict (gov/check {} nil cheap s)]
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict)))
      (is (false? (:high-stakes? verdict))))))

(deftest low-confidence-always-escalates
  (testing "confidence below the floor escalates regardless of op"
    (let [s (store/mem-store {"order-1" order-1})
          uncertain (assoc (clean-proposal :log-catering-order-record "order-1") :confidence 0.4)
          verdict (gov/check {} nil uncertain s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict))))))
