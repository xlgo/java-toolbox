# Global Workbench Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the duplicated tab-and-list navigation with a compact, resizable grouped sidebar while preserving every tool, theme, language, status indicator, and in-progress tool view.

**Architecture:** Introduce pure navigation and persistence models, a Swing sidebar component, and a lazy CardLayout content host. MainFrame becomes the application-shell coordinator: it connects stable tool IDs to the sidebar, header, content host, theme/language controls, and ConfigManager without moving business logic out of any ToolPanel.

**Tech Stack:** Java 8, Swing, FlatLaf 3.5.4, JUnit 5, Maven Surefire

---

## File map

- Create src/main/java/com/aqishi/toolbox/ui/ToolNavigationModel.java: ordered grouping, filtering, stable-ID lookup.
- Create src/main/java/com/aqishi/toolbox/ui/ToolNavigationState.java: sidebar-width clamping, selected-tool fallback, expanded-group serialization.
- Create src/main/java/com/aqishi/toolbox/ui/ToolContentHost.java: lazy one-time mounting of ToolPanel views in CardLayout.
- Create src/main/java/com/aqishi/toolbox/ui/ToolSidebar.java: application title, search field, grouped tree, empty state, keyboard navigation, expansion state.
- Modify src/main/java/com/aqishi/toolbox/ui/ToolPanel.java: locale-stable, null-safe search normalization.
- Modify src/main/java/com/aqishi/toolbox/ui/MainFrame.java: replace tabs and per-group lists with the workbench shell.
- Modify src/main/java/com/aqishi/toolbox/util/UIUtils.java: shared workbench spacing and sidebar dimensions.
- Modify all three messages*.properties files: sidebar, accessibility, and empty-state strings.
- Create six focused JUnit test classes under src/test/java/com/aqishi/toolbox/ui/ and src/test/java/com/aqishi/toolbox/misc/.
- Create DESIGN.md and update README.md after visual verification so documentation matches the shipped client.

### Task 1: Ordered navigation model and normalized search

**Files:**
- Create: src/main/java/com/aqishi/toolbox/ui/ToolNavigationModel.java
- Modify: src/main/java/com/aqishi/toolbox/ui/ToolPanel.java
- Test: src/test/java/com/aqishi/toolbox/ui/ToolNavigationModelTest.java

- [ ] **Step 1: Write the failing model tests**

Create the test file with stable test labels so it does not depend on the user's saved locale:

~~~java
package com.aqishi.toolbox.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolNavigationModelTest {

    @Test
    void preservesGroupAndToolInsertionOrder() {
        ToolPanel hash = tool("crypto", "hash.codec", "Hash", "Security", "digest");
        ToolPanel radix = tool("convert", "radix.encoding", "Radix", "Convert", "binary");
        ToolPanel rsa = tool("crypto", "asymmetric.crypto", "RSA", "Security", "signature");

        ToolNavigationModel model = new ToolNavigationModel(Arrays.asList(hash, radix, rsa));

        assertEquals(Arrays.asList("crypto", "convert"), model.getGroupIds());
        assertEquals(Arrays.asList(hash, rsa), model.getGroups().get(0).getTools());
        assertEquals(Arrays.asList(radix), model.getGroups().get(1).getTools());
    }

    @Test
    void filtersByStableIdLocalizedLabelGroupAndKeyword() {
        ToolPanel hash = tool("crypto", "hash.codec", "摘要与编解码", "加密安全", "digest");
        ToolPanel radix = tool("convert", "radix.encoding", "进制与编码", "转换编码", "binary");
        ToolNavigationModel model = new ToolNavigationModel(Arrays.asList(hash, radix));

        assertEquals("hash.codec", onlyToolId(model.filter("HASH")));
        assertEquals("hash.codec", onlyToolId(model.filter("摘要")));
        assertEquals("hash.codec", onlyToolId(model.filter("加密安全")));
        assertEquals("radix.encoding", onlyToolId(model.filter("BINARY")));
        assertEquals(2, model.filter(" ").size());
        assertTrue(model.filter("missing").isEmpty());
    }

    @Test
    void rejectsDuplicateStableToolIds() {
        ToolPanel first = tool("crypto", "same.id", "One", "Security");
        ToolPanel second = tool("convert", "same.id", "Two", "Convert");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ToolNavigationModel(Arrays.asList(first, second)));

        assertTrue(error.getMessage().contains("same.id"));
    }

    @Test
    void toolPanelSearchHandlesNullAndUsesTrimmedQueries() {
        ToolPanel panel = tool("crypto", "hash.codec", "Hash", "Security", "Digest");

        assertFalse(panel.matchesSearch(null));
        assertTrue(panel.matchesSearch("  DIGEST  "));
        assertTrue(panel.matchesSearch(" "));
    }

    private static String onlyToolId(List<ToolNavigationModel.Group> groups) {
        assertEquals(1, groups.size());
        assertEquals(1, groups.get(0).getTools().size());
        return groups.get(0).getTools().get(0).getName();
    }

    private static ToolPanel tool(
            String group, String id, String label, String groupLabel, String... keywords) {
        return new ToolPanel(group, id, keywords) {
            @Override
            public String getLabel() {
                return label;
            }

            @Override
            public String getGroupLabel() {
                return groupLabel;
            }

            @Override
            protected JComponent build() {
                return new JPanel();
            }
        };
    }
}
~~~

- [ ] **Step 2: Run the test and confirm the expected red state**

Run:

~~~powershell
mvn -Dtest=ToolNavigationModelTest test
~~~

Expected: compilation fails because ToolNavigationModel does not exist; no production code has been added yet.

- [ ] **Step 3: Implement the ordered model**

Create ToolNavigationModel.java:

