# Full Toolbox Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Audit all 44 Java Toolbox entries offline, fix reproducible functional and layout defects, align the shared shell with the supplied reference image, and finish with complete regression and packaging evidence.

**Architecture:** Add test-only inventory, construction, layout, and lifecycle coverage around the existing stable tool IDs. Repair repeated problems in `ui`/`ui.kit`, keep tool-specific behavior in the owning panel or pure helper, and use local loopback/failure paths for connection-oriented tools. The plan is one delivery, but every discovered defect still follows an isolated red-green cycle.

**Tech Stack:** Java 8, Swing, FlatLaf 3.5.4, JUnit 5, Maven Surefire, Java2D icons.

---

## File map

- `src/test/java/com/aqishi/toolbox/ui/ToolInventoryTest.java`: authoritative 44-ID inventory and navigation lookup coverage.
- `src/test/java/com/aqishi/toolbox/ui/AllToolViewsTest.java`: EDT construction, mounting, sizing, and shutdown smoke coverage for every tool.
- `src/test/java/com/aqishi/toolbox/ui/SwingLayoutAudit.java`: test-only recursive layout and geometry assertions.
- `src/main/java/com/aqishi/toolbox/ui/ToolboxIcons.java`: theme-aware Java2D line icons used by the shell and navigation renderer.
- `src/main/java/com/aqishi/toolbox/ui/ToolSidebar.java`: effect-image brand/search/navigation hierarchy and semantic icon mapping.
- `src/main/java/com/aqishi/toolbox/ui/MainFrame.java`: effect-image breadcrumb, theme/language affordances, and status indicator.
- `src/main/java/com/aqishi/toolbox/util/UIUtils.java`: shell width constants only when required by the accepted 256px default.
- `src/test/java/com/aqishi/toolbox/ui/ToolboxIconsTest.java`: icon dimensions and theme-derived paint smoke coverage.
- `src/test/java/com/aqishi/toolbox/ui/ToolSidebarTest.java`: brand, search, icon, accessibility, and selection regression coverage.
- `src/test/java/com/aqishi/toolbox/ui/MainFrameStructureTest.java`: breadcrumb/settings/status shell structure regression coverage.
- `docs/reports/2026-08-08-toolbox-audit.md`: 44-entry audit evidence, fixes, and explicit offline limitations.

### Task 1: Lock the 44-tool inventory

**Files:**
- Create: `src/test/java/com/aqishi/toolbox/ui/ToolInventoryTest.java`
- Read: `src/main/java/com/aqishi/toolbox/ui/MainFrame.java`

- [ ] **Step 1: Write the failing inventory test**

Create a JUnit test with the exact ordered IDs below. Construct `MainFrame` with `VaultUiTestSupport`, assert every ID resolves, assert no duplicate IDs, and assert the total is 44.

```java
private static final List<String> EXPECTED_IDS = Arrays.asList(
        "hash.codec", "symmetric.crypto", "asymmetric.crypto", "account.manager",
        "totp.authenticator", "radix.encoding", "timestamp", "base64.image",
        "format.convert", "json.format", "xml.format", "sql.format", "regex.tester",
        "jwt.codec", "cron.parser", "text.diff", "docker.convert", "subnet.calc",
        "http.client", "callback.mock", "color.convert", "cert.management",
        "k8s.deployment", "k8s.manager", "uuid.generator", "password.generator",
        "random.data", "calculator", "statistics", "sort.visualizer",
        "search.algorithm", "hanoi", "video.monitor", "remote_desktop",
        "redis.management", "bpmn.designer", "database.connector", "string.tool",
        "kafka.connector", "zookeeper.management", "wechat.sender", "mermaid",
        "flowchart", "ssh");
```

- [ ] **Step 2: Run the test and verify the first failure**

Run: `mvn -Dtest=ToolInventoryTest test`

Expected: FAIL because `ToolInventoryTest` has not yet been completed or because the inventory exposes a missing/duplicate ID.

