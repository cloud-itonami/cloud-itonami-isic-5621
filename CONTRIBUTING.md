# Contributing to cloud-itonami-isic-5621

Contributions should preserve the actor's scope: back-office
operations-coordination only, with CRITICAL exclusions of directly
finalizing food-safety-authority decisions and other regulatory/
compliance-enforcement authority (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: kbb -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Directly finalize a food-safety-authority decision (overriding an
  allergen-exclusion requirement, certifying a kitchen as safe
  post-incident).
- Issue a health-department/regulatory clearance.
- Make a product-recall decision.
- Suspend or revoke a food-service license, or take any other
  compliance-enforcement action.

Contributions that cross these boundaries will be rejected.