~~~java
package com.aqishi.toolbox.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ToolNavigationModel {

    public static final class Group {
        private final String id;
        private final List<ToolPanel> tools;

        private Group(String id, List<ToolPanel> tools) {
            this.id = id;
            this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return tools.isEmpty() ? id : tools.get(0).getGroupLabel();
        }

        public List<ToolPanel> getTools() {
            return tools;
        }
    }

    private final List<Group> groups;
    private final Map<String, ToolPanel> toolsById;

    public ToolNavigationModel(List<ToolPanel> tools) {
        LinkedHashMap<String, List<ToolPanel>> grouped = new LinkedHashMap<>();
        LinkedHashMap<String, ToolPanel> indexed = new LinkedHashMap<>();
        for (ToolPanel tool : tools) {
            ToolPanel previous = indexed.put(tool.getName(), tool);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate tool id: " + tool.getName());
            }
            grouped.computeIfAbsent(tool.getGroup(), key -> new ArrayList<>()).add(tool);
        }

        List<Group> ordered = new ArrayList<>();
        for (Map.Entry<String, List<ToolPanel>> entry : grouped.entrySet()) {
            ordered.add(new Group(entry.getKey(), entry.getValue()));
        }
        groups = Collections.unmodifiableList(ordered);
        toolsById = Collections.unmodifiableMap(indexed);
    }

    public List<Group> getGroups() {
        return groups;
    }

    public List<String> getGroupIds() {
        List<String> ids = new ArrayList<>();
        for (Group group : groups) {
            ids.add(group.getId());
        }
        return Collections.unmodifiableList(ids);
    }

    public Set<String> getToolIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(toolsById.keySet()));
    }

    public ToolPanel findTool(String toolId) {
        return toolId == null ? null : toolsById.get(toolId);
    }

    public String getFirstToolId() {
        return toolsById.isEmpty() ? null : toolsById.keySet().iterator().next();
    }

    public List<Group> filter(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return groups;
        }

        List<Group> matches = new ArrayList<>();
        for (Group group : groups) {
            List<ToolPanel> tools = new ArrayList<>();
            for (ToolPanel tool : group.getTools()) {
                if (tool.matchesSearch(normalized)) {
                    tools.add(tool);
                }
            }
            if (!tools.isEmpty()) {
                matches.add(new Group(group.getId(), tools));
            }
        }
        return Collections.unmodifiableList(matches);
    }
}
~~~

Update ToolPanel.matchesSearch:

