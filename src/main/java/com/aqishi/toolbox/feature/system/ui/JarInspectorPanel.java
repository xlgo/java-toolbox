package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.system.application.ClassSearchService;
import com.aqishi.toolbox.feature.system.domain.ClassFileParser;
import com.aqishi.toolbox.feature.system.domain.JarInspector;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

/**
 * JAR / Class 分析面板：查看单个 class 的版本与签名、分析 JAR/WAR/EAR、跨 JAR 找类与检测重复类。
 *
 * <p>三个页签各自持有后台任务与“代次”序号，结果回到 EDT 时序号过期就丢弃；
 * {@link #closeResources()} 统一作废并取消所有任务。</p>
 */
public class JarInspectorPanel extends ToolPanel implements ManagedResourceOwner {

    static final int TAB_CLASS = 0;
    static final int TAB_JAR = 1;
    static final int TAB_SEARCH = 2;

    private final ClassFileParser parser;
    private final JarInspector inspector;
    private final ClassSearchService searchService;

    private JTabbedPane tabs;
    private ClassFileTab classTab;
    private JarTab jarTab;
    private ClassSearchTab searchTab;

    public JarInspectorPanel() {
        this(ToolCatalog.JAR_INSPECTOR, new ClassFileParser(), new JarInspector(), new ClassSearchService());
    }

    public JarInspectorPanel(ToolDescriptor descriptor, ClassFileParser parser, JarInspector inspector,
                             ClassSearchService searchService) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.parser = Objects.requireNonNull(parser, "parser");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.searchService = Objects.requireNonNull(searchService, "searchService");
    }

    @Override
    protected JComponent build() {
        classTab = new ClassFileTab(parser);
        jarTab = new JarTab(inspector);
        searchTab = new ClassSearchTab(searchService);

        tabs = new JTabbedPane();
        tabs.addTab(I18n.get("tool.jarinspector.tab.class"), classTab.component());
        tabs.addTab(I18n.get("tool.jarinspector.tab.jar"), jarTab.component());
        tabs.addTab(I18n.get("tool.jarinspector.tab.search"), searchTab.component());

        JPanel root = Layouts.page();
        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    /** 包内可见：测试直接驱动各页签。 */
    JTabbedPane tabs() {
        return tabs;
    }

    ClassFileTab classTab() {
        return classTab;
    }

    JarTab jarTab() {
        return jarTab;
    }

    ClassSearchTab searchTab() {
        return searchTab;
    }

    @Override
    public void closeResources() {
        if (classTab != null) {
            classTab.close();
        }
        if (jarTab != null) {
            jarTab.close();
        }
        if (searchTab != null) {
            searchTab.close();
        }
    }
}
