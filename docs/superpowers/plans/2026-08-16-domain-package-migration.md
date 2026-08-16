# Domain Package Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every tool and its domain models out of the mixed `misc` package into the approved `feature/*` domains without changing stable IDs or user-visible behavior.

**Architecture:** Each domain uses `ui`, `application`, and `domain` subpackages only where needed. Package moves are mechanical first; responsibility extraction belongs to the infrastructure sub-plan. `MainFrame` and `ToolRegistry` remain the only entry-point assembly locations.

**Tech Stack:** Java 8, Swing, existing panel classes and shared `ui.kit` components.

---

## Move map

| Target package | Existing files |
|---|---|
| `feature.security.ui` | `CryptoPanel`, `SymmetricPanel`, `AsymmetricPanel`, `CertPanel`, `AccountManagerPanel`, `TotpPanel`, `JwtPanel` |
| `feature.security.domain` | `RSAUtils`, `SM2Utils`, `SM3Utils`, `SM4Utils`, `SymmetricUtils`, `OtpUtils`, certificate/ACME helpers |
| `feature.codec.ui` | `ConvertPanel`, `TimePanel`, `Base64ImagePanel`, `UrlToolPanel`, `FormatConvertPanel`, `JsonPanel`, `XmlPanel`, `SqlPanel`, `StringToolPanel`, `RegexPanel`, `TextDiffPanel` |
| `feature.codec.domain` | `JsonFormatter` and pure format/parser helpers |
| `feature.network.ui` | `HttpTestPanel`, `CallbackTestPanel`, `WebSocketClientPanel`, `MqttClientPanel`, `SubnetPanel`, `PortScannerPanel`, `SshClientPanel` |
| `feature.data.ui` | `DatabasePanel`, `RedisPanel`, `KafkaPanel`, `ZooKeeperPanel` |
| `feature.cloud.ui` | `DockerComposePanel`, `K8sPanel`, `K8sManagerPanel` |
| `feature.system.ui` | `ChmodPanel`, `CronPanel`, `HostsManagerPanel`, `WeChatPanel` |
| `feature.system.domain` | `WeChatContactReader` and local automation adapters |
| `feature.generation.ui` | `DataGeneratorPanel`, `QrCodePanel`, `ColorPanel` |
| `feature.compute.ui` | `CalculatorPanel`, `StatisticsPanel`, `SortPanel`, `SearchPanel`, `HanoiPanel`, `PinGamePanel` |
| `feature.diagram.ui` | `BpmnPanel`, `MermaidPanel`, `FlowchartPanel`, `CanvasPanel` |
| `feature.diagram.domain` | `FlowNode`, `FlowEdge`, `FlowchartDataDto` |
| `feature.monitor.ui` | `VideoMonitorPanel`, `RemoteDesktopPanel` |
| `feature.monitor.domain` | remote-session state and protocol models |

## Mechanical migration

- [ ] **Step 1: Move security files**

Use `git mv` for the seven security panels and crypto/ACME helpers in the move map. Do not alter method bodies.

- [ ] **Step 2: Move codec files**

Use `git mv` for the eleven codec panels and pure formatting helpers. Do not alter method bodies.

- [ ] **Step 3: Move network files**

Use `git mv` for HTTP, callback, WebSocket, MQTT, subnet, port-scanner, SSH, and their feature-owned models. Do not alter method bodies.

- [ ] **Step 4: Move data and cloud files**

Use `git mv` for database, Redis, Kafka, ZooKeeper, Docker Compose, and K8s panels. Do not alter method bodies.

- [ ] **Step 5: Move system and generation files**

Use `git mv` for Chmod, Cron, Hosts, WeChat, random-data, QR, and color panels and their local models. Do not alter method bodies.

- [ ] **Step 6: Move compute, diagram, and monitor files**

Use `git mv` for calculator/algorithm panels, diagram panels/models, and monitor/remote-session panels. Do not alter method bodies.

- [ ] **Step 7: Update package declarations and imports**

For every moved file, update the `package` declaration first, then replace imports using the target package. Keep shared `com.aqishi.toolbox.ui.*`, `ui.kit.*`, `support.*`, and `catalog.*` imports explicit; do not introduce wildcard imports.

- [ ] **Step 8: Move domain-only models before their consumers**

Move `FlowNode`, `FlowEdge`, and `FlowchartDataDto` before `FlowchartPanel`; move `WeChatContactReader` before `WeChatPanel`; move crypto and parser helpers before their UI panels. This keeps each intermediate compile failure local to a known missing import.

- [ ] **Step 9: Update descriptor constructor calls**

Replace each panel's legacy constructor call with its `ToolCatalog` descriptor, preserving the existing keyword strings. For example:

```java
public PinGamePanel() {
    super(ToolCatalog.PINGAME);
    loadSavedStats();
}
```

- [ ] **Step 10: Update non-panel consumers**

Search the repository for every old fully qualified name and import. Update tests, resource loaders, `MainFrame`, documentation examples, and reflection strings. Do not change stable IDs or configuration keys.

## Domain boundaries

- [ ] **Step 11: Isolate pure logic in codec and compute domains**

Keep JSON formatting, URL parsing, statistics, sorting, searching, Hanoi state, and Pin Game math free of Swing imports. Move only methods that have no component access; leave event wiring in `ui`.

- [ ] **Step 12: Isolate diagram models**

Make diagram model fields private where existing serialization permits, expose named accessors, and document coordinates, port numbering, routing type, and snapshot mutability. Preserve serialized property names.

- [ ] **Step 13: Isolate system automation boundaries**

Keep Windows Hosts and WeChat UIAutomation calls behind `feature.system.application` interfaces. The UI panels must depend on interfaces, not on `java.awt.Robot`, SQLite paths, or process invocation details.

- [ ] **Step 14: Compile after each domain**

After each domain move run:

```bash
mvn -Dmaven.test.skip=true package
git diff --check
```

Expected: exit code 0 and no old package import remains for the migrated domain. Do not run `mvn test`.

- [ ] **Step 15: Remove the obsolete misc package**

Once `rg -n "package com\.aqishi\.toolbox\.misc(;|\.)" src/main/java` returns only intentionally retained compatibility adapters, delete empty directories and update README paths.

- [ ] **Step 16: Commit the domain checkpoint**

```bash
git add src/main/java src/test/java README.md
git commit -m "refactor: organize tools by domain packages"
```