~~~java
public boolean matchesSearch(String query) {
    if (query == null) return false;
    String q = query.trim().toLowerCase(java.util.Locale.ROOT);
    if (q.isEmpty()) return true;
    if (name.toLowerCase(java.util.Locale.ROOT).contains(q)
            || getLabel().toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
    if (group.toLowerCase(java.util.Locale.ROOT).contains(q)
            || getGroupLabel().toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
    for (String kw : searchKeywords) {
        if (kw != null && kw.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
    }
    return false;
}
~~~

- [ ] **Step 4: Run the focused model tests**

Run:

~~~powershell
mvn -Dtest=ToolNavigationModelTest test
~~~

Expected: 4 tests pass with 0 failures and 0 errors.

- [ ] **Step 5: Commit the model**

~~~powershell
git add src/main/java/com/aqishi/toolbox/ui/ToolNavigationModel.java src/main/java/com/aqishi/toolbox/ui/ToolPanel.java src/test/java/com/aqishi/toolbox/ui/ToolNavigationModelTest.java
git commit -m "feat(ui): add stable tool navigation model"
~~~

### Task 2: Persisted navigation state and shared layout dimensions

**Files:**
- Create: src/main/java/com/aqishi/toolbox/ui/ToolNavigationState.java
- Modify: src/main/java/com/aqishi/toolbox/util/UIUtils.java
- Test: src/test/java/com/aqishi/toolbox/ui/ToolNavigationStateTest.java

- [ ] **Step 1: Write failing state tests**

~~~java
package com.aqishi.toolbox.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.*;

class ToolNavigationStateTest {

    @Test
    void clampsSidebarWidthToSupportedRange() {
        assertEquals(190, ToolNavigationState.clampSidebarWidth(100));
        assertEquals(228, ToolNavigationState.clampSidebarWidth(228));
        assertEquals(320, ToolNavigationState.clampSidebarWidth(500));
    }

    @Test
    void resolvesSavedToolOrFallsBackToFirstTool() {
        ToolNavigationModel model = new ToolNavigationModel(Arrays.asList(
                tool("crypto", "hash.codec"),
                tool("convert", "radix.encoding")));

        assertEquals("radix.encoding", ToolNavigationState.resolveToolId(model, "radix.encoding"));
        assertEquals("hash.codec", ToolNavigationState.resolveToolId(model, "removed.tool"));
        assertEquals("hash.codec", ToolNavigationState.resolveToolId(model, null));
    }

    @Test
    void distinguishesMissingExpansionStateFromIntentionallyEmptyState() {
        LinkedHashSet<String> valid = new LinkedHashSet<>(Arrays.asList("crypto", "convert"));

        assertEquals(valid, ToolNavigationState.parseExpandedGroups(null, valid));
        assertEquals(Collections.emptySet(), ToolNavigationState.parseExpandedGroups("", valid));
        assertEquals(
                new LinkedHashSet<>(Collections.singletonList("convert")),
                ToolNavigationState.parseExpandedGroups("removed,convert", valid));
        assertEquals("crypto,convert", ToolNavigationState.serializeExpandedGroups(valid));
    }

    private static ToolPanel tool(String group, String id) {
        return new ToolPanel(group, id) {
            @Override
            protected JComponent build() {
                return new JPanel();
            }
        };
    }
}
~~~

- [ ] **Step 2: Run the state test and confirm it fails**

Run:

~~~powershell
mvn -Dtest=ToolNavigationStateTest test
~~~

Expected: compilation fails because ToolNavigationState does not exist.

- [ ] **Step 3: Add workbench constants to UIUtils**

Add beside CONTENT_PADDING:

~~~java
public static final int SPACE_XS = 4;
public static final int SPACE_SM = 8;
public static final int SPACE_MD = 12;
public static final int SPACE_LG = 16;
public static final int SIDEBAR_MIN_WIDTH = 190;
public static final int SIDEBAR_DEFAULT_WIDTH = 228;
public static final int SIDEBAR_MAX_WIDTH = 320;
public static final int NAV_ROW_HEIGHT = 32;
public static final int WORKBENCH_DIVIDER_SIZE = 5;
~~~

- [ ] **Step 4: Implement navigation state normalization**

~~~java
package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.UIUtils;

import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ToolNavigationState {

    private ToolNavigationState() {
    }

    public static int clampSidebarWidth(int width) {
        return Math.max(
                UIUtils.SIDEBAR_MIN_WIDTH,
                Math.min(UIUtils.SIDEBAR_MAX_WIDTH, width));
    }

    public static String resolveToolId(ToolNavigationModel model, String savedToolId) {
        return model.findTool(savedToolId) != null ? savedToolId : model.getFirstToolId();
    }

    public static LinkedHashSet<String> parseExpandedGroups(
            String encoded, Collection<String> validGroupIds) {
        LinkedHashSet<String> valid = new LinkedHashSet<>(validGroupIds);
        if (encoded == null) {
            return valid;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (encoded.trim().isEmpty()) {
            return result;
        }
        for (String part : encoded.split(",")) {
            String id = part.trim();
            if (valid.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    public static String serializeExpandedGroups(Collection<String> groupIds) {
        StringBuilder encoded = new StringBuilder();
        for (String id : groupIds) {
            if (encoded.length() > 0) {
                encoded.append(',');
            }
            encoded.append(id);
        }
        return encoded.toString();
    }
}
~~~

- [ ] **Step 5: Run focused state tests**

Run:

~~~powershell
mvn -Dtest=ToolNavigationStateTest test
~~~

Expected: 3 tests pass with 0 failures and 0 errors.

- [ ] **Step 6: Commit state and tokens**

~~~powershell
git add src/main/java/com/aqishi/toolbox/ui/ToolNavigationState.java src/main/java/com/aqishi/toolbox/util/UIUtils.java src/test/java/com/aqishi/toolbox/ui/ToolNavigationStateTest.java
git commit -m "feat(ui): persist normalized navigation state"
~~~

### Task 3: Lazy CardLayout content host

**Files:**
- Create: src/main/java/com/aqishi/toolbox/ui/ToolContentHost.java
- Test: src/test/java/com/aqishi/toolbox/ui/ToolContentHostTest.java

- [ ] **Step 1: Write the failing lazy-mount tests**

~~~java
package com.aqishi.toolbox.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolContentHostTest {

    @Test
    void mountsOnlyTheSelectedToolAndBuildsItOnce() {
        AtomicInteger firstBuilds = new AtomicInteger();
        AtomicInteger secondBuilds = new AtomicInteger();
        ToolPanel first = tool("first", firstBuilds);
        ToolPanel second = tool("second", secondBuilds);
        ToolContentHost host = new ToolContentHost(Arrays.asList(first, second));

        assertEquals(0, host.getComponentCount());
        assertTrue(host.showTool("first"));
        assertEquals(1, firstBuilds.get());
        assertEquals(0, secondBuilds.get());
        assertTrue(host.showTool("first"));
        assertEquals(1, firstBuilds.get());
        assertTrue(host.showTool("second"));
        assertEquals(1, secondBuilds.get());
        assertEquals(2, host.getComponentCount());
    }

    @Test
    void ignoresUnknownToolWithoutChangingMountedViews() {
        ToolContentHost host = new ToolContentHost(
                Arrays.asList(tool("known", new AtomicInteger())));

        assertFalse(host.showTool("missing"));
        assertEquals(0, host.getComponentCount());
    }

    private static ToolPanel tool(String id, AtomicInteger builds) {
        return new ToolPanel("group", id) {
            @Override
            protected JComponent build() {
                builds.incrementAndGet();
                return new JPanel();
            }
        };
    }
}
~~~

- [ ] **Step 2: Run the test and confirm it fails**

Run:

~~~powershell
mvn -Dtest=ToolContentHostTest test
~~~

Expected: compilation fails because ToolContentHost does not exist.

- [ ] **Step 3: Implement the lazy content host**

~~~java
package com.aqishi.toolbox.ui;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ToolContentHost extends JPanel {

    private final CardLayout cards = new CardLayout();
    private final Map<String, ToolPanel> toolsById = new LinkedHashMap<>();
    private final Set<String> mountedToolIds = new LinkedHashSet<>();

    public ToolContentHost(List<ToolPanel> tools) {
        super();
        setLayout(cards);
        for (ToolPanel tool : tools) {
            toolsById.put(tool.getName(), tool);
        }
    }

    public boolean showTool(String toolId) {
        ToolPanel tool = toolsById.get(toolId);
        if (tool == null) {
            return false;
        }
        if (mountedToolIds.add(toolId)) {
            add(tool.getView(), toolId);
        }
        cards.show(this, toolId);
        return true;
    }

    public boolean isMounted(String toolId) {
        return mountedToolIds.contains(toolId);
    }
}
~~~

- [ ] **Step 4: Run the lazy host tests**

Run:

~~~powershell
mvn -Dtest=ToolContentHostTest test
~~~

Expected: 2 tests pass with 0 failures and 0 errors.

- [ ] **Step 5: Commit the content host**

~~~powershell
git add src/main/java/com/aqishi/toolbox/ui/ToolContentHost.java src/test/java/com/aqishi/toolbox/ui/ToolContentHostTest.java
git commit -m "feat(ui): mount tool views lazily"
~~~

### Task 4: Grouped sidebar, inline filtering, and keyboard navigation

**Files:**
- Create: src/main/java/com/aqishi/toolbox/ui/ToolSidebar.java
- Modify: src/main/resources/com/aqishi/toolbox/util/messages.properties
- Modify: src/main/resources/com/aqishi/toolbox/util/messages_zh_CN.properties
- Modify: src/main/resources/com/aqishi/toolbox/util/messages_en_US.properties
- Test: src/test/java/com/aqishi/toolbox/ui/ToolSidebarTest.java

- [ ] **Step 1: Add failing Swing behavior tests**

Create tests that find components by type, avoiding test-only methods in production:

~~~java
package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.I18n;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ToolSidebarTest {

    @Test
    void filtersInlineAndShowsEmptyState() throws Exception {
        AtomicReference<ToolSidebar> sidebarRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> sidebarRef.set(sidebar()));
        ToolSidebar sidebar = sidebarRef.get();
        JTextField search = find(sidebar, JTextField.class);
        JTree tree = find(sidebar, JTree.class);

        SwingUtilities.invokeAndWait(() -> search.setText("missing"));
        Thread.sleep(180);
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(1, tree.getRowCount());
        assertEquals(
                com.aqishi.toolbox.util.I18n.get("nav.empty"),
                tree.getPathForRow(0).getLastPathComponent().toString());
    }

    @Test
    void selectionCallbackUsesStableToolId() throws Exception {
        AtomicReference<String> selected = new AtomicReference<>();
        ToolSidebar[] sidebar = new ToolSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            ToolNavigationModel model = model();
            sidebar[0] = new ToolSidebar(model, selected::set, () -> { });
            JTree tree = find(sidebar[0], JTree.class);
            TreePath path = findPath(tree, "Hash");
            tree.setSelectionPath(path);
        });

        assertEquals("hash.codec", selected.get());
    }

    @Test
    void shortcutsFocusSearchOpenSelectionAndClearQuery() throws Exception {
        ToolSidebar[] sidebar = new ToolSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            sidebar[0] = sidebar();
            JTextField search = find(sidebar[0], JTextField.class);
            search.setText("Hash");
            sidebar[0].getActionMap().get("nav.focusSearch")
                    .actionPerformed(new java.awt.event.ActionEvent(sidebar[0], 0, ""));
            assertTrue(search.isFocusOwner() || !sidebar[0].isShowing());
            sidebar[0].getActionMap().get("nav.clearSearch")
                    .actionPerformed(new java.awt.event.ActionEvent(sidebar[0], 0, ""));
            assertEquals("", search.getText());
        });
    }

    @Test
    void restoresPersistedExpansionSet() throws Exception {
        ToolSidebar[] sidebar = new ToolSidebar[1];
        SwingUtilities.invokeAndWait(() -> {
            sidebar[0] = sidebar();
            sidebar[0].setExpandedGroupIds(Collections.singleton("convert"));
        });

        assertEquals(Collections.singleton("convert"), sidebar[0].getExpandedGroupIds());
    }

    @Test
    void usesCompactCollapseControlWithLocalizedDescription() throws Exception {
        ToolSidebar[] sidebar = new ToolSidebar[1];
        SwingUtilities.invokeAndWait(() -> sidebar[0] = sidebar());

        JButton collapse = find(sidebar[0], JButton.class);
        assertEquals("‹", collapse.getText());
        assertEquals(I18n.get("nav.collapse"), collapse.getToolTipText());
        assertEquals(
                I18n.get("nav.collapse"),
                collapse.getAccessibleContext().getAccessibleName());
    }

    @Test
    void refreshLabelsKeepsTheMountedViewAndCallbacksOnTheEdt() throws Exception {
        AtomicInteger builds = new AtomicInteger();
        AtomicReference<Boolean> callbackOnEdt = new AtomicReference<>(false);
        ToolPanel tool = new ToolPanel("crypto", "hash.codec", "digest") {
            @Override public String getLabel() { return "Hash"; }
            @Override public String getGroupLabel() { return "Security"; }
            @Override protected JComponent build() {
                builds.incrementAndGet();
                return new JPanel();
            }
        };
        ToolNavigationModel model =
                new ToolNavigationModel(Collections.singletonList(tool));
        ToolContentHost host =
                new ToolContentHost(Collections.singletonList(tool));
        ToolSidebar[] sidebar = new ToolSidebar[1];

        SwingUtilities.invokeAndWait(() -> {
            sidebar[0] = new ToolSidebar(model, id -> {
                callbackOnEdt.set(SwingUtilities.isEventDispatchThread());
                host.showTool(id);
            }, () -> { });
            sidebar[0].setSelectedTool("hash.codec");
            sidebar[0].getActionMap().get("nav.openSelection")
                    .actionPerformed(new java.awt.event.ActionEvent(sidebar[0], 0, ""));
            sidebar[0].refreshLabels();
        });

        assertTrue(callbackOnEdt.get());
        assertEquals(1, builds.get());
        assertTrue(host.isMounted("hash.codec"));
    }

    private static ToolSidebar sidebar() {
        return new ToolSidebar(model(), id -> { }, () -> { });
    }

    private static ToolNavigationModel model() {
        return new ToolNavigationModel(Arrays.asList(
                tool("crypto", "hash.codec", "Hash", "Security", "digest"),
                tool("convert", "radix.encoding", "Radix", "Convert", "binary")));
    }

    private static ToolPanel tool(
            String group, String id, String label, String groupLabel, String... keywords) {
        return new ToolPanel(group, id, keywords) {
            @Override public String getLabel() { return label; }
            @Override public String getGroupLabel() { return groupLabel; }
            @Override protected JComponent build() { return new JPanel(); }
        };
    }

    private static TreePath findPath(JTree tree, String label) {
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            if (label.equals(path.getLastPathComponent().toString())) {
                return path;
            }
        }
        fail("Missing tree path: " + label);
        return null;
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) {
                T nested = find((Container) child, type);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
~~~

- [ ] **Step 2: Run the sidebar test and confirm it fails**

Run:

~~~powershell
mvn -Dtest=ToolSidebarTest test
~~~

Expected: compilation fails because ToolSidebar does not exist.

- [ ] **Step 3: Add the localization keys**

Append to messages.properties and messages_zh_CN.properties:

~~~properties
nav.collapse=收起导航
nav.expand=展开导航
nav.empty=未找到工具
nav.accessible=工具导航
nav.search.accessible=搜索全部工具
~~~

Append to messages_en_US.properties:

~~~properties
nav.collapse=Collapse navigation
nav.expand=Expand navigation
nav.empty=No tools found
nav.accessible=Tool navigation
nav.search.accessible=Search all tools
~~~

- [ ] **Step 4: Implement the complete ToolSidebar**

Create ToolSidebar.java:

~~~java
package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyEvent;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class ToolSidebar extends JPanel {

    private enum Kind { ROOT, GROUP, TOOL, EMPTY }

    private static final class NavNode extends DefaultMutableTreeNode {
        private final Kind kind;
        private final String id;
        private final String label;

        private NavNode(Kind kind, String id, String label) {
            super(label);
            this.kind = kind;
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final ToolNavigationModel model;
    private final Consumer<String> selectionListener;
    private final JLabel titleLabel = new JLabel();
    private final JButton collapseButton = new JButton();
    private final JTextField searchField = new JTextField();
    private final JTree tree = new JTree();
    private final Timer filterTimer;
    private final LinkedHashSet<String> expandedGroupIds = new LinkedHashSet<>();
    private boolean inputComposing;
    private boolean rebuilding;
    private boolean settingSelection;
    private String selectedToolId;

    public ToolSidebar(
            ToolNavigationModel model,
            Consumer<String> selectionListener,
            Runnable collapseListener) {
        super(new BorderLayout(0, UIUtils.SPACE_SM));
        this.model = model;
        this.selectionListener = selectionListener;
        expandedGroupIds.addAll(model.getGroupIds());

        setBorder(new javax.swing.border.EmptyBorder(
                UIUtils.SPACE_MD, UIUtils.SPACE_MD,
                UIUtils.SPACE_MD, UIUtils.SPACE_MD));

        JPanel header = new JPanel(new BorderLayout(UIUtils.SPACE_SM, 0));
        titleLabel.setFont(UIUtils.titleFont().deriveFont(16f));
        collapseButton.setPreferredSize(new Dimension(32, 32));
        collapseButton.addActionListener(event -> collapseListener.run());
        collapseButton.setFocusPainted(false);
        header.add(titleLabel, BorderLayout.CENTER);
        header.add(collapseButton, BorderLayout.EAST);

        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.addInputMethodListener(new InputMethodListener() {
            @Override
            public void inputMethodTextChanged(InputMethodEvent event) {
                int textLength = event.getText() == null
                        ? 0
                        : event.getText().getEndIndex() - event.getText().getBeginIndex();
                inputComposing = textLength > event.getCommittedCharacterCount();
                if (!inputComposing) scheduleFilter();
            }

            @Override
            public void caretPositionChanged(InputMethodEvent event) {
            }
        });
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { scheduleFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { scheduleFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { scheduleFilter(); }
        });

        JPanel top = new JPanel(new BorderLayout(0, UIUtils.SPACE_SM));
        top.add(header, BorderLayout.NORTH);
        top.add(searchField, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UIUtils.NAV_ROW_HEIGHT);
        tree.setToggleClickCount(1);
        tree.setCellRenderer(new NavigationRenderer());
        tree.getSelectionModel().setSelectionMode(
                javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        ToolTipManager.sharedInstance().registerComponent(tree);

        tree.addTreeSelectionListener(event -> {
            NavNode node = selectedNode();
            if (node != null && node.kind == Kind.TOOL) {
                selectedToolId = node.id;
                if (!settingSelection) {
                    selectionListener.accept(node.id);
                }
            } else if (node != null && node.kind == Kind.EMPTY) {
                tree.clearSelection();
            }
        });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                rememberExpansion(event.getPath(), true);
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                rememberExpansion(event.getPath(), false);
            }
        });
        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);

        filterTimer = new Timer(120, event -> rebuildTree());
        filterTimer.setRepeats(false);
        installKeyboardActions();
        refreshLabels();
    }

    public void setSelectedTool(String toolId) {
        selectedToolId = toolId;
        TreePath path = findToolPath(toolId);
        if (path != null) {
            settingSelection = true;
            try {
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
            } finally {
                settingSelection = false;
            }
        }
    }

    public String getSelectedToolId() {
        return selectedToolId;
    }

    public void setExpandedGroupIds(Collection<String> groupIds) {
        expandedGroupIds.clear();
        for (String id : model.getGroupIds()) {
            if (groupIds.contains(id)) {
                expandedGroupIds.add(id);
            }
        }
        rebuildTree();
    }

    public Set<String> getExpandedGroupIds() {
        return new LinkedHashSet<>(expandedGroupIds);
    }

    public void refreshLabels() {
        titleLabel.setText(I18n.get("top.title"));
        titleLabel.setToolTipText(I18n.get("top.title"));
        collapseButton.setText("‹");
        collapseButton.setToolTipText(I18n.get("nav.collapse"));
        collapseButton.getAccessibleContext().setAccessibleName(I18n.get("nav.collapse"));
        searchField.putClientProperty(
                "JTextField.placeholderText", I18n.get("top.search.placeholder"));
        searchField.getAccessibleContext().setAccessibleName(
                I18n.get("nav.search.accessible"));
        tree.getAccessibleContext().setAccessibleName(I18n.get("nav.accessible"));
        rebuildTree();
    }

    public void focusSearch() {
        searchField.requestFocusInWindow();
        searchField.selectAll();
    }

    private void scheduleFilter() {
        if (!inputComposing && filterTimer != null) {
            filterTimer.restart();
        }
    }

    private void rebuildTree() {
        rebuilding = true;
        try {
            String query = searchField.getText();
            List<ToolNavigationModel.Group> groups = model.filter(query);
            NavNode root = new NavNode(Kind.ROOT, null, "");
            if (groups.isEmpty()) {
                root.add(new NavNode(Kind.EMPTY, null, I18n.get("nav.empty")));
            } else {
                for (ToolNavigationModel.Group group : groups) {
                    NavNode groupNode =
                            new NavNode(Kind.GROUP, group.getId(), group.getLabel());
                    for (ToolPanel tool : group.getTools()) {
                        groupNode.add(new NavNode(
                                Kind.TOOL, tool.getName(), tool.getLabel()));
                    }
                    root.add(groupNode);
                }
            }
            tree.setModel(new DefaultTreeModel(root));

            boolean filtering = query != null && !query.trim().isEmpty();
            for (int row = 0; row < tree.getRowCount(); row++) {
                TreePath path = tree.getPathForRow(row);
                NavNode node = node(path);
                if (node != null && node.kind == Kind.GROUP
                        && (filtering || expandedGroupIds.contains(node.id))) {
                    tree.expandPath(path);
                }
            }
            setSelectedTool(selectedToolId);
        } finally {
            rebuilding = false;
        }
    }

    private void installKeyboardActions() {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK),
                "nav.focusSearch");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "nav.clearSearch");
        getActionMap().put("nav.focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                focusSearch();
            }
        });
        getActionMap().put("nav.clearSearch", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!searchField.getText().isEmpty()) searchField.setText("");
                else tree.requestFocusInWindow();
            }
        });
        getActionMap().put("nav.openSelection", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                activateSelectedNode();
            }
        });

        searchField.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "nav.firstResult");
        searchField.getActionMap().put("nav.firstResult", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                selectFirstTool();
            }
        });
        tree.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "nav.openSelection");
        tree.getActionMap().put(
                "nav.openSelection", getActionMap().get("nav.openSelection"));
    }

    private void selectFirstTool() {
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            NavNode node = node(path);
            if (node != null && node.kind == Kind.TOOL) {
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
                tree.requestFocusInWindow();
                return;
            }
        }
    }

    private void activateSelectedNode() {
        TreePath path = tree.getSelectionPath();
        NavNode node = node(path);
        if (node == null) return;
        if (node.kind == Kind.TOOL) {
            selectedToolId = node.id;
            selectionListener.accept(node.id);
        } else if (node.kind == Kind.GROUP) {
            if (tree.isExpanded(path)) tree.collapsePath(path);
            else tree.expandPath(path);
        }
    }

    private void rememberExpansion(TreePath path, boolean expanded) {
        if (rebuilding || !searchField.getText().trim().isEmpty()) return;
        NavNode node = node(path);
        if (node == null || node.kind != Kind.GROUP) return;
        if (expanded) expandedGroupIds.add(node.id);
        else expandedGroupIds.remove(node.id);
    }

    private TreePath findToolPath(String toolId) {
        if (toolId == null) return null;
        Object rootObject = tree.getModel().getRoot();
        if (!(rootObject instanceof NavNode)) return null;
        Enumeration<?> nodes = ((NavNode) rootObject).depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            Object value = nodes.nextElement();
            if (value instanceof NavNode) {
                NavNode node = (NavNode) value;
                if (node.kind == Kind.TOOL && toolId.equals(node.id)) {
                    return new TreePath(node.getPath());
                }
            }
        }
        return null;
    }

    private NavNode selectedNode() {
        return node(tree.getSelectionPath());
    }

    private static NavNode node(TreePath path) {
        if (path == null || !(path.getLastPathComponent() instanceof NavNode)) {
            return null;
        }
        return (NavNode) path.getLastPathComponent();
    }

    private final class NavigationRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
                JTree source, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean focused) {
            JLabel label = (JLabel) super.getTreeCellRendererComponent(
                    source, value, selected, expanded, leaf, row, focused);
            setLeafIcon(null);
            setOpenIcon(null);
            setClosedIcon(null);
            NavNode node = value instanceof NavNode ? (NavNode) value : null;
            label.setFont(node != null && node.kind == Kind.GROUP
                    ? UIUtils.plainFont().deriveFont(Font.BOLD)
                    : UIUtils.plainFont());
            label.setEnabled(node == null || node.kind != Kind.EMPTY);
            label.setToolTipText(node == null ? null : node.label);
            return label;
        }
    }
}
~~~

