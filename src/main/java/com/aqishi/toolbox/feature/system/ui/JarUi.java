package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.ClassFileInfo;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.InputEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * JAR / Class 分析面板各页签共用的小工具：只读表格模型、表格工厂、文件拖放、格式化。
 */
final class JarUi {

    private JarUi() {
    }

    /** 以对象列表为行的只读表格模型；整表替换只触发一次刷新。 */
    static final class RowsModel<T> extends AbstractTableModel {
        private final List<String> names = new ArrayList<>();
        private final List<Class<?>> types = new ArrayList<>();
        private final List<Function<T, Object>> getters = new ArrayList<>();
        private List<T> rows = List.of();

        RowsModel<T> column(String name, Class<?> type, Function<T, Object> getter) {
            names.add(name);
            types.add(type);
            getters.add(getter);
            return this;
        }

        void setRows(List<T> newRows) {
            rows = newRows == null ? List.of() : List.copyOf(newRows);
            fireTableDataChanged();
        }

        List<T> rows() {
            return rows;
        }

        T rowAt(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return names.size();
        }

        @Override
        public String getColumnName(int column) {
            return names.get(column);
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return types.get(column);
        }

        @Override
        public Object getValueAt(int row, int column) {
            return getters.get(column).apply(rows.get(row));
        }
    }

    static JTable table(RowsModel<?> model) {
        JTable table = new JTable(model);
        table.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        return table;
    }

    /** 选中行变化时回调模型行（已换算排序后的下标）。 */
    static <T> void onSelect(JTable table, RowsModel<T> model, Consumer<T> handler) {
        table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            int view = table.getSelectedRow();
            if (view < 0) {
                return;
            }
            int row = table.convertRowIndexToModel(view);
            if (row >= 0 && row < model.getRowCount()) {
                handler.accept(model.rowAt(row));
            }
        });
    }

    static JTextArea detailArea() {
        JTextArea area = com.aqishi.toolbox.ui.kit.Fields.output(8, 30);
        area.setLineWrap(false);
        return area;
    }

    /** {@code 21 (65)}；没有类时为 {@code -}。 */
    static String releaseLabel(int major) {
        return major <= 0 ? "-" : ClassFileInfo.releaseName(major) + " (" + major + ")";
    }

    static String formatSize(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    /**
     * 给容器及其中的文本框、表格、列表装上文件拖放。
     *
     * <p>这些组件原本就有自己的 TransferHandler（复制、粘贴），直接替换会让 Ctrl+C 失效；
     * 这里包一层：文件列表归我们处理，其他一律交还原来的 handler。</p>
     */
    static void installDrop(Container root, Consumer<List<File>> consumer) {
        if (root instanceof JComponent) {
            JComponent component = (JComponent) root;
            if (component instanceof JPanel || component instanceof javax.swing.text.JTextComponent
                    || component instanceof JTable || component instanceof JList) {
                component.setTransferHandler(new FileDropHandler(component.getTransferHandler(), consumer));
            }
        }
        for (Component child : root.getComponents()) {
            if (child instanceof Container) {
                installDrop((Container) child, consumer);
            }
        }
    }

    static final class FileDropHandler extends TransferHandler {
        private final TransferHandler delegate;
        private final Consumer<List<File>> consumer;

        FileDropHandler(TransferHandler delegate, Consumer<List<File>> consumer) {
            this.delegate = delegate instanceof FileDropHandler ? ((FileDropHandler) delegate).delegate : delegate;
            this.consumer = consumer;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return true;
            }
            return delegate != null && delegate.canImport(support);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return delegate != null && delegate.importData(support);
            }
            try {
                List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                if (files == null || files.isEmpty()) {
                    return false;
                }
                consumer.accept(files);
                return true;
            } catch (UnsupportedFlavorException | IOException unreadable) {
                return false;
            }
        }

        @Override
        public int getSourceActions(JComponent component) {
            return delegate == null ? NONE : delegate.getSourceActions(component);
        }

        @Override
        public void exportToClipboard(JComponent component, Clipboard clipboard, int action) {
            if (delegate != null) {
                delegate.exportToClipboard(component, clipboard, action);
            }
        }

        @Override
        public void exportAsDrag(JComponent component, InputEvent event, int action) {
            if (delegate != null) {
                delegate.exportAsDrag(component, event, action);
            }
        }
    }
}
