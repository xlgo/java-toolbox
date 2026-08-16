# Documentation, Internationalization, and Encoding Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct source/documentation encoding, establish consistent comments, remove duplicate documentation and script sources, and make all navigation labels complete in the three supported resource files.

**Architecture:** Keep runtime resource loading unchanged while normalizing resource bytes and keys. Use one canonical script source and a deterministic build copy. Documentation describes the new catalog/domain tree rather than listing stale implementation paths.

**Tech Stack:** UTF-8 without BOM, Maven resources, Markdown, Java Javadoc, PowerShell/static scans.

---

## Encoding and resources

- [ ] **Step 1: Replace POM mojibake**

Update `pom.xml` description and comments at the existing dependency/plugin locations to valid UTF-8 Chinese:

```xml
<description>桌面工具箱：算法、加密、格式转换、计算、SSH远程终端与代理等</description>
<!-- FlatLaf 外观包：现代扁平风格，自带 Light/Dark/IntelliJ/Darcula/macOS 等核心主题 -->
<!-- FlatLaf 扩展：提供切换动画 FlatAnimatedLafChange -->
<!-- FlatLaf IntelliJ 主题包：数十种 IDE 风格主题 -->
<!-- BouncyCastle：国密 SM2/SM3/SM4 算法支持 -->
<!-- BouncyCastle PKIX/CMS：X.509 证书生成、解析、PEM 编解码 -->
<!-- JUnit 5 测试框架 -->
<!-- Java-WebSocket：用于 K8s 容器 Exec 的 WebSocket 连接 -->
<!-- 打包 fat jar，双击即可运行 -->
```

- [ ] **Step 2: Normalize properties bytes**

Rewrite `messages.properties`, `messages_zh_CN.properties`, and `messages_en_US.properties` as UTF-8 without BOM with LF line endings. Preserve every existing key/value and retain `tool.pingame=见缝插针` in default/Chinese and `tool.pingame=Pin Game` in English.

- [ ] **Step 3: Add the ten category labels**

Add `group.security`, `group.codec`, `group.network`, `group.data`, `group.cloud`, `group.system`, `group.generation`, `group.compute`, `group.diagram`, and `group.monitor` to all three files. Keep old group keys as aliases during migration.

- [ ] **Step 4: Fix user-visible fallback text**

In `feature/generation/ui/RandomNumberPanel.java` (moved path), replace the unknown-field `"???"` fallback with the original field name plus a localized validation message. Do not hide an invalid field by replacing it with punctuation.

## Comments and contracts

- [ ] **Step 5: Document navigation contracts**

Add class-level Javadocs to `ToolContentHost`, `ToolNavigationModel`, `ToolNavigationState`, and `ToolSidebar`. State stable-ID uniqueness, group order, lazy mounting, filtering behavior, and EDT requirements.

- [ ] **Step 6: Document diagram contracts**

Add Javadocs to `FlowNode`, `FlowEdge`, and `FlowchartDataDto` describing coordinate units, port numbering, routing type values, mutable fields, and serialized property names.

- [ ] **Step 7: Document vault lifecycle contracts**

Add Javadocs to `VaultClock`, `VaultListener`, `VaultScheduler`, and `VaultState` describing legal state transitions, callback thread, cancellation, and close semantics.

- [ ] **Step 8: Normalize comment language and remove duplicates**

While migrating files, remove duplicate `enableEvents(...)` comments in `CanvasPanel`, obsolete compatibility comments, and comments that merely repeat the next line. Keep comments that document protocol, security, thread, or migration constraints.

## Script and README structure

- [ ] **Step 9: Choose the canonical WeChat script source**

Treat `tools/wechat_export.py` as the editable source. Configure `maven-resources-plugin` in `pom.xml` to copy it to `target/classes/tools/wechat_export.py` during `process-resources`; remove the duplicate tracked copy under `src/main/resources/tools` only after verifying all runtime resource lookups use the packaged path.

- [ ] **Step 10: Rewrite README once**

Keep one canonical `README.md` sequence: overview, complete 50-entry feature table, screenshots, category navigation, security vault, run/build commands, architecture tree, guides, release workflow, and license. Remove the duplicated run/build/technology/project-tree block and update the release example from `v1.5.2` to a version-neutral `vX.Y.Z`.

- [ ] **Step 11: Add architecture and roadmap links**

Link `docs/superpowers/specs/2026-08-16-full-architecture-rewrite-design.md`, the final architecture overview, and `docs/feature-roadmap.md` from README. Update package examples to `catalog`, `feature/*`, `infra`, and `support`.

## Static checks and checkpoint

- [ ] **Step 12: Scan bytes and keys**

Run:

```powershell
$files = Get-ChildItem src/main/resources/com/aqishi/toolbox/util/messages*.properties
foreach ($file in $files) {
  $bytes = [IO.File]::ReadAllBytes($file.FullName)
  if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) { throw "BOM: $($file.Name)" }
}
rg -n "妗|澶|绠|鎴|锛|鐨|�|Ã|Â|â" pom.xml README.md src/main/java src/main/resources
```

Expected: no BOM and no mojibake matches. Compare key names across all three resource files; the category and tool key sets must be equal.

- [ ] **Step 13: Compile and commit**

```bash
mvn -Dmaven.test.skip=true package
git diff --check
git add pom.xml README.md docs src/main/resources src/main/java/com/aqishi/toolbox/feature
git commit -m "docs: normalize comments encoding and project guides"
```