Keep the callback activation separate from plain tree selection: arrow-key movement changes focus, while Enter or a mouse click opens the selected tool. Use only Java 8 APIs; Ctrl+K is bound with InputEvent.CTRL_DOWN_MASK.

- [ ] **Step 5: Run the sidebar tests**

Run:

~~~powershell
mvn -Dtest=ToolSidebarTest test
~~~

Expected: 6 tests pass with 0 failures and 0 errors.

- [ ] **Step 6: Run all new UI unit tests together**

Run:

~~~powershell
mvn "-Dtest=ToolNavigationModelTest,ToolNavigationStateTest,ToolContentHostTest,ToolSidebarTest,MainFrameStructureTest,BpmnPanelLazyInitializationTest" test
~~~

Expected: 15 tests pass with 0 failures and 0 errors.

- [ ] **Step 7: Commit the sidebar**

~~~powershell
git add src/main/java/com/aqishi/toolbox/ui/ToolSidebar.java src/main/resources/com/aqishi/toolbox/util/messages.properties src/main/resources/com/aqishi/toolbox/util/messages_zh_CN.properties src/main/resources/com/aqishi/toolbox/util/messages_en_US.properties src/test/java/com/aqishi/toolbox/ui/ToolSidebarTest.java
git commit -m "feat(ui): add searchable grouped sidebar"
~~~

