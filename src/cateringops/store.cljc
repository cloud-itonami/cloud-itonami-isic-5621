(ns cateringops.store
  "SSoT for the ISIC-5621 event-catering COORDINATION actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of an event-catering
  business: catering order/event record logging (menu, headcount, allergen
  flags), prep/staging/delivery event scheduling, ingredient/equipment
  supply-order coordination, and food-safety-concern flagging (allergen
  mismatch, temperature abuse, contamination). It never touches finalizing
  a food-safety-authority decision -- overriding an allergen-exclusion
  requirement, certifying a kitchen as safe post-incident, issuing a
  regulatory/health-department clearance, or any other food-safety-authority
  sign-off -- see `cateringops.governor`'s `scope-exclusion-violations`, a
  HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). An `orders` directory keyed by `:order-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss
  bug that plagued an earlier shepherd attempt).

  A registered/verified catering order record must exist before ANY
  proposal for that order may ever commit or escalate --
  `cateringops.governor`'s `order-unverified-violations` re-derives this
  from the order's own `:registered?`/`:verified?` fields, never from
  proposal self-report, the SAME 'ground truth, not self-report'
  discipline every sibling actor's own governor uses.

  The ledger stays append-only: which order a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (order [s order-id] "Registered catering order record, or nil.
    Order map: {:order-id .. :client .. :registered? bool :verified? bool}.")
  (all-orders [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-orders [s orders] "replace/seed the order directory (map order-id->order)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained catering-order directory covering both the
  happy path and the governor's own hard checks, so the actor + tests
  run offline."
  []
  {:orders
   {"order-1" {:order-id "order-1" :client "Kawasaki Wedding Reception (120 guests)"
               :registered? true :verified? true}
    "order-2" {:order-id "order-2" :client "Acme Corp Holiday Party (60 guests)"
               :registered? true :verified? true}
    "order-3" {:order-id "order-3" :client "Tanaka Family Reunion (in intake, 40 guests)"
               :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (order [_ order-id] (get-in @a [:orders order-id]))
  (all-orders [_] (sort-by :order-id (vals (:orders @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-orders [s orders] (when (seq orders) (swap! a assoc :orders orders)) s))

(defn seed-db
  "A MemStore seeded with the demo order directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `orders` map (order-id string ->
  order map) -- the primary test/dev entry point. `orders` may be empty
  (an unregistered-everywhere store)."
  [orders]
  (->MemStore (atom {:orders (or orders {}) :ledger [] :coordination-log []})))
