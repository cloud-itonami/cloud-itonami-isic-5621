(ns cateringops.advisor
  "CateringAdvisor -- the *contained intelligence node* for the
  ISIC-5621 event-catering operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: catering order/event record logging, event scheduling,
  supply-order coordination, and food-safety-concern flagging.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a *proposal*
  (with a rationale + the fields it cited), never a committed record
  and NEVER a direct actuation -- every proposal's `:effect` is always
  `:propose`. Every output is censored downstream by `cateringops.governor`
  before anything touches the SSoT.

  This advisor NEVER drafts a proposal that directly finalizes a
  food-safety-authority decision (overriding an allergen-exclusion
  requirement, certifying a kitchen as safe post-incident, issuing a
  health-department/regulatory clearance, a recall decision, or a
  license suspension/revocation) -- those are permanently out of scope
  for this actor, not merely un-implemented. `cateringops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal
  for exactly this failure mode (a compromised or confused advisor
  drifting into scope it must never touch) and HARD-holds it,
  regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :order-id   str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-order-record
  "Draft a catering order/event data-record log entry (menu, headcount,
  allergen flags). Pure logging of order data -- never a food-safety-
  authority decision."
  [_db {:keys [order-id patch]}]
  {:op         :log-catering-order-record
   :order-id   order-id
   :summary    (str order-id " のケータリング注文記録を更新: " (pr-str (keys patch)))
   :rationale  "イベント/メニュー/人数/アレルギー表示の記録のみ。食品安全当局の最終判断は含まない。"
   :cites      [order-id]
   :effect     :propose
   :value      (merge {:order-id order-id} patch)
   :confidence 0.93})

(defn- propose-event-schedule
  "Draft a prep/staging/delivery scheduling proposal for a catering
  event (a calendar entry, never a direct dispatch)."
  [_db {:keys [order-id patch]}]
  {:op         :schedule-catering-event
   :order-id   order-id
   :summary    (str order-id " の仕込み/搬入/配達スケジュールを提案: " (pr-str (keys patch)))
   :rationale  "仕込み・搬入・配達のスケジュール調整提案のみ。実施決定はケータリングマネージャーが行う。"
   :cites      [order-id]
   :effect     :propose
   :value      (merge {:order-id order-id} patch)
   :confidence 0.88})

(defn- propose-supply-order
  "Draft an ingredient/equipment procurement coordination proposal
  (never a finalized purchase order)."
  [_db {:keys [order-id patch]}]
  {:op         :coordinate-supply-order
   :order-id   order-id
   :summary    (str order-id " に関連する食材/什器の調達を提案: " (pr-str (keys patch)))
   :rationale  "食材・什器などの調達調整提案のみ。確定発注は人間の調達担当者が判断する。"
   :cites      [order-id]
   :effect     :propose
   :value      (merge {:order-id order-id} patch)
   :confidence 0.90})

(defn- propose-food-safety-concern
  "Surface a food-safety concern (allergen mismatch, temperature abuse,
  contamination) for HUMAN triage. This op ALWAYS escalates in
  `cateringops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real."
  [_db {:keys [order-id patch]}]
  {:op         :flag-food-safety-concern
   :order-id   order-id
   :summary    (str order-id " の食品安全懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "アレルゲン不一致・温度逸脱・汚染などの観察事実の報告。常に人間の確認・対応が必要。"
   :cites      [order-id]
   :effect     :propose
   :value      (merge {:order-id order-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-catering-order-record (propose-order-record _db request)
                   :schedule-catering-event (propose-event-schedule _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   :flag-food-safety-concern (propose-food-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually decided to override the allergen exclusion and certify the kitchen as safe")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :order-id (:order-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