### Task 5: Replace MainFrame tabs with the workbench shell

**Files:**
- Modify: src/main/java/com/aqishi/toolbox/ui/MainFrame.java
- Modify: src/main/java/com/aqishi/toolbox/misc/BpmnPanel.java
- Test: src/test/java/com/aqishi/toolbox/ui/MainFrameStructureTest.java
- Test: src/test/java/com/aqishi/toolbox/misc/BpmnPanelLazyInitializationTest.java

- [ ] **Step 1: Record the baseline smoke check**

Run:

~~~powershell
mvn test
~~~

Expected before the MainFrame refactor: the existing test suite passes. Save the exact test count in the implementation notes.

Before changing MainFrame, add MainFrameStructureTest to require ToolSidebar and ToolContentHost and reject JTabbedPane. Run it once and confirm it fails against the old shell. Add BpmnPanelLazyInitializationTest when the first lazy-load smoke test exposes the constructor-scheduled canvas access; confirm it captures the NullPointerException before moving adjustCanvasSize into build() after canvasPanel is initialized.

- [ ] **Step 2: Replace obsolete navigation fields**

Keep statusLabel, topThemeLabel, topLangLabel, statusTimer, and tools. Remove topTitleLabel, searchField, tabs, all three group maps, searchUpdateTimer, and searchInputComposing. Add:

