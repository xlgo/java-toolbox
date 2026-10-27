package com.aqishi.toolbox.ui.kit;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CardTest {

    @Test
    void testCollapsibleCardBasics() {
        Card card = Card.collapsible("配置面板");
        assertTrue(card.isCollapsible());
        assertFalse(card.isCollapsed());

        JLabel contentLabel = new JLabel("详细配置内容");
        card.setContent(contentLabel);

        JLabel footerLabel = new JLabel("状态栏");
        card.setFooter(footerLabel);

        AtomicBoolean listenerTriggered = new AtomicBoolean(false);
        card.addCollapseListener(listenerTriggered::set);

        // 折叠卡片
        card.setCollapsed(true);
        assertTrue(card.isCollapsed());
        assertTrue(listenerTriggered.get());
        assertFalse(card.getContent().isVisible());

        // 展开卡片
        card.setCollapsed(false);
        assertFalse(card.isCollapsed());
        assertFalse(listenerTriggered.get());
        assertTrue(card.getContent().isVisible());

        // toggleCollapsed
        card.toggleCollapsed();
        assertTrue(card.isCollapsed());
        assertTrue(listenerTriggered.get());
    }

    @Test
    void testHeaderActionPreservedOnCollapse() {
        Card card = Card.titled("服务配置").setCollapsible(true);
        JButton connectBtn = new JButton("连接");
        card.addHeaderAction(connectBtn);

        card.setContent(new JPanel());
        card.setCollapsed(true);

        assertTrue(card.isCollapsed());
        // 关键操作在折叠时依然可见可用
        assertTrue(connectBtn.isVisible());
    }
}