- [ ] **Step 3: Complete only the inventory assertions**

Use the existing package-private injected `MainFrame` constructor and `findTool(String)`. Do not add test-only production accessors.

- [ ] **Step 4: Run the inventory test**

Run: `mvn -Dtest=ToolInventoryTest test`

Expected: PASS with 44 unique stable IDs.

- [ ] **Step 5: Commit the inventory checkpoint**

```bash
git add src/test/java/com/aqishi/toolbox/ui/ToolInventoryTest.java
git commit -m "test: lock toolbox tool inventory"
```

### Task 2: Construct and lay out every tool offline

**Files:**
- Create: `src/test/java/com/aqishi/toolbox/ui/SwingLayoutAudit.java`
- Create: `src/test/java/com/aqishi/toolbox/ui/AllToolViewsTest.java`
- Modify only if a test exposes a lifecycle defect: the owning `src/main/java/**` file

- [ ] **Step 1: Write a test-only recursive layout helper**

`SwingLayoutAudit.layout(Container, Dimension)` sets the size, calls `doLayout()` recursively on visible containers, and records visible components with negative width/height or non-finite preferred dimensions. `assertSane(Container, String)` fails with the component class path and tool ID.

- [ ] **Step 2: Write the failing all-view construction test**

For each ID from Task 1, run on the EDT:

```java
frame.setSize(1024, 660);
frame.selectTool(toolId);
assertNotNull(frame.findTool(toolId).getView(), toolId);
SwingLayoutAudit.layout(frame.getContentPane(), new Dimension(1024, 660));
SwingLayoutAudit.assertSane(frame.getContentPane(), toolId + "@1024x660");
frame.setSize(820, 520);
SwingLayoutAudit.layout(frame.getContentPane(), new Dimension(820, 520));
SwingLayoutAudit.assertSane(frame.getContentPane(), toolId + "@820x520");
```

Dispose the frame in `finally`, then flush the EDT. Use the temporary vault/config support; never load the user's config.

- [ ] **Step 3: Run and capture the first real failure**

Run: `mvn -Dtest=AllToolViewsTest test`

Expected: FAIL on the first reproducible construction, layout, or shutdown defect. If it passes, the test establishes construction coverage and Task 3 becomes the first red test.

- [ ] **Step 4: Fix each exposed defect with an isolated red-green loop**

For each failure, keep the failing assertion, change only the owning shared component or tool panel, rerun the single failing test method, then rerun `AllToolViewsTest`. Record the exact defect and file in the audit report created in Task 6.

- [ ] **Step 5: Commit the construction checkpoint**

```bash
git add src/test/java/com/aqishi/toolbox/ui/SwingLayoutAudit.java src/test/java/com/aqishi/toolbox/ui/AllToolViewsTest.java src/main/java
git commit -m "test: audit every toolbox view"
```

### Task 3: Match the accepted main-frame reference

**Files:**
- Create: `src/main/java/com/aqishi/toolbox/ui/ToolboxIcons.java`
- Create: `src/test/java/com/aqishi/toolbox/ui/ToolboxIconsTest.java`
- Modify: `src/main/java/com/aqishi/toolbox/ui/ToolSidebar.java`
- Modify: `src/main/java/com/aqishi/toolbox/ui/MainFrame.java`
- Modify: `src/main/java/com/aqishi/toolbox/util/UIUtils.java`
- Modify: `src/test/java/com/aqishi/toolbox/ui/ToolSidebarTest.java`
- Modify: `src/test/java/com/aqishi/toolbox/ui/MainFrameStructureTest.java`

- [ ] **Step 1: Write failing shell structure tests**

Add assertions for:

```java
assertEquals(256, UIUtils.SIDEBAR_DEFAULT_WIDTH);
assertNotNull(findIconLabel(sidebar, "Java Toolbox"));
assertNotNull(findSearchFieldWithLeadingIcon(sidebar));
assertEquals("›", findBreadcrumbSeparator(frame).getText());
assertNotNull(findAccessibleIcon(frame, I18n.get("top.theme")));
assertNotNull(findAccessibleIcon(frame, I18n.get("top.lang")));
assertNotNull(findAccessibleIcon(frame, I18n.get("status.ready.short")));
```