~~~java
private JLabel currentToolLabel;
private JButton expandSidebarButton;
private ToolNavigationModel navigationModel;
private ToolSidebar sidebar;
private ToolContentHost contentHost;
private JSplitPane workspaceSplit;
private String currentToolId;
private int expandedSidebarWidth;
private boolean sidebarCollapsed;
~~~

Remove imports for InputMethodEvent, InputMethodListener, LinkedHashMap, and Map after the old navigation methods are deleted. Add imports for InputEvent and KeyEvent for the root-pane Ctrl+K binding.

- [ ] **Step 3: Initialize the model and persist navigation state on close**

Immediately after createTools() in the constructor:

~~~java
navigationModel = new ToolNavigationModel(java.util.Arrays.asList(tools));
~~~

Before ConfigManager.save() in windowClosing:

~~~java
persistNavigationState();
~~~

Add:

~~~java
private void persistNavigationState() {
    ConfigManager.set("nav.selectedTool", currentToolId == null ? "" : currentToolId);
    ConfigManager.setInt("nav.sidebarWidth", expandedSidebarWidth);
    ConfigManager.set("nav.sidebarCollapsed", Boolean.toString(sidebarCollapsed));
    ConfigManager.set(
            "nav.expandedGroups",
            ToolNavigationState.serializeExpandedGroups(sidebar.getExpandedGroupIds()));
}
~~~

- [ ] **Step 4: Replace initUI, buildTopBar, search-popup methods, buildCenter, buildGroupTab, refreshNavigationLabels, and selectTool**

Use this shell flow:

