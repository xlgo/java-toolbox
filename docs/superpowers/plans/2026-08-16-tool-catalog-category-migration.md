# Tool Catalog and Category Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `MainFrame#createTools()` and scattered panel metadata with a centralized catalog while migrating all 50 entries to the approved ten-category taxonomy.

**Architecture:** `ToolCatalog` owns immutable descriptors; `ToolRegistry` owns factories and indexes; `ToolboxContext` supplies shared dependencies. `ToolPanel` reads its descriptor instead of duplicating ID/category metadata. `ToolNavigationState` translates old category IDs once.

**Tech Stack:** Java 8, Swing, existing `ToolPanel`, `ToolNavigationModel`, `ToolNavigationState`, UTF-8 message properties.

---

## File map

- Create: `src/main/java/com/aqishi/toolbox/catalog/ToolCategory.java` — category ID, label key, and stable display order.
- Create: `src/main/java/com/aqishi/toolbox/catalog/ToolDescriptor.java` — stable tool ID, category, label key, and keywords.
- Create: `src/main/java/com/aqishi/toolbox/catalog/ToolCatalog.java` — the 50 immutable descriptor constants and ordered list.
- Create: `src/main/java/com/aqishi/toolbox/catalog/ToolboxContext.java` — vault, clipboard, config, and shared service dependencies.
- Create: `src/main/java/com/aqishi/toolbox/catalog/ToolRegistry.java` — descriptor/factory index and duplicate validation.
- Modify: `src/main/java/com/aqishi/toolbox/ui/ToolPanel.java` — descriptor-backed metadata accessors.
- Modify: `src/main/java/com/aqishi/toolbox/ui/MainFrame.java` — consume `ToolRegistry` instead of the Supplier array.
- Modify: `src/main/java/com/aqishi/toolbox/ui/ToolNavigationModel.java` — group labels from `ToolCategory` descriptors.
- Modify: `src/main/java/com/aqishi/toolbox/ui/ToolNavigationState.java` — old-to-new category migration.
- Modify: `src/main/resources/com/aqishi/toolbox/util/messages.properties`.
- Modify: `src/main/resources/com/aqishi/toolbox/util/messages_zh_CN.properties`.
- Modify: `src/main/resources/com/aqishi/toolbox/util/messages_en_US.properties`.

## Descriptor contract

- [ ] **Step 1: Define the category value object**

Add the following Java 8-compatible shape to `ToolCategory.java`:

```java
public final class ToolCategory {
    private final String id;
    private final String labelKey;
    private final int order;

    public ToolCategory(String id, String labelKey, int order) { ... }
    public String getId() { ... }
    public String getLabelKey() { ... }
    public int getOrder() { ... }
}
```

Reject null/blank IDs and label keys in the constructor with `IllegalArgumentException`.

- [ ] **Step 2: Define the descriptor value object**

Add `ToolDescriptor` with immutable fields and a factory-independent metadata API:

```java
public final class ToolDescriptor {
    private final String id;
    private final String categoryId;
    private final String labelKey;
    private final List<String> keywords;

    public ToolDescriptor(String id, String categoryId, String labelKey,
                          List<String> keywords) { ... }
    public String getId() { ... }
    public String getCategoryId() { ... }
    public String getLabelKey() { ... }
    public List<String> getKeywords() { ... }
}
```

Copy the keyword list defensively and reject duplicate/blank IDs.

- [ ] **Step 3: Define the construction context**

Create `ToolboxContext` with non-null accessors for `VaultService` and `SecureClipboard`. Configuration continues to use the existing `ConfigManager` static facade; do not add a second configuration store.

## Catalog and registry

- [ ] **Step 4: Enter the approved ten categories**

Create constants in `ToolCatalog` for `security`, `codec`, `network`, `data`, `cloud`, `system`, `generation`, `compute`, `diagram`, and `monitor`, ordered as the design document specifies. Use label keys `group.security` through `group.monitor`.

- [ ] **Step 5: Enter all 50 stable descriptors**

