package com.aqishi.toolbox.feature.codec.ui;

/**
 * 折叠树节点载体：同时携带「展开态」与「折叠态」两份 HTML 文本，
 * 由 {@link CodeTreeCellRenderer} 根据节点展开状态切换显示。
 */
class CodeFolderNode {
    final String openText;
    final String closeText;

    CodeFolderNode(String openText, String closeText) {
        this.openText = openText;
        this.closeText = closeText;
    }

    @Override
    public String toString() {
        return openText;
    }
}
