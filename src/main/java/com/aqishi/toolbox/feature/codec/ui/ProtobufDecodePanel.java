package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.codec.domain.ProtobufRawDecoder;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;

/**
 * Protobuf 原始解码工具面板（无需 .proto 定义文件）。
 *
 * <p>支持 Hex 与 Base64 输入，自动还原字段编号、WireType、Varint/ZigZag、
 * 嵌套 Message、UTF-8 字符串与浮点数。</p>
 */
public final class ProtobufDecodePanel extends ToolPanel implements ManagedResourceOwner {

    // 示例 Hex：
    // field 1 (varint): 150
    // field 2 (string): "testing"
    // field 3 (message): { field 1 (varint): 42 }
    private static final String SAMPLE =
            "08 96 01 12 07 74 65 73 74 69 6e 67 1a 02 08 2a";

    private TextWorkbench workbench;

    public ProtobufDecodePanel() {
        super(ToolCatalog.PROTOBUF_DECODE);
    }

    @Override
    protected JComponent build() {
        FormGrid options = new FormGrid();
        options.fullRow(Fields.note(I18n.get("proto.hint")));

        workbench = new TextWorkbench(
                options,
                () -> {
                    String input = workbench.input.getText();
                    return () -> new ProtobufRawDecoder().decode(input);
                },
                SAMPLE
        );

        JPanel root = Layouts.page();
        root.add(workbench);
        return root;
    }

    @Override
    public void closeResources() {
        if (workbench != null) {
            workbench.closeResources();
        }
    }
}