~~~java
private void initUI() {
    setLayout(new BorderLayout(0, 0));
    add(buildWorkbench(), BorderLayout.CENTER);
    add(buildStatusBar(), BorderLayout.SOUTH);
    installGlobalShortcuts();

    String restoredId = ToolNavigationState.resolveToolId(
            navigationModel,
            ConfigManager.get("nav.selectedTool", null));
    java.util.Set<String> expandedGroups = ToolNavigationState.parseExpandedGroups(
            ConfigManager.get("nav.expandedGroups", null),
            navigationModel.getGroupIds());
    sidebar.setExpandedGroupIds(expandedGroups);
    selectTool(restoredId);
    sidebar.setSelectedTool(restoredId);

    expandedSidebarWidth = ToolNavigationState.clampSidebarWidth(
            ConfigManager.getInt("nav.sidebarWidth", UIUtils.SIDEBAR_DEFAULT_WIDTH));
    boolean restoredCollapsed = Boolean.parseBoolean(
            ConfigManager.get("nav.sidebarCollapsed", "false"));
    sidebarCollapsed = false;
    SwingUtilities.invokeLater(() -> {
        workspaceSplit.setDividerLocation(expandedSidebarWidth);
        setSidebarCollapsed(restoredCollapsed);
    });
}

private JComponent buildWorkbench() {
    sidebar = new ToolSidebar(
            navigationModel,
            this::selectTool,
            () -> setSidebarCollapsed(true));
    contentHost = new ToolContentHost(java.util.Arrays.asList(tools));

    JPanel contentArea = new JPanel(new BorderLayout(0, 0));
    contentArea.add(buildToolBar(), BorderLayout.NORTH);
    contentArea.add(contentHost, BorderLayout.CENTER);

    workspaceSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, contentArea);
    workspaceSplit.setBorder(null);
    workspaceSplit.setContinuousLayout(true);
    workspaceSplit.setResizeWeight(0.0);
    workspaceSplit.setDividerSize(UIUtils.WORKBENCH_DIVIDER_SIZE);
    workspaceSplit.addPropertyChangeListener(
            JSplitPane.DIVIDER_LOCATION_PROPERTY,
            event -> {
                if (!sidebarCollapsed && workspaceSplit.getDividerLocation() > 0) {
                    expandedSidebarWidth = ToolNavigationState.clampSidebarWidth(
                            workspaceSplit.getDividerLocation());
                }
            });
    return workspaceSplit;
}

private void selectTool(String toolId) {
    ToolPanel tool = navigationModel.findTool(toolId);
    if (tool == null || !contentHost.showTool(toolId)) {
        return;
    }
    currentToolId = toolId;
    ConfigManager.set("nav.selectedTool", toolId);
    updateCurrentToolLabel();
}

private void updateCurrentToolLabel() {
    ToolPanel tool = navigationModel.findTool(currentToolId);
    currentToolLabel.setText(
            tool == null ? "" : tool.getGroupLabel() + " / " + tool.getLabel());
    currentToolLabel.setToolTipText(currentToolLabel.getText());
}

private void setSidebarCollapsed(boolean collapsed) {
    if (workspaceSplit == null) return;
    if (collapsed && !sidebarCollapsed) {
        expandedSidebarWidth = ToolNavigationState.clampSidebarWidth(
                workspaceSplit.getDividerLocation());
    }
    sidebarCollapsed = collapsed;
    sidebar.setVisible(!collapsed);
    workspaceSplit.setDividerSize(collapsed ? 0 : UIUtils.WORKBENCH_DIVIDER_SIZE);
    workspaceSplit.setDividerLocation(collapsed ? 0 : expandedSidebarWidth);
    expandSidebarButton.setVisible(collapsed);
    ConfigManager.set("nav.sidebarCollapsed", Boolean.toString(collapsed));
    workspaceSplit.revalidate();
    workspaceSplit.repaint();
}

private void installGlobalShortcuts() {
    JRootPane root = getRootPane();
    root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke(
                    KeyEvent.VK_K,
                    InputEvent.CTRL_DOWN_MASK),
            "nav.focusSearch");
    root.getActionMap().put("nav.focusSearch", new AbstractAction() {
        @Override
        public void actionPerformed(java.awt.event.ActionEvent event) {
            if (sidebarCollapsed) {
                setSidebarCollapsed(false);
            }
            SwingUtilities.invokeLater(sidebar::focusSearch);
        }
    });
}
~~~

- [ ] **Step 5: Build the compact current-tool toolbar**

Add a buildToolBar method based on the old theme/language listeners:

~~~java
private JComponent buildToolBar() {
    JPanel bar = new JPanel(new BorderLayout(UIUtils.SPACE_SM, 0));
    bar.setBorder(BorderFactory.createCompoundBorder(
            new MatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
            new EmptyBorder(UIUtils.SPACE_SM, UIUtils.SPACE_MD,
                    UIUtils.SPACE_SM, UIUtils.SPACE_MD)));

    JPanel location = new JPanel(new BorderLayout(UIUtils.SPACE_SM, 0));
    expandSidebarButton = new JButton("☰");
    expandSidebarButton.setToolTipText(I18n.get("nav.expand"));
    expandSidebarButton.getAccessibleContext().setAccessibleName(I18n.get("nav.expand"));
    expandSidebarButton.addActionListener(event -> setSidebarCollapsed(false));
    expandSidebarButton.setVisible(false);
    currentToolLabel = new JLabel();
    currentToolLabel.setFont(UIUtils.titleFont());
    location.add(expandSidebarButton, BorderLayout.WEST);
    location.add(currentToolLabel, BorderLayout.CENTER);
    bar.add(location, BorderLayout.CENTER);

    JPanel settings = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIUtils.SPACE_XS, 0));
    topThemeLabel = new JLabel(I18n.get("top.theme"));
    JComboBox<String> themeBox = new JComboBox<>(ThemeManager.names());
    themeBox.setSelectedItem(ThemeManager.current().name);
    themeBox.setPreferredSize(new Dimension(160, 32));
    themeBox.addActionListener(event -> {
        ThemeManager.apply((String) themeBox.getSelectedItem());
        SwingUtilities.invokeLater(() -> {
            if (!sidebarCollapsed) workspaceSplit.setDividerLocation(expandedSidebarWidth);
        });
    });
    settings.add(topThemeLabel);
    settings.add(themeBox);

    topLangLabel = new JLabel(I18n.get("top.lang"));
    JComboBox<String> langBox = new JComboBox<>(new String[]{"简体中文", "English"});
    langBox.setSelectedIndex(
            "en_US".equals(ConfigManager.get("locale", "zh_CN")) ? 1 : 0);
    langBox.setPreferredSize(new Dimension(100, 32));
    langBox.addActionListener(event -> {
        String target = "English".equals(langBox.getSelectedItem()) ? "en_US" : "zh_CN";
        if (!target.equals(ConfigManager.get("locale", "zh_CN"))) {
            ConfigManager.set("locale", target);
            ConfigManager.save();
            I18n.init();
            reloadInPlace();
        }
    });
    settings.add(topLangLabel);
    settings.add(langBox);
    bar.add(settings, BorderLayout.EAST);
    return bar;
}
~~~

