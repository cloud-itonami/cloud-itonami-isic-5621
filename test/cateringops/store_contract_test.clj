(ns cateringops.store-contract-test
  "Contract tests for `cateringops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [cateringops.store :as store]))

(deftest mem-store-order-lookup
  (testing "MemStore can store and retrieve orders by ID (string keys)"
    (let [orders {"o1" {:order-id "o1" :client "Alice" :registered? true :verified? true}}
          s (store/mem-store orders)]
      (is (some? (store/order s "o1")))
      (is (nil? (store/order s "o99"))))))

(deftest mem-store-all-orders
  (testing "MemStore returns all orders in sorted order"
    (let [orders {"o2" {:order-id "o2" :client "Bob"}
                  "o1" {:order-id "o1" :client "Alice"}
                  "o3" {:order-id "o3" :client "Carol"}}
          s (store/mem-store orders)
          all-o (store/all-orders s)]
      (is (= 3 (count all-o)))
      (is (= "o1" (:order-id (first all-o))))
      (is (= "o3" (:order-id (last all-o)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-catering-order-record :order-id "o1" :value {:menu "lunch"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-orders
  (testing "MemStore with-orders replaces the order directory"
    (let [s (store/mem-store {})
          new-orders {"o1" {:order-id "o1" :client "Alice"}}]
      (is (= 0 (count (store/all-orders s))))
      (store/with-orders s new-orders)
      (is (= 1 (count (store/all-orders s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo orders"
    (let [s (store/seed-db)]
      (is (> (count (store/all-orders s)) 0))
      (is (some? (store/order s "order-1")))
      (is (some? (store/order s "order-2")))
      (is (some? (store/order s "order-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for order-id"
    (let [demo (store/demo-data)
          orders (:orders demo)]
      (doseq [[k v] orders]
        (is (string? k) "keys must be strings")
        (is (string? (:order-id v)) "order-id must be string")
        (is (= k (:order-id v)) "key must match order-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
