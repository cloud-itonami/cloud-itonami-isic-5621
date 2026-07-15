(ns cateringops.governor
  "CateringGovernor -- the independent compliance layer that earns the
  CateringAdvisor the right to commit. The advisor has no notion of
  whether a catering order is actually registered and verified, whether
  its own proposed `:effect` secretly claims a direct actuation instead
  of a mere proposal, or whether it has silently drifted into a
  permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (catering order/event record logging, event scheduling, supply
  coordination, food-safety-concern flagging). It NEVER performs or
  authorizes:
    - directly finalizing a food-safety-authority decision (e.g.
      overriding an allergen-exclusion requirement, or certifying a
      kitchen as safe post-incident)
    - issuing a health-department/regulatory clearance
    - a product-recall decision
    - a food-service license suspension/revocation or other
      compliance-enforcement action

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Order unverified        -- the target catering order record
                                   must exist AND be independently
                                   confirmed `:registered?`/
                                   `:verified?` in the store before
                                   ANY proposal for it may commit or
                                   even escalate. Never trusts a
                                   proposal's own claim about the
                                   order -- re-derived from the
                                   order's own store record, the same
                                   'ground truth, not self-report'
                                   discipline every sibling actor's
                                   governor uses.
    2. Effect not :propose      -- every proposal's `:effect` MUST
                                   be `:propose`. Any other effect
                                   value is, by construction, a
                                   claim to directly actuate/commit
                                   outside governance -- HARD block,
                                   not merely low-confidence.
    3. Scope exclusion          -- ANY proposal (regardless of op)
                                   whose op, rationale, summary,
                                   citations or draft value touches
                                   a food-safety-authority-finalization
                                   decision (allergen-exclusion
                                   override, kitchen safety
                                   certification, health-department/
                                   regulatory clearance, recall order,
                                   license suspension/revocation,
                                   compliance-enforcement action) is a
                                   HARD, PERMANENT block -- this
                                   actor's charter excludes that
                                   territory structurally, not as a
                                   rollout milestone. Evaluated
                                   UNCONDITIONALLY on every
                                   proposal. An op outside the
                                   closed four-op allowlist is the
                                   SAME failure mode (an advisor
                                   proposing something it was never
                                   authorized to propose) and is
                                   folded into this same check.

  Two ESCALATE (SOFT) gates, neither ever auto-commit-eligible, per
  this fleet's Wave 4 person-facing-service guardrail (ADR-2607152500):
    - `:flag-food-safety-concern` -- ALWAYS escalates to a human,
      regardless of confidence, regardless of how clean the proposal
      otherwise is. `cateringops.phase` independently agrees:
      `:flag-food-safety-concern` is never a member of any phase's
      `:auto` set either -- two layers, not one.
    - `:coordinate-supply-order` above `cost-threshold` -- a
      high-value procurement commitment always needs a human's
      sign-off, regardless of confidence.
  Plus the ordinary low-confidence gate (below `confidence-floor`)
  shared by every op."
  (:require [clojure.string :as str]
            [cateringops.store :as store]))

(def confidence-floor 0.6)

(def cost-threshold
  "Supply-order proposals whose :estimated-cost exceeds this always
  escalate for human sign-off, regardless of confidence -- a high-value
  procurement commitment is never auto-commit-eligible."
  5000)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-catering-order-record :schedule-catering-event
    :coordinate-supply-order :flag-food-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not. Per
  ADR-2607152500, a 'flag a concern' op must never be auto-commit-eligible."
  #{:flag-food-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  food-safety-authority decision (allergen-exclusion override, kitchen
  safety certification), a health-department/regulatory clearance, a
  product-recall decision, or a food-service license suspension/
  revocation. Scanned across the proposal's op/summary/rationale/
  cites/value, never trusting the advisor's own framing of its intent."
  ["allergen exclusion override" "allergen-exclusion override"
   "override the allergen exclusion" "override allergen exclusion"
   "waive the allergen exclusion" "waive allergen exclusion"
   "アレルゲン除外の上書き" "アレルゲン除外を無視" "アレルゲン除外を解除"
   "certify the kitchen as safe" "certify kitchen as safe" "certify kitchen safe"
   "kitchen safety certification" "recertify the kitchen" "厨房を安全と認定" "厨房の安全認定"
   "food safety clearance" "food-safety clearance" "clear the kitchen for service"
   "clear the venue for service" "health department clearance" "health inspector sign-off"
   "regulatory clearance" "compliance clearance" "食品衛生許可" "保健所の認可" "衛生証明書発行"
   "recall order" "product recall decision" "issue a recall" "issue the recall"
   "食品回収命令" "リコール決定"
   "license suspension" "license-suspension" "permit revocation" "revoke the permit"
   "revoke the license" "営業許可取消" "許可証取消" "営業停止処分"
   "compliance enforcement" "compliance-enforcement" "investigat" "violation citation" "違反通知"])

;; ----------------------------- checks -----------------------------

(defn- order-unverified-violations
  "The target catering order must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:order-id` claim without a store lookup."
  [{:keys [order-id]} st]
  (let [o (store/order st order-id)]
    (when-not (and o (:registered? o) (:verified? o))
      [{:rule :order-unverified
        :detail (str order-id " は未登録または未検証の注文/イベント -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches directly finalizing a food-safety-
  authority decision (allergen-exclusion override, kitchen safety
  certification, regulatory clearance, recall, license suspension),
  regardless of confidence or how clean every other check is.
  Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "アレルゲン除外の上書き/食品安全当局の最終認定/営業許可の停止・取消・リコール決定等の判断領域に触れる提案は永久に禁止"}])))

(defn- high-cost-supply-order?
  "A `:coordinate-supply-order` proposal whose `:value :estimated-cost`
  exceeds `cost-threshold` -- always escalates, never auto-commits."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (> (get-in proposal [:value :estimated-cost] 0) cost-threshold)))

(defn check
  "Censors a CateringAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [order-id (or (:order-id proposal) (:order-id request))
        hard (into []
                   (concat (order-unverified-violations {:order-id order-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (or (boolean (always-escalate-ops (:op proposal)))
                    (high-cost-supply-order? proposal))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :order-id   (:order-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
