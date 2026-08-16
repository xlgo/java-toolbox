# Java Toolbox Full Architecture Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the 50-tool Java Toolbox from the mixed `misc`/hard-coded registration structure to domain packages, a centralized catalog, separated infrastructure services, corrected internationalization, and maintained project documentation without changing stable user data contracts.

**Architecture:** Execute five independent sub-plans in dependency order: catalog and category migration, domain package migration, infrastructure decomposition, documentation/encoding cleanup, and feature-roadmap publication. Each domain remains compilable before the next one starts; panels become thin UI adapters over application services and infrastructure ports.

**Tech Stack:** Java 8 source/target, Swing, FlatLaf, Maven Shade, existing JUnit/resource layout (not executed in this work), UTF-8 properties, Markdown documentation.

---

## Scope and constraints

- Preserve all 50 stable tool IDs, configuration keys, vault formats, connection profiles, and existing `tool.*`/domain message keys.
- Add new category keys while keeping old `group.*` keys as migration aliases.
- Do not run functional tests. Use main-source compilation, package/import scans, resource-key scans, and `git diff --check` only.
- Keep the already prepared `tool.pingame` entries in the default and Chinese resources and the existing resource-contract test; do not execute that test.
- Do not implement P0/P1/P2 roadmap features in this rewrite.

## Sub-plan map

| Order | Plan | Owns |
|---|---|---|
| 1 | `2026-08-16-tool-catalog-category-migration.md` | `catalog`, stable descriptors, registry, category IDs, navigation migration |
| 2 | `2026-08-16-domain-package-migration.md` | `feature/*` package moves and panel/model package declarations |
| 3 | `2026-08-16-infrastructure-decomposition.md` | K8s/Kafka/database/SSH/Redis/remote services and resource lifecycles |
| 4 | `2026-08-16-documentation-i18n-encoding.md` | comments, BOM/line endings, POM/README, duplicate script source |
| 5 | `2026-08-16-feature-roadmap.md` | prioritized future-feature list and architecture dependencies |

The catalog plan covers the design's target architecture, registry, category table, stable IDs, and navigation migration. The domain plan covers every package move and model boundary. The infrastructure plan covers the dependency direction and all large-panel extractions. The documentation plan covers comments, encoding, resources, README, and script ownership. The roadmap plan covers every P0/P1/P2 proposal and its release dependency.

## Shared execution protocol

For every sub-plan:

1. Inspect the listed files and record the current import/package paths.
2. Apply only the task's changes; do not mix roadmap implementation or behavior changes.
3. Run the task's static command and inspect the full exit code/output.
4. Run `git diff --check`.
5. Commit the task checkpoint with the message specified in the sub-plan.

The only build command permitted for this request is:

```bash
mvn -Dmaven.test.skip=true package
```

Expected result: Maven exits with code 0, compiles main sources, and creates `target/java-toolbox.jar` without executing or compiling tests.

## Final handoff checklist

- [ ] All five sub-plans completed in order.
- [ ] `MainFrame` has no direct 50-panel Supplier array.
- [ ] No production class remains in the catch-all `misc` package except explicitly retained compatibility adapters.
- [ ] All 50 stable IDs resolve through `ToolRegistry` and map to exactly one new category.
- [ ] Old navigation state is migrated once and new category labels exist in default/Chinese/English resources.
- [ ] POM and resource files contain no detected mojibake, BOM, or inconsistent line endings.
- [ ] README, architecture docs, and roadmap match the source tree.
- [ ] Main-source package build succeeds with `maven.test.skip=true`.
- [ ] Final report explicitly states that functional tests were not run and which external-service paths remain unverified.
