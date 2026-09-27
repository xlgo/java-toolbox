package com.aqishi.toolbox.feature.network.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 发送历史（最多 {@link #MAX} 条，最新在前）与类似 shell 的上下翻看游标。
 */
final class SocketSendHistory {

    static final int MAX = 50;

    private final List<String> items = new ArrayList<>();
    /** -1 表示不在翻看中；否则指向 items 中当前显示的条目。 */
    private int cursor = -1;
    /** 开始翻看前输入框里的草稿，翻回最底部时恢复。 */
    private String draft = "";

    void add(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        items.remove(text);
        items.add(0, text);
        while (items.size() > MAX) {
            items.remove(items.size() - 1);
        }
        cursor = -1;
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    List<String> items() {
        return new ArrayList<>(items);
    }

    /** 往更早翻；已到最早时停在原地。current 是当前输入，第一次翻看时存为草稿。 */
    String previous(String current) {
        if (items.isEmpty()) {
            return null;
        }
        if (cursor < 0) {
            draft = current == null ? "" : current;
        }
        if (cursor < items.size() - 1) {
            cursor++;
        }
        return items.get(cursor);
    }

    /** 往更新翻；越过最新一条时返回草稿并结束翻看。 */
    String next() {
        if (cursor < 0) {
            return null;
        }
        cursor--;
        return cursor < 0 ? draft : items.get(cursor);
    }
}
