# Feature Roadmap Publication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a prioritized, architecture-aware feature list without implementing new runtime functionality during the rewrite.

**Architecture:** The roadmap maps every proposal to a target domain and records dependencies, scope boundaries, and a first release slice. P0 items reuse existing panels/services; P1 items wait for shared network/security abstractions; P2 items wait for catalog/plugin/workspace boundaries.

**Tech Stack:** Markdown documentation and existing domain vocabulary only.

---

## Files

- Create: `docs/feature-roadmap.md` — prioritized feature list, domain, dependencies, and acceptance notes.
- Modify: `README.md` — link to the roadmap and summarize P0.
- Modify: `DESIGN.md` — add the roadmap ownership rule and extension boundary.

## Roadmap content

- [ ] **Step 1: Write P0 items**

Document these eight items with a short scope and dependency list: OpenAPI/Swagger workspace; JSONPath/JMESPath query; YAML/TOML/INI conversion; file digest/signature verification; CSR/PKCS#12/certificate-chain checks; DNS/TLS/HTTP diagnostics; log viewer/filter; workspace import/export.

- [ ] **Step 2: Write P1 items**

Document GraphQL, gRPC, OAuth2/OIDC/JWK, Git helper, Docker/K8s diff and port-forward, XPath/XSLT, webhook signature verification, and Cron visual editing/conflict detection.

- [ ] **Step 3: Write P2 items**

Document plugin SDK/directory, automation macros, OpenTelemetry trace viewer, OS keychain integration, configuration history/diff recovery, and multi-window/tab workspace.

- [ ] **Step 4: Record prioritization rules**

State that P0 is evaluated after the catalog and infrastructure layers compile; P1 requires shared network/security services; P2 requires stable plugin/workspace contracts. No roadmap item may add a new tool before its descriptor, category, localization, lifecycle, and documentation entries are defined.

- [ ] **Step 5: Link the roadmap**

Add a `## Roadmap` section to README and a short extension-policy paragraph to `DESIGN.md`. Keep the roadmap explicitly non-committal about release dates and external service availability.

## Static verification and checkpoint

- [ ] **Step 6: Check roadmap coverage**

Use `rg -n "OpenAPI|JSONPath|YAML|CSR|DNS|日志|GraphQL|gRPC|插件" docs/feature-roadmap.md README.md` and manually confirm all 22 proposals appear exactly once in the roadmap and are linked from README.

- [ ] **Step 7: Commit the roadmap checkpoint**

```bash
git diff --check
git add docs/feature-roadmap.md README.md DESIGN.md
git commit -m "docs: publish prioritized feature roadmap"
```

