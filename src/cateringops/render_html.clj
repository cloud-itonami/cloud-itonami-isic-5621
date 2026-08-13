(ns cateringops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: there was previously NO
  demo page and no generator at all. This namespace drives the REAL actor
  stack -- `cateringops.operation` (the langgraph StateGraph) ->
  `cateringops.governor` -> `cateringops.store` -- over the repo's own
  seeded catering-order directory (`cateringops.store/demo-data`:
  `order-1`, `order-2`, `order-3`), then renders whatever that run
  actually produced.

  Nothing on the page is hand-typed telemetry. Every order row comes from
  `store/all-orders`, every ledger row from `store/ledger`, every
  committed record from `store/coordination-log`, the phase table from
  `cateringops.phase/phases`, and the per-op action gate from
  `cateringops.governor/allowed-ops` + `always-escalate-ops` +
  `cost-threshold` + `confidence-floor` joined against the phase table.
  The approver-attribution section is *measured* against the store at
  render time (see `approver-probe`) rather than asserted, so it cannot
  turn into a stale claim if the store changes.

  Build-time invariant: `-main` THROWS unless the real governor produced
  at least one `:governor-hold` fact AND the run covered every HARD rule
  in `expected-hard-rules`. A console that silently renders zero holds
  would look identical to one where the governor stopped working.

  Deterministic: no timestamps, no randomness, sets are sorted before
  rendering -- two consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [cateringops.advisor :as advisor]
            [cateringops.governor :as governor]
            [cateringops.phase :as phase]
            [cateringops.store :as store]
            [cateringops.operation :as op]
            [langgraph.graph :as g]))

(def ^:private coordinator-p1
  "Phase 1 (assisted-logging) -- every write still needs a human."
  {:actor-id "coord-1" :actor-role :catering-coordinator :phase 1})

(def ^:private coordinator-p3
  "Phase 3 (supervised-auto) -- the repo's own `phase/default-phase`."
  {:actor-id "coord-1" :actor-role :catering-coordinator :phase 3})

(def expected-hard-rules
  "Every HARD rule `cateringops.governor` can emit. The scenario below
  must exercise all of them or `-main` refuses to write the page --
  otherwise a governor that quietly stopped firing would still produce a
  plausible-looking console."
  #{:order-unverified :effect-not-propose :scope-excluded :op-not-allowed})

;; ----------------------------- the real run -----------------------------

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid by]
  (g/run* actor {:approval {:status :approved :by by}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid by]
  (g/run* actor {:approval {:status :rejected :by by}}
          {:thread-id tid :resume? true}))

(defn- drifted-op-advisor
  "A compromised/confused advisor that keeps `:effect :propose` (so the
  effect check clears) but drafts an op OUTSIDE the closed four-op
  allowlist -- `:issue-health-department-clearance`, one of the decision
  areas `cateringops.governor`'s own docstring names as permanently out
  of scope. Exercises the `:op-not-allowed` HARD branch, which no other
  scenario reaches."
  []
  (reify advisor/Advisor
    (-advise [_ _store request]
      (assoc (advisor/infer nil request)
             :op :issue-health-department-clearance))))

(defn- direct-actuation-advisor
  "An advisor claiming to actuate directly instead of proposing --
  exercises the `:effect-not-propose` HARD branch (same shape the repo's
  own `cateringops.sim` uses)."
  []
  (reify advisor/Advisor
    (-advise [_ _store request]
      (assoc (advisor/infer nil request) :effect :commit))))