- [ ] **Step 6: Refresh localized shell labels in place**

Replace reload and refreshNavigationLabels with:

~~~java
private void reloadInPlace() {
    setTitle(I18n.get("app.title"));
    topThemeLabel.setText(I18n.get("top.theme"));
    topLangLabel.setText(I18n.get("top.lang"));
    expandSidebarButton.setToolTipText(I18n.get("nav.expand"));
    expandSidebarButton.getAccessibleContext().setAccessibleName(I18n.get("nav.expand"));
    sidebar.refreshLabels();
    sidebar.setSelectedTool(currentToolId);
    updateCurrentToolLabel();
    revalidate();
    repaint();
}
~~~

Delete the obsolete static reload helper. Keep buildStatusBar, updateStatusBar, formatBytes, screenshot support, window geometry persistence, tool creation order, and the complete creator array unchanged.

- [ ] **Step 7: Compile and run focused UI tests**

Run:

~~~powershell
mvn -DskipTests compile
mvn "-Dtest=ToolNavigationModelTest,ToolNavigationStateTest,ToolContentHostTest,ToolSidebarTest" test
~~~

Expected: both commands exit 0; the focused UI suite reports 17 passing tests.

- [ ] **Step 8: Launch the packaged application for the first smoke check**

Run:

~~~powershell
mvn -DskipTests package
java -Xmx512m -Dfile.encoding=UTF-8 -jar target/java-toolbox.jar
~~~

Verify manually:

1. Every original group appears in the sidebar in source order.
2. Every original tool appears once.
3. Selecting tools changes content without rebuilding a previously opened view.
4. Search, Ctrl+K, arrows, Enter, and Escape work; Ctrl+K expands a collapsed sidebar before focusing search.
5. Sidebar resizing and collapse/expand work.
6. Theme and language changes preserve the current tool.
7. Status text still updates.

- [ ] **Step 9: Commit the shell integration**

~~~powershell
git add src/main/java/com/aqishi/toolbox/ui/MainFrame.java
git commit -m "feat(ui): replace tabs with workbench sidebar"
~~~

### Task 6: Documentation, complete regression suite, and visual acceptance

**Files:**
- Create: DESIGN.md
- Modify: README.md
- Modify: screenshots/overview.png

- [ ] **Step 1: Write DESIGN.md from the implemented values**

Record the shipped product register and actual values:

~~~markdown
# Design System

## Product register

Product UI for developers and operations users working in a Java Swing desktop client.

## Layout

- One grouped navigation sidebar and one content surface.
- Sidebar width: 228px default, 190px minimum, 320px maximum.
- Sidebar can be collapsed and its state is persisted.
- Current-tool toolbar remains visible above content.
- Status information remains in the bottom status bar.

## Spacing and density

- Spacing scale: 4px, 8px, 12px, 16px.
- Navigation row height: 32px.
- Interactive controls: at least 32px high.
- Content panels keep their existing UIUtils.CONTENT_PADDING unless individually redesigned.

## Color and theme

- Colors come from FlatLaf and UIManager.
- Selection, focus, disabled, error, warning, and success states follow the active theme.
- No hard-coded light or dark shell colors.

## Typography

- Use the existing platform/FlatLaf label font.
- Current tool and group labels use weight for hierarchy.
- Tool names truncate with a tooltip when horizontal space is limited.

## Interaction

- Ctrl+K focuses search.
- Arrow keys move through results.
- Enter opens a tool.
- Escape clears search.
- Search filters navigation without destroying tool views.

## Accessibility

- Keyboard focus remains visible.
- Selection is not communicated by color alone.
- Search and navigation expose localized accessible names.
- Chinese and English labels update without changing stable tool identity.
~~~

- [ ] **Step 2: Update README navigation descriptions**

Change the “界面布局” diagram to the approved sidebar workbench diagram. Change the project-structure description of MainFrame from “8 大页签 + 搜索 + 主题” to “统一侧边导航 + 搜索 + 主题/语言 + 内容工作台”. Add ToolNavigationModel, ToolNavigationState, ToolSidebar, and ToolContentHost under the ui directory.

- [ ] **Step 3: Run the full automated verification**

Run:

~~~powershell
mvn clean test
mvn clean package
~~~

Expected: both commands exit 0, Surefire reports 0 failures and 0 errors, and target/java-toolbox.jar is produced.

- [ ] **Step 4: Perform the four visual acceptance combinations**

Launch target/java-toolbox.jar and capture:

1. Default window size, Simplified Chinese, a light FlatLaf theme.
2. Default window size, English, a dark FlatLaf theme.
3. 820×520 minimum window, Simplified Chinese, a light FlatLaf theme.
4. 820×520 minimum window, English, a dark FlatLaf theme.

For every combination verify that navigation, search, current-tool label, theme, language, content, and status remain reachable. Verify long English labels truncate with tooltips and do not displace the theme/language controls.

- [ ] **Step 5: Replace the README overview screenshot**

Save the verified default-size Simplified Chinese light-theme image to screenshots/overview.png. Inspect the image at original resolution before accepting it; it must show the unified sidebar, current-tool toolbar, content area, and status bar without unrelated windows.

- [ ] **Step 6: Confirm feature inventory and repository hygiene**

Run:

~~~powershell
git diff --check
git status --short
rg -n "new JTabbedPane|groupListMap|groupCardMap|groupContentMap|searchPopup" src/main/java/com/aqishi/toolbox/ui/MainFrame.java
~~~

Expected: git diff --check exits 0; the obsolete-navigation search returns no matches; git status lists only the intended documentation and screenshot changes at this point.

Compare the unchanged MainFrame creator array against the original list in commit ac54adb. Confirm that all tool constructors remain present and in the same order.

- [ ] **Step 7: Commit documentation and final screenshot**

~~~powershell
git add DESIGN.md README.md screenshots/overview.png
git commit -m "docs: document workbench navigation"
~~~

- [ ] **Step 8: Run final verification after all commits**

Run:

~~~powershell
mvn clean package
git status --short
git log -6 --oneline
~~~

Expected: Maven exits 0 with all tests passing, the shaded jar is created, git status is empty, and the log contains the navigation model, state, content host, sidebar, shell integration, and documentation commits.