Keep the existing collapse, keyboard, theme, language, status, and selection assertions.

- [ ] **Step 2: Verify the shell tests fail for the intended differences**

Run: `mvn -Dtest=ToolboxIconsTest,ToolSidebarTest,MainFrameStructureTest test`

Expected: FAIL because the icon library, 256px default, chevron separator, leading search icon, and status dot do not exist.

- [ ] **Step 3: Implement `ToolboxIcons`**

Create a 16×16 `Icon` implementation that paints 1.5px rounded Java2D strokes using `Tokens.mutedForeground()` or `Tokens.accent()`. Provide semantic factories for terminal, search, palette, globe, status, group chevrons, and the reusable tool families `CODE`, `LOCK`, `KEY`, `SHIELD`, `CERTIFICATE`, `CONVERT`, `FORMAT`, `NETWORK`, `DATABASE`, `TERMINAL`, `CHART`, `CALCULATOR`, and `MONITOR`. Icons expose no mutable global state and repaint correctly after LAF updates because paint colors are resolved inside `paintIcon`.

- [ ] **Step 4: Apply the sidebar shell**

Use the terminal icon beside `Java Toolbox`, install the search icon through `JTextField.leadingIcon`, replace text disclosure/tool glyphs in `NavigationRenderer` with semantic `ToolboxIcons`, preserve tooltip and accessible names, and keep the collapse action as a low-emphasis icon button. Change only the default width to 256; retain min/max clamping and persisted user widths.

- [ ] **Step 5: Apply top and bottom shell details**

Change the breadcrumb separator to `›`; replace visible “主题/语言” labels with accessible palette/globe icon labels; add a green status dot next to “就绪”; keep the existing selectors, secure-vault placement, timers, status values, and content host behavior.

- [ ] **Step 6: Run shell and navigation regression tests**

Run: `mvn -Dtest=ToolboxIconsTest,ToolSidebarTest,MainFrameStructureTest,ToolNavigationModelTest,ToolNavigationStateTest,ToolContentHostTest test`

Expected: PASS.

- [ ] **Step 7: Commit the shared shell checkpoint**

```bash
git add src/main/java/com/aqishi/toolbox/ui/ToolboxIcons.java src/main/java/com/aqishi/toolbox/ui/ToolSidebar.java src/main/java/com/aqishi/toolbox/ui/MainFrame.java src/main/java/com/aqishi/toolbox/util/UIUtils.java src/test/java/com/aqishi/toolbox/ui
git commit -m "feat: align the shared toolbox shell"
```

### Task 4: Run static functional and lifecycle diagnostics

**Files:**
- Inspect: `src/main/java/**/*.java`
- Modify after a confirmed failing test: the exact owning source and test file

- [ ] **Step 1: Locate high-risk Swing and lifecycle patterns**

Run these exact read-only scans and save the matching file/line list in working notes:

```powershell
rg -n "Thread\.sleep|\.get\(\)|join\(|invokeAndWait|new Timer|new Thread|newSingleThreadExecutor|newFixedThreadPool|Executors\." src/main/java
rg -n "setPreferredSize|setMinimumSize|setMaximumSize|setBounds|setLayout\(null\)|new Dimension" src/main/java
rg -n "catch \([^)]*\) \{\s*\}|printStackTrace|System\.err|JOptionPane" src/main/java
rg -n "new Socket|openConnection|DriverManager|getConnection|Jedis|KafkaConsumer|KafkaProducer|ZooKeeper|Session" src/main/java
```

- [ ] **Step 2: Classify every match before changing code**

Mark each match as safe, test-only concern, or reproducible P0–P3 defect. A production edit is permitted only after a named JUnit test fails for that exact behavior. Benign matches stay unchanged.