(defn run-demo!
  "Runs a fresh seeded store through a scenario that reaches every
  disposition this actor can produce, against the repo's OWN seeded
  orders:

    order-1 (registered + verified) -- a record log at phase 1 (the
      phase gate escalates even though the governor is clean; a human
      approves), the same op at phase 3 (auto-commits), an event
      schedule (auto-commits), a below-threshold supply order
      (auto-commits), an above-threshold supply order (the governor's
      own `high-cost-supply-order?` forces escalation regardless of
      phase; the procurement lead approves), a food-safety concern flag
      (ALWAYS escalates at every phase; approved), then three HARD
      holds: a direct-actuation advisor, a scope-drifted advisor, and an
      advisor drafting an op outside the closed allowlist.

    order-2 (registered + verified) -- an event schedule (auto-commits)
      and a food-safety concern flag the human REJECTS (the rejection
      path, not a governor hold).

    order-3 (registered but NOT verified in the seed) -- HARD hold.

    order-99 -- never in the order directory at all; the id the repo's
      own `cateringops.sim` uses for the unregistered case. HARD hold.

  Returns the store. Every value rendered below is read back out of it."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; --- order-1: phase gate forces approval even on a clean proposal
    (exec! actor "o1-log-p1"
           {:op :log-catering-order-record :order-id "order-1"
            :patch {:menu "seasonal tasting menu" :headcount 120
                    :allergens ["peanut" "shellfish"]}}
           coordinator-p1)
    (approve! actor "o1-log-p1" "catering-coordinator-1")

    ;; --- order-1: same op at phase 3, clean -> auto-commit
    (exec! actor "o1-log-p3"
           {:op :log-catering-order-record :order-id "order-1"
            :patch {:headcount 122 :notes "two additional vegan guests"}}
           coordinator-p3)

    (exec! actor "o1-schedule"
           {:op :schedule-catering-event :order-id "order-1"
            :patch {:prep-start "2026-07-19T06:00" :delivery "2026-07-19T11:00"
                    :venue "Grand Hall"}}
           coordinator-p3)

    (exec! actor "o1-supply-low"
           {:op :coordinate-supply-order :order-id "order-1"
            :patch {:item "chafing dishes" :quantity 20 :estimated-cost 800}}
           coordinator-p3)

    ;; --- order-1: above `governor/cost-threshold` -> always escalates
    (exec! actor "o1-supply-high"
           {:op :coordinate-supply-order :order-id "order-1"
            :patch {:item "premium seafood tower ingredients" :quantity 4
                    :estimated-cost 7500}}
           coordinator-p3)
    (approve! actor "o1-supply-high" "procurement-lead-1")

    ;; --- order-1: a concern flag never auto-commits at any phase
    (exec! actor "o1-safety-flag"
           {:op :flag-food-safety-concern :order-id "order-1"
            :patch {:concern "shellfish garnish observed on a dish tagged shellfish-free"
                    :confidence 0.92}}
           coordinator-p3)
    (approve! actor "o1-safety-flag" "catering-coordinator-1")

    ;; --- order-2: clean auto-commit
    (exec! actor "o2-schedule"
           {:op :schedule-catering-event :order-id "order-2"
            :patch {:prep-start "2026-12-18T14:00" :delivery "2026-12-18T18:00"
                    :venue "Acme Corp atrium"}}
           coordinator-p3)

    ;; --- order-2: escalated concern flag the human REJECTS
    (exec! actor "o2-safety-flag"
           {:op :flag-food-safety-concern :order-id "order-2"
            :patch {:concern "hot-hold line held below 63C for an unrecorded interval"
                    :confidence 0.71}}
           coordinator-p3)
    (reject! actor "o2-safety-flag" "catering-coordinator-1")

    ;; --- HARD: order-3 is registered but not verified
    (exec! actor "o3-log"
           {:op :log-catering-order-record :order-id "order-3"
            :patch {:menu "buffet"}}
           coordinator-p3)

    ;; --- HARD: order-99 is not in the directory at all
    (exec! actor "o99-log"
           {:op :log-catering-order-record :order-id "order-99"
            :patch {:menu "unknown"}}
           coordinator-p3)

    ;; --- HARD: advisor claims a direct actuation
    (exec! (op/build db {:advisor (direct-actuation-advisor)}) "o1-direct"
           {:op :schedule-catering-event :order-id "order-1"
            :patch {:venue "Riverside Pavilion"}}
           coordinator-p3)

    ;; --- HARD: advisor drifts into food-safety-authority scope
    (exec! actor "o1-scope-drift"
           {:op :log-catering-order-record :order-id "order-1"
            :out-of-scope? true :patch {}}
           coordinator-p3)

    ;; --- HARD: advisor drafts an op outside the closed allowlist
    (exec! (op/build db {:advisor (drifted-op-advisor)}) "o1-op-drift"
           {:op :flag-food-safety-concern :order-id "order-1"
            :patch {:concern "kitchen reopening after a deep clean"}}
           coordinator-p3)
    db))