Add one descriptor for each existing ID. The category assignments must be exactly:

```text
security: hash.codec, symmetric.crypto, asymmetric.crypto,
          cert.management, account.manager, totp.authenticator, jwt.codec
codec:    radix.encoding, timestamp, base64.image, url.tool, format.convert,
          json.format, xml.format, sql.format, string.tool, regex.tester,
          text.diff
network:  http.client, callback.mock, websocket.client, mqtt.client,
          subnet.calc, port.scanner, ssh
data:     database.connector, redis.management, kafka.connector,
          zookeeper.management
cloud:    docker.convert, k8s.deployment, k8s.manager
system:   chmod.calc, cron.parser, hosts.manager, wechat.sender
generation: data.generator, qrcode, color.convert
compute:  calculator, statistics, sort.visualizer, search.algorithm,
          hanoi, pingame
diagram:  bpmn.designer, mermaid, flowchart
monitor:  video.monitor, remote_desktop
```

Keep the existing search keywords verbatim while moving them into descriptor constants.

- [ ] **Step 6: Implement registry indexing**

`ToolRegistry` must expose `getCategories()`, `getDescriptors()`, `find(String id)`, `create(String id, ToolboxContext)`, and `createAll(ToolboxContext)`. `createAll` returns panels in descriptor order. Build an unmodifiable `LinkedHashMap`; throw `IllegalArgumentException("Duplicate tool id: ...")` on duplicate descriptors and `IllegalStateException` when a factory is missing.

Use `Function<ToolboxContext, ToolPanel>` factories so sensitive panels receive the shared vault and clipboard.

## UI integration and migration

- [ ] **Step 7: Make ToolPanel descriptor-backed**

Add a protected constructor:

```java
protected ToolPanel(ToolDescriptor descriptor) {
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
}
```

Change `getName()`, `getGroup()`, `getLabel()`, `getGroupLabel()`, and keyword matching to read descriptor fields. Keep the old `(String group, String name, String... keywords)` constructor temporarily as a package-private compatibility bridge and mark it `@Deprecated` with a migration comment.

- [ ] **Step 8: Replace MainFrame creation**

Remove the `Supplier<ToolPanel>[] creators` field/local array from `MainFrame#createTools()`. Construct one `ToolboxContext`, call `ToolRegistry.createAll(context)`, and preserve the existing startup progress callback and order.

- [ ] **Step 9: Add category resources and aliases**

Add these labels to all three resource files:

```properties
group.security=安全与身份
group.codec=编码、转换与文本
group.network=网络与接口
group.data=数据库与中间件
group.cloud=云原生与运维
group.system=系统与自动化
group.generation=生成与设计
group.compute=计算与算法
group.diagram=图表与建模
group.monitor=监控与远程协作
```

Use English values in `messages_en_US.properties`. Keep old `group.crypto`, `group.convert`, `group.format`, `group.dev`, `group.generate`, `group.calc`, `group.algo`, `group.chart`, and `group.misc` keys as aliases until the migration is complete.

- [ ] **Step 10: Migrate persisted navigation groups**

In `ToolNavigationState`, map `crypto→security`, `convert→codec`, `format→codec`, `dev→network`, `generate→generation`, `calc→compute`, `algo→compute`, `chart→diagram`, `misc→system`, and retain `monitor→monitor`. Filter duplicates while preserving order.

- [ ] **Step 11: Compile and inspect catalog statically**

Run:

```bash
mvn -Dmaven.test.skip=true package
git diff --check
```

Expected: exit code 0, `target/java-toolbox.jar` exists, and no whitespace errors. Also run a text scan confirming each descriptor ID appears once in `ToolCatalog.java` and each category label key appears in all three resources.

- [ ] **Step 12: Commit the catalog checkpoint**

```bash
git add src/main/java/com/aqishi/toolbox/catalog src/main/java/com/aqishi/toolbox/ui src/main/resources/com/aqishi/toolbox/util/messages*.properties
git commit -m "refactor: centralize tool catalog and categories"
```
