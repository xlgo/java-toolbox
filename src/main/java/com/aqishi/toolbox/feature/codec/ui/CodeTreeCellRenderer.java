package com.aqishi.toolbox.feature.codec.ui;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;

/**
 * 折叠树行内渲染器：根据节点展开状态切换 {@link CodeFolderNode} 的显示文本，
 * 并去掉默认图标与边框，使树与编辑器界面融为一体。
 */
class CodeTreeCellRenderer extends DefaultTreeCellRenderer {

    CodeTreeCellRenderer() {
        setOpenIcon(null);
        setClosedIcon(null);
        setLeafIcon(null);
        setBackgroundNonSelectionColor(new Color(0, 0, 0, 0));
        setBorderSelectionColor(new Color(0, 0, 0, 0));
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                  boolean expanded, boolean leaf, int row, boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

        if (value instanceof DefaultMutableTreeNode) {
            Object userObj = ((DefaultMutableTreeNode) value).getUserObject();
            if (userObj instanceof CodeFolderNode) {
                CodeFolderNode node = (CodeFolderNode) userObj;
                setText(expanded ? node.openText : node.closeText);
            }
        }

        // 选中行高亮着色，保持温和的前背景色
        if (sel) {
            setBackground(UIManager.getColor("List.selectionBackground"));
            setForeground(UIManager.getColor("List.selectionForeground"));
        } else {
            setBackground(null);
            setForeground(null);
        }
        return this;
    }
}