;; ----------------------------- measurement -----------------------------

(defn approver-probe
  "MEASURES, against the store this run actually produced, whether an
  approving human's identity survives into the committed record.

  This fleet has repos whose `commit-record!` destructures `:value` and
  never reads `:payload`, silently dropping the approver. Whether THIS
  repo does is not assumed here -- it is read back out of
  `store/coordination-log` and reported. A hardcoded claim would become
  a lie the day the store changes.

  Returns {:granted n :retained n :paths [[k k] ..] :approvers [..]}."
  [db]
  (let [granted (filter #(= :approval-granted (:t %)) (store/ledger db))
        recs (store/coordination-log db)
        paths [[:value :approved-by] [:payload :approved-by]]
        hits (for [r recs
                   p paths
                   :when (get-in r p)]
               [p (get-in r p)])]
    {:granted   (count granted)
     :retained  (count (distinct (map (fn [r] (some #(when (get-in r %) r) paths))
                                      (filter (fn [r] (some #(get-in r %) paths)) recs))))
     :paths     (vec (sort-by str (distinct (map first hits))))
     :approvers (vec (sort (distinct (map second hits))))
     :from-audit (vec (sort (distinct (keep :by granted))))}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [x] (if (keyword? x) (name x) (str x)))

(defn- basis-str [basis]
  (str/join ", " (map kw-str basis)))

(defn- keys-str [m]
  (str/join " " (map kw-str (sort (map kw-str (keys m))))))

(defn- last-fact-for [ledger order-id]
  (last (filter #(= (:order-id %) order-id) ledger)))

(defn- status-cell [ledger order-id]
  (let [f (last-fact-for ledger order-id)]
    (case (:t f)
      nil                 "<span class=\"muted\">no activity this run</span>"
      :committed          "<span class=\"ok\">committed</span>"
      :governor-hold      (str "<span class=\"critical\">HARD hold &middot; "
                               (esc (basis-str (:basis f))) "</span>")
      :approval-rejected  "<span class=\"err\">approval rejected &middot; held</span>"
      (str "<span class=\"muted\">" (esc (kw-str (:t f))) "</span>"))))

(defn- order-row [ledger {:keys [order-id client registered? verified?]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc order-id) (esc client)
          (if registered? "<span class=\"ok\">registered</span>"
              "<span class=\"critical\">not registered</span>")
          (if verified? "<span class=\"ok\">verified</span>"
              "<span class=\"warn\">not yet verified</span>")
          (status-cell ledger order-id)))

;; --- phase table, derived from `cateringops.phase/phases` ---

(defn- ops-cell [ops]
  (if (empty? ops)
    "<span class=\"muted\">none</span>"
    (str/join " " (map #(str "<code>:" (esc (name %)) "</code>") (sort-by name ops)))))

(defn- phase-row [[ph {:keys [label writes auto]}]]
  (format "        <tr><td>%s%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          ph
          (if (= ph phase/default-phase) " <span class=\"badge\">default</span>" "")
          (esc label) (ops-cell writes) (ops-cell auto)))

;; --- action gate, derived from the governor + the phase table ---

(defn- first-phase-where [pred]
  (first (for [[ph cfg] (sort-by key phase/phases) :when (pred cfg)] ph)))

(defn- gate-note [op]
  (str/join " &middot; "
            (cond-> []
              (contains? governor/always-escalate-ops op)
              (conj "<span class=\"warn\">ALWAYS escalates &mdash; never auto at any phase</span>")

              (= :coordinate-supply-order op)
              (conj (str "<span class=\"warn\">escalates above :estimated-cost "
                         governor/cost-threshold "</span>"))

              true
              (conj (str "confidence floor " governor/confidence-floor)))))

(defn- action-gate-row [op]
  (let [write-at (first-phase-where #(contains? (:writes %) op))
        auto-at  (first-phase-where #(contains? (:auto %) op))]
    (format "        <tr><td><code>:%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc (name op))
            (if write-at (str "phase " write-at "+") "<span class=\"critical\">never</span>")
            (if auto-at
              (str "<span class=\"ok\">phase " auto-at "+</span>")
              "<span class=\"critical\">never</span>")
            (gate-note op))))

;; --- committed coordination records ---

(defn- record-row [{:keys [op order-id value payload]}]
  (let [approver (or (:approved-by payload) (:approved-by value))]
    (format "        <tr><td><code>:%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
            (esc (name op)) (esc order-id)
            (esc (keys-str (dissoc value :order-id)))
            (if approver
              (str "<span class=\"ok\">" (esc approver) "</span>")
              "<span class=\"muted\">auto-committed (no approval step)</span>"))))

;; --- HARD holds ---

(defn- hold-row [{:keys [op order-id violations confidence]}]
  (str/join "\n"
            (for [{:keys [rule detail]} violations]
              (format "        <tr><td><code>%s</code></td><td><code>:%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
                      (esc (kw-str rule)) (esc (name op)) (esc order-id)
                      (esc detail) (esc confidence)))))

;; --- audit ledger ---

(defn- ledger-row [{:keys [t op order-id disposition basis by]}]
  (format "        <tr><td>%s</td><td><code>:%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw-str t)) (esc (name (or op :n-a))) (esc order-id)
          (esc (kw-str (or disposition "")))
          (esc (or by (basis-str basis)))))

(defn- approver-note
  "Renders the MEASURED approver-attribution finding. The wording is
  chosen from what `approver-probe` actually observed, so it stays true
  if the store's retention behaviour changes."
  [{:keys [granted retained paths approvers from-audit]}]
  (cond
    (zero? granted)
    "<span class=\"muted\">No approval was granted in this run, so retention could not be measured.</span>"

    (zero? retained)
    (str "<span class=\"critical\">The approver identity is NOT retained in the committed record.</span> "
         "The store keeps no <code>:approved-by</code> on any committed record, so the names below are "
         "<strong>audit only &mdash; not retained in record</strong>, recovered by joining the "
         "<code>:approval-granted</code> ledger facts: "
         (esc (str/join ", " from-audit)) ".")

    (< retained granted)
    (str "<span class=\"warn\">Partially retained.</span> " granted
         " approvals were granted but only " retained
         " committed records carry the approver (at "
         (str/join ", " (map #(str "<code>" (esc (pr-str %)) "</code>") paths))
         "). The remainder are <strong>audit only &mdash; not retained in record</strong>.")

    :else
    (str "<span class=\"ok\">Retained.</span> All " granted
         " approvals granted in this run are recoverable from the committed record itself, at "
         (str/join ", " (map #(str "<code>" (esc (pr-str %)) "</code>") paths))
         ". Note this is a property of <code>cateringops.store/MemStore</code>, which conj&#39;s the "
         "<em>whole</em> record &mdash; the approver rides on <code>:payload</code>, not on "
         "<code>:value</code>, so a store implementation that destructured only <code>:value</code> "
         "would drop it silently.")))

(defn- probe-rows [{:keys [granted retained paths approvers from-audit]}]
  (str/join
   "\n"
   [(format "        <tr><td>%s</td><td>%s</td></tr>"
            "<code>:approval-granted</code> ledger facts this run" granted)
    (format "        <tr><td>%s</td><td>%s</td></tr>"
            "committed records carrying an approver" retained)
    (format "        <tr><td>%s</td><td>%s</td></tr>"
            "key path(s) the approver was found at"
            (if (seq paths)
              (str/join ", " (map #(str "<code>" (esc (pr-str %)) "</code>") paths))
              "<span class=\"critical\">none</span>"))
    (format "        <tr><td>%s</td><td>%s</td></tr>"
            "approvers named in the record" (esc (str/join ", " approvers)))
    (format "        <tr><td>%s</td><td>%s</td></tr>"
            "approvers named in the audit ledger" (esc (str/join ", " from-audit)))]))

(defn render
  "Renders the whole document from a store `db` that has already been
  through `run-demo!`."
  [db]
  (let [ledger  (vec (store/ledger db))
        orders  (store/all-orders db)
        recs    (vec (store/coordination-log db))
        holds   (filterv #(= :governor-hold (:t %)) ledger)
        probe   (approver-probe db)]
    (str
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-5621 &middot; event catering operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Event catering &amp; other food service (ISIC 5621) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · food-safety-authority decisions permanently out of scope</span>\n"
     "</header>\n"
     "<main>\n"

     ;; 1. orders
     "  <section class=\"card\">\n"
     "    <h2>Catering orders (SSoT directory)</h2>\n"
     "    <p class=\"muted\">Build-time-generated from <code>cateringops.store</code> by <code>cateringops.render-html</code> (<code>clojure -M:dev:render-html</code>). A proposal for an order that is not both <em>registered</em> and <em>verified</em> is HARD-held before it can commit or even escalate — re-derived from the order&#39;s own record, never from the proposal&#39;s claim.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Order</th><th>Client</th><th>Registered</th><th>Verified</th><th>Last disposition this run</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial order-row ledger) orders)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; 2. phases
     "  <section class=\"card\">\n"
     "    <h2>Rollout phase gate</h2>\n"
     "    <p class=\"muted\">Read out of <code>cateringops.phase/phases</code>. The phase gate can only add caution: it may turn a governor <em>commit</em> into an approval request, never the other way round.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Phase</th><th>Label</th><th>May write</th><th>May auto-commit when governor-clean</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map phase-row (sort-by key phase/phases))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; 3. action gate
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Catering Governor × phase)</h2>\n"
     "    <p class=\"muted\">Derived by joining <code>cateringops.governor</code>&#39;s closed op allowlist, its <code>always-escalate-ops</code> set and its cost threshold against the phase table above. HARD holds are un-overridable — no human approval can release one.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Writable from</th><th>Auto-committable from</th><th>Standing gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map action-gate-row (sort-by name governor/allowed-ops))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; 4. committed records
     "  <section class=\"card\">\n"
     "    <h2>Committed coordination records</h2>\n"
     "    <p class=\"muted\">Everything <code>store/commit-record!</code> accepted this run — the only writes that reached the SSoT.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Order</th><th>Record fields</th><th>Approved by</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map record-row recs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; 5. approver attribution (measured)
     "  <section class=\"card\">\n"
     "    <h2>Approver attribution (measured at build time)</h2>\n"
     "    <p class=\"muted\">Measured, not asserted: <code>render-html/approver-probe</code> reads the store back and reports whether an approving human&#39;s identity survives into the committed record, so a reader can tell &quot;nobody approved&quot; apart from &quot;the store dropped it&quot;.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Observation</th><th>Value</th></tr></thead>\n"
     "      <tbody>\n"
     (probe-rows probe) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <p>" (approver-note probe) "</p>\n"
     "  </section>\n"

     ;; 6. HARD holds
     "  <section class=\"card\">\n"
     "    <h2>HARD governor holds this run</h2>\n"
     "    <p class=\"muted\">"
     (count holds)
     " proposal(s) were stopped by the Catering Governor and never reached a human. These are permanent, un-overridable blocks — a HARD violation cannot be approved away.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Requested op</th><th>Order</th><th>Detail (from the governor)</th><th>Advisor confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hold-row holds)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; 7. ledger
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">The append-only decision-fact log exactly as <code>store/ledger</code> returns it — every commit, hold and rejection, in order.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Order</th><th>Disposition</th><th>Basis / approver</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer><p class=\"muted\">Generated by <code>cateringops.render-html</code> from a real "
     "<code>cateringops.operation</code> run — "
     (count ledger) " ledger facts, " (count recs) " committed records, "
     (count holds) " HARD holds. No hand-written rows.</p></footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        ledger (store/ledger db)
        holds (filter #(= :governor-hold (:t %)) ledger)
        rules (set (mapcat :basis holds))]
    ;; Build-time invariants, not comments. A console rendered from a
    ;; governor that stopped firing would look perfectly plausible.
    (when (empty? holds)
      (throw (ex-info "REFUSING to write the console: the real governor produced ZERO :governor-hold facts. Either the scenario stopped exercising the HARD checks or the governor is broken."
                      {:ledger-facts (count ledger)})))
    (when-not (every? rules expected-hard-rules)
      (throw (ex-info "REFUSING to write the console: the run did not exercise every HARD governor rule."
                      {:expected expected-hard-rules
                       :observed rules
                       :missing (remove rules expected-hard-rules)})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p)))
    (spit out (render db))
    (println "wrote" out
             "-" (count ledger) "ledger facts,"
             (count (store/coordination-log db)) "committed records,"
             (count holds) "HARD holds covering" (sort (map name rules)))))