- [ ] **Step 3: Add concrete regression tests for confirmed defects**

Place pure logic tests beside the owning package, UI behavior tests in the existing panel test or a new `<PanelName>Test`, and lifecycle tests in the connection/session package. Each test name states one behavior, uses temporary files or loopback endpoints, and is run alone to verify the expected failure before implementation.

- [ ] **Step 4: Apply minimal owning-layer fixes**

Shared layout defects go to `ui.kit`; single-panel validation stays in that panel; reusable deterministic logic is extracted to a package-private pure helper; network work moves off the EDT without changing configuration formats. Run the single test and its package suite after every fix.

- [ ] **Step 5: Re-run the entire offline audit**

Run: `mvn test`

Expected: all baseline and new tests pass with zero failures and errors.

### Task 5: Inspect deterministic core behavior by family

**Files:**
- Modify: existing tests under `src/test/java/com/aqishi/toolbox/crypto`, `misc`, `monitor`, and new tests beside untested pure helpers
- Modify after a red test: matching sources under `src/main/java`

- [ ] **Step 1: Exercise transformation invariants**

Add or extend tests for encode/decode round trips, formatter idempotence, invalid inputs, empty inputs, numeric boundaries, and algorithm fixed vectors where pure helpers exist. Run each class alone before and after its minimal fix.

- [ ] **Step 2: Exercise generator and calculator boundaries**

Verify requested counts/lengths, inclusive ranges, zero/negative rejection, overflow handling, and stable output shapes for UUID/password/random/calculator/statistics logic that can be invoked without a visible window.

- [ ] **Step 3: Exercise protocol and configuration parsing offline**

Use loopback or fixtures for HTTP request construction, callback parsing, SSH endpoint/config parsing, Redis/database/Kafka/ZooKeeper/K8s parameter validation, and remote protocol codecs. Successful real-service sessions remain explicitly unexecuted.

- [ ] **Step 4: Run the related suites**

Run: `mvn -Dtest='com.aqishi.toolbox.crypto.**,com.aqishi.toolbox.misc.**,com.aqishi.toolbox.monitor.**' test`

Expected: PASS with zero failures and errors.

### Task 6: Record the 44-entry audit and verify delivery

**Files:**
- Create: `docs/reports/2026-08-08-toolbox-audit.md`
- Update: `docs/superpowers/plans/2026-08-08-full-toolbox-audit.md` checkboxes

- [ ] **Step 1: Create the audit report**

Include one row per exact stable ID from Task 1 with columns: tool, construction, core offline check, invalid/failure path, 1024×660 layout, 820×520 layout, lifecycle, result, evidence. Use `PASS`, `FIXED`, or `CONDITIONAL` only; `CONDITIONAL` must name the required real service and the offline evidence that did run.

- [ ] **Step 2: Run the complete test suite**

Run: `mvn test`

Expected: zero failures and zero errors.

- [ ] **Step 3: Build the distributable jar**

Run: `mvn package`

Expected: `BUILD SUCCESS` and `target/java-toolbox.jar` exists.

- [ ] **Step 4: Run visual verification**

Launch the app with temporary config and capture the shared shell plus representative local, connection, chart, and remote layouts at 1024×660 and 820×520. Check one light and one dark FlatLaf theme and both locales. Store images under `target/audit-screenshots/`.

- [ ] **Step 5: Inspect repository hygiene**

Run:

```powershell
git diff --check
git status --short
Get-ChildItem target\audit-screenshots -File | Select-Object Name,Length
```

Expected: no whitespace errors; no credentials, temporary vaults, generated screenshots, or `.superpowers` files are staged.

- [ ] **Step 6: Commit the final audit evidence**

```bash
git add docs/reports/2026-08-08-toolbox-audit.md docs/superpowers/plans/2026-08-08-full-toolbox-audit.md src/main/java src/test/java
git commit -m "fix: complete offline toolbox audit"
```
