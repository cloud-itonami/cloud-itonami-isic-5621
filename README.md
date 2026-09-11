# cloud-itonami-isic-5621

**Event Catering** — ISIC Rev.4 class 5621.

An operations-coordination-only actor for event-catering businesses, behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: `log-catering-order-record`, `schedule-catering-event`, `coordinate-supply-order`, `flag-food-safety-concern` (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Order verified** — target catering order/event record must exist AND be registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — directly finalizing a food-safety-authority decision (overriding an allergen-exclusion requirement, certifying a kitchen as safe post-incident), health-department/regulatory clearance, a product-recall decision, and food-service license suspension/revocation are permanently blocked.
- **Two ALWAYS-ESCALATE gates**, per this fleet's Wave 4 person-facing-service guardrail (ADR-2607152500) — neither is ever auto-commit-eligible, in any phase:
  - `flag-food-safety-concern` — surfacing an allergen-mismatch/temperature-abuse/contamination concern always needs a human to look at it.
  - `coordinate-supply-order` above the cost threshold (5000) — a high-value procurement commitment always needs a human's sign-off.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: catering order-record logging only (approval-gated)
  - Phase 2: + event scheduling, supply-order coordination (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, below-threshold proposals (food-safety concerns and high-cost supply orders always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## Scope

This actor coordinates the back-office operations of an event-catering business: catering order/event record logging (menu, headcount, allergen flags), prep/staging/delivery event scheduling, ingredient/equipment supply-order coordination, and food-safety-concern flagging.

**It NEVER performs or authorizes:**
- Directly finalizing a food-safety-authority decision — overriding an allergen-exclusion requirement, or certifying a kitchen as safe post-incident.
- Issuing a health-department/regulatory clearance.
- A product-recall decision.
- A food-service license suspension/revocation or other compliance-enforcement action.

Contributions that cross these boundaries will be rejected — see CONTRIBUTING.md.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
kbb -M:dev -P

# Run tests
kbb -M:test

# Run linter
kbb -M:lint

# Run demo
kbb -M:run
```

## Test suite

- `test/cateringops/governor_test.cljk` — unit tests of governor hard checks and scope exclusion
- `test/cateringops/advisor_test.cljk` — advisor proposal shape and consistency
- `test/cateringops/phase_test.cljk` — rollout phase logic
- `test/cateringops/governor_contract_test.cljk` — full graph integration, audit trail
- `test/cateringops/store_contract_test.cljk` — Store protocol and MemStore implementation

## Modules

- `cateringops.store` — SSoT (MemStore, String-keyed catering-order directory, append-only ledger)
- `cateringops.advisor` — contained intelligence node (mock + real-LLM seam)
- `cateringops.governor` — independent compliance layer
- `cateringops.phase` — staged rollout (0→3)
- `cateringops.operation` — langgraph-clj StateGraph
- `cateringops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-facing/personal-services) fleet. See ADR-2607121000 (Wave 3 reverse-toposort rollout plan), ADR-2607152500 (Wave 4 authorization + person-facing-service safety guardrail), and ADR-2616562100 (this actor's own coverage record) for design decisions.
