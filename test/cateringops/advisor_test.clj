(ns cateringops.advisor-test
  "Unit tests of `cateringops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [cateringops.advisor :as adv]
            [cateringops.store :as store]))

(def db (store/seed-db))

(deftest propose-order-record-shape
  (testing "catering order-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-catering-order-record
                           :order-id "order-1"
                           :patch {:menu "tasting menu" :headcount 120}})]
      (is (= :log-catering-order-record (:op p)))
      (is (= "order-1" (:order-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :order-id)))))

(deftest propose-event-schedule-shape
  (testing "event-schedule proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-catering-event
                           :order-id "order-2"
                           :patch {:delivery "2026-07-20T11:00" :venue "Grand Hall"}})]
      (is (= :schedule-catering-event (:op p)))
      (is (= "order-2" (:order-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-order-shape
  (testing "supply-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-order
                           :order-id "order-1"
                           :patch {:item "linens" :quantity 20 :estimated-cost 400}})]
      (is (= :coordinate-supply-order (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p)))
      (is (= 400 (get-in p [:value :estimated-cost]))))))

(deftest propose-food-safety-concern-shape
  (testing "food-safety-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-food-safety-concern
                           :order-id "order-1"
                           :patch {:concern "temperature abuse observed in cold-holding unit"}})]
      (is (= :flag-food-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-catering-order-record :schedule-catering-event
                :coordinate-supply-order :flag-food-safety-concern]]
      (let [p (adv/infer db {:op op :order-id "order-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-catering-order-record :schedule-catering-event
                :coordinate-supply-order :flag-food-safety-concern]]
      (let [p (adv/infer db {:op op :order-id "order-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
