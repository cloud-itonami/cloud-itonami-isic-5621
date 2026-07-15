(ns cateringops.phase
  "Phase 0->3 staged rollout for the ISIC-5621 event-catering
  operations-coordination actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-logging     -- catering order/event record
                                     logging allowed, every write needs
                                     human approval.
    Phase 2  assisted-coordination-- adds event scheduling, supply
                                     order coordination proposals,
                                     still approval.
    Phase 3  supervised auto      -- governor-clean, high-confidence
                                     `:log-catering-order-record`/
                                     `:schedule-catering-event`/
                                     `:coordinate-supply-order` (below
                                     the cost threshold) may
                                     auto-commit. `:flag-food-safety-concern`
                                     NEVER auto-commits, at any phase.

  `:flag-food-safety-concern` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come (per this fleet's Wave 4
  person-facing-service guardrail, ADR-2607152500: a 'flag a concern'
  op must always escalate to human sign-off). Flagging a food-safety
  concern always needs a human to actually look at it.
  `cateringops.governor`'s own `always-escalate-ops` enforces the same
  invariant independently -- two layers, not one, agree on this. A
  high-cost `:coordinate-supply-order` proposal is also never
  auto-commit-eligible regardless of phase -- `cateringops.governor`'s
  own `high-cost-supply-order?` check independently forces escalation
  for those, even though the op itself IS a phase-3 `:auto` member for
  ordinary (below-threshold) proposals."
  (:require [cateringops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:flag-food-safety-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member
;; of any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops
  allowed to auto-commit when governor-clean>}."
  {0 {:label "read-only"              :writes #{}                                                             :auto #{}}
   1 {:label "assisted-logging"       :writes #{:log-catering-order-record}                                   :auto #{}}
   2 {:label "assisted-coordination"  :writes #{:log-catering-order-record :schedule-catering-event
                                               :coordinate-supply-order}                                       :auto #{}}
   3 {:label "supervised-auto"        :writes write-ops
      :auto #{:log-catering-order-record :schedule-catering-event :coordinate-supply-order}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean.
  - `:flag-food-safety-concern` is never auto-eligible at any phase, so
    it always escalates once the governor clears it (or holds if the
    governor doesn't). A high-cost `:coordinate-supply-order` proposal
    arrives here with a governor disposition of `:escalate` already
    (the governor's own `high-cost-supply-order?` check), so it passes
    through unchanged regardless of the phase's `:auto` set."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a CateringGovernor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
