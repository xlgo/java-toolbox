package com.aqishi.toolbox.feature.generation.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.feature.generation.domain.IdCodecException;
import com.aqishi.toolbox.feature.generation.domain.IdDetector;
import com.aqishi.toolbox.feature.generation.domain.KsuidCodec;
import com.aqishi.toolbox.feature.generation.domain.NanoIdGenerator;
import com.aqishi.toolbox.feature.generation.domain.ObjectIdCodec;
import com.aqishi.toolbox.feature.generation.domain.SnowflakeCodec;
import com.aqishi.toolbox.feature.generation.domain.SnowflakeLayout;
import com.aqishi.toolbox.feature.generation.domain.UlidCodec;
import com.aqishi.toolbox.feature.generation.domain.UuidCodec;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/**
 * 分布式 ID 工具：识别解码、批量生成、雪花位布局设计。
 *
 * <p>与「数据生成器」里的 UUID 页签不同，这里关注的是<em>带时间信息</em>的分布式 ID：
 * 粘贴任意 ID 能看出它是什么、何时生成、由哪个节点生成。</p>
 */
public class IdToolkitPanel extends ToolPanel implements ManagedResourceOwner {

    static final int MAX_DECODE_LINES = 2000;
    private static final int DEBOUNCE_MILLIS = 250;
    private static final int MAX_COUNT = 10_000;
    private static final double MILLIS_PER_YEAR = 365.2425 * 86_400_000d;
    private static final DateTimeFormatter LOCAL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 解码页「示例」按钮填入的样例：均为各格式的公开参考值 */
    private static final String EXAMPLES = String.join("\n",
            "1780483424564006913",
            "175928847299117063",
            "017F22E2-79B0-7CC3-98C4-DC0C0C07398F",
            "C232AB00-9414-11EC-B3C8-9F6BDECED846",
            "01ARYZ6S41TSV4RRFFQ69G5FAV",
            "507f1f77bcf86cd799439011",
            "0ujtsYcgvSTl8PAuAdqWYSMnLOv",
            "1727000000000");

    /** 表格「字段」列里省略的细节：与时间列重复，或太长不适合摘要 */
    private static final Set<String> SUMMARY_EXCLUDED = Set.of(
            IdDetector.D_CANONICAL, IdDetector.D_HEX, IdDetector.D_EPOCH, IdDetector.D_UUID, IdDetector.D_ULID,
            IdDetector.D_GREGORIAN, IdDetector.D_MILLIS, IdDetector.D_SECONDS, IdDetector.D_KSUID_SECONDS,
            IdDetector.D_VARIANT, IdDetector.D_TICKS, IdDetector.D_PAYLOAD, IdDetector.D_RANDOMNESS);

    /** 生成类型 */
    enum GenType {
        SNOWFLAKE("snowflake"), UUID_V4("uuidV4"), UUID_V7("uuidV7"), UUID_V5("uuidV5"), UUID_V3("uuidV3"),
        UUID_V1("uuidV1"), UUID_V6("uuidV6"), ULID("ulid"), OBJECT_ID("objectId"), KSUID("ksuid"),
        NANOID("nanoid");

        final String id;

        GenType(String id) {
            this.id = id;
        }

        boolean isUuid() {
            return name().startsWith("UUID");
        }

        boolean isNameBased() {
            return this == UUID_V5 || this == UUID_V3;
        }
    }

    private static final String CARD_SNOWFLAKE = "snowflake";
    private static final String CARD_NAME = "name";
    private static final String CARD_ULID = "ulid";
    private static final String CARD_NANOID = "nanoid";
    private static final String CARD_NONE = "none";

    private final UuidCodec uuidCodec = new UuidCodec();
    private final UlidCodec ulidCodec = new UlidCodec();
    private final ObjectIdCodec objectIdCodec = new ObjectIdCodec();
    private final KsuidCodec ksuidCodec = new KsuidCodec();
    private final NanoIdGenerator nanoIdGenerator = new NanoIdGenerator();
    private final List<SnowflakeLayout.Preset> knownPresets = SnowflakeLayout.Preset.known();

    // ---------------------------------------------------------------- 解码页
    private JTextArea decodeInput;
    private JComboBox<String> zoneCombo;
    private final DecodeTableModel decodeModel = new DecodeTableModel();
    private JTable decodeTable;
    private JTextArea decodeDetail;
    private JLabel decodeStatus;
    private Timer debounce;

    // ---------------------------------------------------------------- 生成页
    private JComboBox<String> genTypeCombo;
    private CardLayout genOptionsLayout;
    private JPanel genOptionsCards;
    private JComboBox<String> genPresetCombo;
    private JPanel genNodeHost;
    private final Map<String, JSpinner> genNodeSpinners = new LinkedHashMap<>();
    private final Map<String, Long> genNodeMemory = new LinkedHashMap<>();
    private JLabel genSnowflakeCaption;
    private JComboBox<String> genNamespaceCombo;
    private JTextField genCustomNamespace;
    private JTextArea genNames;
    private JCheckBox genMonotonic;
    private JTextField genAlphabet;
    private JSpinner genNanoSize;
    private JLabel genNanoCaption;
    private JSpinner genCount;
    private JCheckBox genUpper;
    private JCheckBox genNoHyphens;
    private JTextArea genOutput;
    private JLabel genStatus;
    private JButton genButton;
    private SnowflakeCodec.Generator snowflakeGenerator;
    private Map<String, Long> snowflakeGeneratorNodes;
    private SwingWorker<List<String>, Void> genWorker;

    // ---------------------------------------------------------------- 布局页
    private JComboBox<String> layoutPresetCombo;
    private JTextField epochField;
    private JLabel epochCaption;
    private JComboBox<String> unitCombo;
    private JSpinner timestampBitsSpinner;
    private JPanel layoutFieldHost;
    private final List<String> layoutFieldNames = new ArrayList<>();
    private final List<Boolean> layoutFieldSequence = new ArrayList<>();
    private final List<JSpinner> layoutFieldSpinners = new ArrayList<>();
    private BitDiagram diagram;
    private JLabel capacityBits;
    private JLabel capacityRate;
    private JLabel capacityNodes;
    private JLabel capacityLifetime;
    private JTextField encodeInstantField;
    private JPanel encodeFieldHost;
    private final Map<String, JSpinner> encodeSpinners = new LinkedHashMap<>();
    private final Map<String, Long> encodeMemory = new LinkedHashMap<>();
    private JTextField encodeResultField;
    private JLabel encodeStatus;
    private JTextField layoutDecodeField;
    private JTextArea layoutDecodeOutput;
    /** 当前布局页控件组成的布局，非法时为 null */
    private SnowflakeLayout currentLayout;
    /** 程序化修改布局控件时抑制「切换为自定义」的联动 */
    private boolean updatingLayout;

    public IdToolkitPanel() {
        this(ToolCatalog.ID_TOOLKIT);
    }

    public IdToolkitPanel(ToolDescriptor descriptor) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        root.setBorder(KitBorders.padding(4));

        // 生成页的「自定义布局」引用布局页状态，所以布局页先构建
        JComponent decodeTab = buildDecodeTab();
        JComponent layoutTab = buildLayoutTab();
        JComponent generateTab = buildGenerateTab();
        applyLayoutPreset(SnowflakeLayout.Preset.TWITTER);
        updateGenerateOptions();

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Tokens.fontBody());
        tabs.addTab(I18n.get("tool.idtoolkit.tab.decode"), decodeTab);
        tabs.addTab(I18n.get("tool.idtoolkit.tab.generate"), generateTab);
        tabs.addTab(I18n.get("tool.idtoolkit.tab.layout"), layoutTab);
        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    // ==========================================
    // 解码页
    // ==========================================
    private JComponent buildDecodeTab() {
        decodeInput = Fields.area(5, 40);
        decodeInput.setLineWrap(false);
        decodeInput.putClientProperty("JTextField.placeholderText",
                I18n.get("tool.idtoolkit.decode.placeholder"));

        zoneCombo = Fields.combo(zoneChoices(), 200);
        JButton exampleBtn = Buttons.snug(I18n.get("tool.idtoolkit.btn.example"));
        JButton clearBtn = Buttons.snug(I18n.get("tool.idtoolkit.btn.clear"));

        Card inputCard = Card.titled(I18n.get("tool.idtoolkit.decode.card.input"),
                I18n.get("tool.idtoolkit.decode.card.inputHint"));
        inputCard.setContent(Fields.scrollBoxed(decodeInput));
        inputCard.addHeaderAction(Fields.label(I18n.get("tool.idtoolkit.label.zone")));
        inputCard.addHeaderAction(zoneCombo);
        inputCard.addHeaderAction(exampleBtn);
        inputCard.addHeaderAction(clearBtn);

        decodeTable = new JTable(decodeModel);
        decodeTable.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        decodeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        decodeTable.setFillsViewportHeight(true);
        int[] widths = {220, 170, 170, 200, 120, 220};
        for (int i = 0; i < widths.length; i++) {
            decodeTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        decodeStatus = Fields.caption(I18n.get("tool.idtoolkit.decode.status.empty"));
        Card tableCard = Card.flush(I18n.get("tool.idtoolkit.decode.card.results"));
        tableCard.setContent(Fields.scroll(decodeTable));
        tableCard.addHeaderAction(decodeStatus);

        decodeDetail = Fields.output(10, 30);
        decodeDetail.setLineWrap(false);
        JButton copyDetail = Buttons.snug(I18n.get("tool.idtoolkit.btn.copy"));
        Card detailCard = Card.flush(I18n.get("tool.idtoolkit.decode.card.detail"));
        detailCard.setContent(Fields.scroll(decodeDetail));
        detailCard.addHeaderAction(copyDetail);

        JPanel page = Layouts.box(0, Tokens.SPACE_MD);
        page.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, 0, 0, 0));
        page.add(inputCard, BorderLayout.NORTH);
        page.add(Layouts.splitHorizontal(tableCard, detailCard, 0.62, 0.62), BorderLayout.CENTER);

        debounce = new Timer(DEBOUNCE_MILLIS, event -> runDecode());
        debounce.setRepeats(false);
        decodeInput.getDocument().addDocumentListener(onChange(() -> {
            if (debounce != null) {
                debounce.restart();
            }
        }));
        zoneCombo.addActionListener(event -> {
            if (decodeModel.getRowCount() > 0) {
                // 只刷新行内容，保留当前选中行
                decodeModel.fireTableRowsUpdated(0, decodeModel.getRowCount() - 1);
            }
            showDecodeDetail();
        });
        decodeTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showDecodeDetail();
            }
        });
        exampleBtn.addActionListener(event -> decodeInput.setText(EXAMPLES));
        clearBtn.addActionListener(event -> decodeInput.setText(""));
        copyDetail.addActionListener(event -> copy(decodeDetail.getText()));
        return page;
    }

    /** 立即解码输入框内容（去抖定时器到点后调用，测试也可直接调用） */
    void runDecode() {
        String[] lines = decodeInput.getText().split("\\R");
        List<DecodeRow> rows = new ArrayList<>();
        Instant now = Instant.now();
        boolean truncated = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (rows.size() >= MAX_DECODE_LINES) {
                truncated = true;
                break;
            }
            rows.add(new DecodeRow(trimmed, IdDetector.detect(trimmed, now)));
        }
        int previous = decodeTable.getSelectedRow();
        decodeModel.setRows(rows);
        int recognized = 0;
        for (DecodeRow row : rows) {
            if (!row.interpretations.isEmpty()) {
                recognized++;
            }
        }
        if (rows.isEmpty()) {
            decodeStatus.setText(I18n.get("tool.idtoolkit.decode.status.empty"));
            decodeDetail.setText("");
            return;
        }
        String status = I18n.get("tool.idtoolkit.decode.status.summary", String.valueOf(rows.size()),
                String.valueOf(recognized), String.valueOf(rows.size() - recognized));
        if (truncated) {
            status += " " + I18n.get("tool.idtoolkit.decode.status.truncated", String.valueOf(MAX_DECODE_LINES));
        }
        decodeStatus.setText(status);
        int select = previous < 0 ? 0 : Math.min(previous, rows.size() - 1);
        decodeTable.setRowSelectionInterval(select, select);
        showDecodeDetail();
    }

    private void showDecodeDetail() {
        int row = decodeTable == null ? -1 : decodeTable.getSelectedRow();
        if (row < 0 || row >= decodeModel.getRowCount()) {
            decodeDetail.setText("");
            return;
        }
        DecodeRow decodeRow = decodeModel.rows.get(row);
        StringBuilder text = new StringBuilder();
        text.append(decodeRow.input).append('\n');
        if (decodeRow.interpretations.isEmpty()) {
            text.append('\n').append(I18n.get("tool.idtoolkit.decode.unrecognized"));
            decodeDetail.setText(text.toString());
            return;
        }
        ZoneId zone = selectedZone();
        int index = 1;
        for (IdDetector.Interpretation interpretation : decodeRow.interpretations) {
            text.append('\n').append(I18n.get("tool.idtoolkit.decode.detail.header", String.valueOf(index++),
                    typeLabel(interpretation), percent(interpretation.score()))).append('\n');
            if (interpretation.timestamp() != null) {
                Instant time = interpretation.timestamp();
                appendLine(text, I18n.get("tool.idtoolkit.decode.detail.local", zone.getId()), formatLocal(time, zone));
                appendLine(text, I18n.get("tool.idtoolkit.decode.detail.iso"), time.toString());
                appendLine(text, I18n.get("tool.idtoolkit.decode.detail.epochMillis"), epochMillis(time));
            }
            for (IdDetector.Detail detail : interpretation.details()) {
                appendLine(text, detailLabel(detail.key()), detailValue(detail));
            }
            for (String warning : interpretation.warnings()) {
                text.append("  ! ").append(warningText(warning)).append('\n');
            }
        }
        decodeDetail.setText(text.toString());
        decodeDetail.setCaretPosition(0);
    }

    private static void appendLine(StringBuilder text, String label, String value) {
        text.append("  ").append(label).append(": ").append(value).append('\n');
    }

    private ZoneId selectedZone() {
        Object selected = zoneCombo == null ? null : zoneCombo.getSelectedItem();
        try {
            return selected == null ? ZoneId.systemDefault() : ZoneId.of(selected.toString());
        } catch (DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /** 系统时区、UTC、上海在前，其余按字母序 */
    private static String[] zoneChoices() {
        List<String> zones = new ArrayList<>();
        zones.add(ZoneId.systemDefault().getId());
        for (String id : new String[]{"UTC", "Asia/Shanghai"}) {
            if (!zones.contains(id)) {
                zones.add(id);
            }
        }
        for (String id : new TreeSet<>(ZoneId.getAvailableZoneIds())) {
            if (!zones.contains(id)) {
                zones.add(id);
            }
        }
        return zones.toArray(new String[0]);
    }

    private static String formatLocal(Instant time, ZoneId zone) {
        try {
            return LOCAL_FORMAT.format(time.atZone(zone));
        } catch (DateTimeException e) {
            return time.toString();
        }
    }

    private static String epochMillis(Instant time) {
        try {
            return String.valueOf(time.toEpochMilli());
        } catch (ArithmeticException e) {
            return "";
        }
    }

    private static String percent(double score) {
        return Math.round(score * 100) + "%";
    }

    // ---------------------------------------------------------------- 文案映射

    String typeLabel(IdDetector.Interpretation interpretation) {
        switch (interpretation.kind()) {
            case UUID:
                if ("nil".equals(interpretation.subtype())) {
                    return I18n.get("tool.idtoolkit.kind.uuidNil");
                }
                if ("max".equals(interpretation.subtype())) {
                    return I18n.get("tool.idtoolkit.kind.uuidMax");
                }
                return I18n.get("tool.idtoolkit.kind.uuid", interpretation.subtype());
            case ULID:
                return I18n.get("tool.idtoolkit.kind.ulid");
            case OBJECT_ID:
                return I18n.get("tool.idtoolkit.kind.objectId");
            case KSUID:
                return I18n.get("tool.idtoolkit.kind.ksuid");
            case SNOWFLAKE: {
                StringBuilder presets = new StringBuilder();
                for (String id : interpretation.subtype().split(",")) {
                    if (presets.length() > 0) {
                        presets.append(" / ");
                    }
                    presets.append(presetLabel(SnowflakeLayout.Preset.byId(id)));
                }
                return I18n.get("tool.idtoolkit.kind.snowflake", presets.toString());
            }
            case UNIX_SECONDS:
                return I18n.get("tool.idtoolkit.kind.unixSeconds");
            case UNIX_MILLIS:
                return I18n.get("tool.idtoolkit.kind.unixMillis");
            default:
                return interpretation.kind().name();
        }
    }

    private static String presetLabel(SnowflakeLayout.Preset preset) {
        switch (preset) {
            case TWITTER:
                return I18n.get("tool.idtoolkit.preset.twitter");
            case MYBATIS_PLUS:
                return I18n.get("tool.idtoolkit.preset.mybatisPlus");
            case HUTOOL:
                return I18n.get("tool.idtoolkit.preset.hutool");
            case BAIDU_UID:
                return I18n.get("tool.idtoolkit.preset.baiduUid");
            case SONYFLAKE:
                return I18n.get("tool.idtoolkit.preset.sonyflake");
            case DISCORD:
                return I18n.get("tool.idtoolkit.preset.discord");
            default:
                return I18n.get("tool.idtoolkit.preset.custom");
        }
    }

    private static String detailLabel(String key) {
        switch (key) {
            case IdDetector.D_CANONICAL:
                return I18n.get("tool.idtoolkit.detail.canonical");
            case IdDetector.D_VERSION:
                return I18n.get("tool.idtoolkit.detail.version");
            case IdDetector.D_VARIANT:
                return I18n.get("tool.idtoolkit.detail.variant");
            case IdDetector.D_GREGORIAN:
                return I18n.get("tool.idtoolkit.detail.gregorian100ns");
            case IdDetector.D_CLOCK_SEQUENCE:
                return I18n.get("tool.idtoolkit.detail.clockSequence");
            case IdDetector.D_NODE:
                return I18n.get("tool.idtoolkit.detail.node");
            case IdDetector.D_NODE_RANDOM:
                return I18n.get("tool.idtoolkit.detail.nodeRandom");
            case IdDetector.D_RAND_A:
                return I18n.get("tool.idtoolkit.detail.randA");
            case IdDetector.D_ULID:
                return I18n.get("tool.idtoolkit.detail.ulid");
            case IdDetector.D_UUID:
                return I18n.get("tool.idtoolkit.detail.uuid");
            case IdDetector.D_RANDOMNESS:
                return I18n.get("tool.idtoolkit.detail.randomness");
            case IdDetector.D_SECONDS:
                return I18n.get("tool.idtoolkit.detail.seconds");
            case IdDetector.D_MILLIS:
                return I18n.get("tool.idtoolkit.detail.millis");
            case IdDetector.D_RANDOM:
                return I18n.get("tool.idtoolkit.detail.random");
            case IdDetector.D_COUNTER:
                return I18n.get("tool.idtoolkit.detail.counter");
            case IdDetector.D_KSUID_SECONDS:
                return I18n.get("tool.idtoolkit.detail.ksuidSeconds");
            case IdDetector.D_PAYLOAD:
                return I18n.get("tool.idtoolkit.detail.payload");
            case IdDetector.D_TICKS:
                return I18n.get("tool.idtoolkit.detail.ticks");
            case IdDetector.D_HEX:
                return I18n.get("tool.idtoolkit.detail.hex");
            case IdDetector.D_EPOCH:
                return I18n.get("tool.idtoolkit.detail.epoch");
            default:
                return fieldLabel(key);
        }
    }

    /** 雪花字段名的本地化；未知名称（理论上不会出现）原样显示 */
    static String fieldLabel(String name) {
        switch (name) {
            case SnowflakeLayout.DATACENTER:
                return I18n.get("tool.idtoolkit.detail.datacenter");
            case SnowflakeLayout.WORKER:
                return I18n.get("tool.idtoolkit.detail.worker");
            case SnowflakeLayout.SEQUENCE:
                return I18n.get("tool.idtoolkit.detail.sequence");
            case SnowflakeLayout.MACHINE:
                return I18n.get("tool.idtoolkit.detail.machine");
            case SnowflakeLayout.PROCESS:
                return I18n.get("tool.idtoolkit.detail.process");
            case SnowflakeLayout.INCREMENT:
                return I18n.get("tool.idtoolkit.detail.increment");
            default:
                return name;
        }
    }

    private static String detailValue(IdDetector.Detail detail) {
        Object value = detail.value();
        if (value instanceof Boolean) {
            return I18n.get((Boolean) value ? "tool.idtoolkit.yes" : "tool.idtoolkit.no");
        }
        if (IdDetector.D_VARIANT.equals(detail.key())) {
            return variantLabel(String.valueOf(value));
        }
        return String.valueOf(value);
    }

    private static String variantLabel(String name) {
        switch (name) {
            case "NCS":
                return I18n.get("tool.idtoolkit.variant.ncs");
            case "RFC_9562":
                return I18n.get("tool.idtoolkit.variant.rfc9562");
            case "MICROSOFT":
                return I18n.get("tool.idtoolkit.variant.microsoft");
            default:
                return I18n.get("tool.idtoolkit.variant.future");
        }
    }

    private static String warningText(String code) {
        switch (code) {
            case IdDetector.W_SIGN_BIT:
                return I18n.get("tool.idtoolkit.warning.signBit");
            case IdDetector.W_UNUSED_BITS:
                return I18n.get("tool.idtoolkit.warning.unusedBits");
            case IdDetector.W_FUTURE:
                return I18n.get("tool.idtoolkit.warning.futureTimestamp");
            case IdDetector.W_NON_RFC_VARIANT:
                return I18n.get("tool.idtoolkit.warning.nonRfcVariant");
            case IdDetector.W_UNKNOWN_VERSION:
                return I18n.get("tool.idtoolkit.warning.unknownVersion");
            case IdDetector.W_LENIENT_CHARS:
                return I18n.get("tool.idtoolkit.warning.lenientChars");
            case IdDetector.W_IMPLAUSIBLE_TIME:
                return I18n.get("tool.idtoolkit.warning.implausibleTime");
            default:
                return code;
        }
    }

    /** 领域异常代码 → 本地化文案 */
    static String errorText(IdCodecException error) {
        Object[] args = error.getArgs();
        String a0 = args.length > 0 ? String.valueOf(args[0]) : "";
        String a1 = args.length > 1 ? String.valueOf(args[1]) : "";
        switch (error.getCode()) {
            case EMPTY:
                return I18n.get("tool.idtoolkit.error.empty");
            case INVALID_LENGTH:
                return I18n.get("tool.idtoolkit.error.invalidLength", a0, a1);
            case INVALID_CHARACTER:
                return I18n.get("tool.idtoolkit.error.invalidCharacter", a0, a1);
            case OVERFLOW:
                return I18n.get("tool.idtoolkit.error.overflow");
            case OUT_OF_RANGE:
                return I18n.get("tool.idtoolkit.error.outOfRange", fieldLabel(a0), a1);
            case UNKNOWN_FIELD:
                return I18n.get("tool.idtoolkit.error.unknownField", a0);
            case BEFORE_EPOCH:
                return I18n.get("tool.idtoolkit.error.beforeEpoch", a0);
            case TIMESTAMP_OVERFLOW:
                return I18n.get("tool.idtoolkit.error.timestampOverflow", a0);
            case CLOCK_BACKWARDS:
                return I18n.get("tool.idtoolkit.error.clockBackwards", a0);
            case MONOTONIC_OVERFLOW:
                return I18n.get("tool.idtoolkit.error.monotonicOverflow");
            case INVALID_LAYOUT:
                return I18n.get("tool.idtoolkit.error.invalidLayout", a0);
            case INVALID_ALPHABET:
                return I18n.get("tool.idtoolkit.error.invalidAlphabet");
            case INVALID_SIZE:
                return I18n.get("tool.idtoolkit.error.invalidSize", a0, a1);
            case INVALID_TIME:
                return I18n.get("tool.idtoolkit.error.invalidTime", a0);
            default:
                return error.getMessage();
        }
    }

    private String summary(IdDetector.Interpretation interpretation) {
        StringBuilder text = new StringBuilder();
        for (IdDetector.Detail detail : interpretation.details()) {
            if (SUMMARY_EXCLUDED.contains(detail.key())) {
                continue;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(detailLabel(detail.key())).append('=').append(detailValue(detail));
        }
        return text.toString();
    }

    /** 一行输入及其全部解读 */
    static final class DecodeRow {
        final String input;
        final List<IdDetector.Interpretation> interpretations;

        DecodeRow(String input, List<IdDetector.Interpretation> interpretations) {
            this.input = input;
            this.interpretations = interpretations;
        }
    }

    /** 解码结果表：每行展示最可信的一种解读，时间列随所选时区变化 */
    final class DecodeTableModel extends AbstractTableModel {

        private final String[] columns = {
                I18n.get("tool.idtoolkit.decode.col.input"),
                I18n.get("tool.idtoolkit.decode.col.type"),
                I18n.get("tool.idtoolkit.decode.col.localTime"),
                I18n.get("tool.idtoolkit.decode.col.iso"),
                I18n.get("tool.idtoolkit.decode.col.epochMillis"),
                I18n.get("tool.idtoolkit.decode.col.fields")};
        private List<DecodeRow> rows = new ArrayList<>();

        void setRows(List<DecodeRow> newRows) {
            rows = new ArrayList<>(newRows);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DecodeRow row = rows.get(rowIndex);
            if (columnIndex == 0) {
                return row.input;
            }
            if (row.interpretations.isEmpty()) {
                return columnIndex == 1 ? I18n.get("tool.idtoolkit.kind.unknown") : "";
            }
            IdDetector.Interpretation best = row.interpretations.get(0);
            Instant time = best.timestamp();
            switch (columnIndex) {
                case 1:
                    return typeLabel(best);
                case 2:
                    return time == null ? "" : formatLocal(time, selectedZone());
                case 3:
                    return time == null ? "" : time.toString();
                case 4:
                    return time == null ? "" : epochMillis(time);
                default:
                    return summary(best);
            }
        }
    }

    // ==========================================
    // 生成页
    // ==========================================
    private JComponent buildGenerateTab() {
        String[] typeLabels = new String[GenType.values().length];
        for (GenType type : GenType.values()) {
            typeLabels[type.ordinal()] = genTypeLabel(type);
        }
        genTypeCombo = Fields.combo(typeLabels);
        genCount = Fields.spinner(10, 1, MAX_COUNT, 1);
        genUpper = Fields.check(I18n.get("tool.idtoolkit.gen.upper"), false);
        genNoHyphens = Fields.check(I18n.get("tool.idtoolkit.gen.noHyphens"), false);

        // 雪花：预设 + 节点字段
        String[] presetLabels = new String[knownPresets.size() + 1];
        for (int i = 0; i < knownPresets.size(); i++) {
            presetLabels[i] = presetLabel(knownPresets.get(i));
        }
        presetLabels[knownPresets.size()] = I18n.get("tool.idtoolkit.gen.customLayout");
        genPresetCombo = Fields.combo(presetLabels);
        genNodeHost = Layouts.wrapRow();
        genSnowflakeCaption = Fields.caption("");
        FormGrid snowflakeForm = new FormGrid();
        snowflakeForm.row(I18n.get("tool.idtoolkit.label.preset"), genPresetCombo);
        snowflakeForm.row(I18n.get("tool.idtoolkit.gen.nodeFields"), genNodeHost);
        snowflakeForm.row("", genSnowflakeCaption);

        // v3 / v5：命名空间 + 名称
        genNamespaceCombo = Fields.combo(new String[]{"DNS", "URL", "OID", "X.500",
                I18n.get("tool.idtoolkit.gen.namespace.custom")});
        genCustomNamespace = Fields.mono("");
        genCustomNamespace.putClientProperty("JTextField.placeholderText",
                I18n.get("tool.idtoolkit.gen.namespace.placeholder"));
        genCustomNamespace.setEnabled(false);
        genNames = Fields.area(3, 30);
        genNames.setText("www.example.com");
        FormGrid nameForm = new FormGrid();
        nameForm.row(I18n.get("tool.idtoolkit.gen.namespace"), genNamespaceCombo);
        nameForm.row(I18n.get("tool.idtoolkit.gen.namespace.customLabel"), genCustomNamespace);
        nameForm.row(I18n.get("tool.idtoolkit.gen.names"), Fields.scrollBoxed(genNames));
        nameForm.caption(I18n.get("tool.idtoolkit.gen.names.hint"));

        genMonotonic = Fields.check(I18n.get("tool.idtoolkit.gen.monotonic"), true);
        FormGrid ulidForm = new FormGrid();
        ulidForm.fullRow(genMonotonic);
        ulidForm.caption(I18n.get("tool.idtoolkit.gen.monotonic.hint"));

        genAlphabet = Fields.mono(NanoIdGenerator.DEFAULT_ALPHABET);
        genNanoSize = Fields.spinner(NanoIdGenerator.DEFAULT_SIZE, 1, NanoIdGenerator.MAX_SIZE, 1);
        genNanoCaption = Fields.caption("");
        JPanel sizeWrap = Layouts.box();
        sizeWrap.add(genNanoSize, BorderLayout.WEST);
        FormGrid nanoForm = new FormGrid();
        nanoForm.row(I18n.get("tool.idtoolkit.gen.alphabet"), genAlphabet);
        nanoForm.row(I18n.get("tool.idtoolkit.gen.size"), sizeWrap);
        nanoForm.row("", genNanoCaption);

        FormGrid noneForm = new FormGrid();
        noneForm.fullRow(Fields.caption(I18n.get("tool.idtoolkit.gen.noOptions")));

        genOptionsLayout = new CardLayout();
        genOptionsCards = new JPanel(genOptionsLayout);
        genOptionsCards.setOpaque(false);
        genOptionsCards.add(snowflakeForm, CARD_SNOWFLAKE);
        genOptionsCards.add(nameForm, CARD_NAME);
        genOptionsCards.add(ulidForm, CARD_ULID);
        genOptionsCards.add(nanoForm, CARD_NANOID);
        genOptionsCards.add(noneForm, CARD_NONE);

        JPanel countWrap = Layouts.box();
        countWrap.add(genCount, BorderLayout.WEST);
        JPanel switches = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_MD, 0));
        switches.setOpaque(false);
        switches.add(genUpper);
        switches.add(genNoHyphens);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.idtoolkit.gen.type"), genTypeCombo);
        form.row(I18n.get("tool.idtoolkit.gen.count"), countWrap);
        form.row(I18n.get("tool.idtoolkit.gen.format"), switches);
        form.fullRow(genOptionsCards);

        genButton = Buttons.primary(I18n.get("tool.idtoolkit.btn.generate"));
        Card config = Card.titled(I18n.get("tool.idtoolkit.gen.card.options"));
        config.setContent(form);
        config.addHeaderAction(genButton);

        genOutput = Fields.output(10, 40);
        genOutput.setLineWrap(false);
        genStatus = Fields.caption("");
        JButton copyBtn = Buttons.snug(I18n.get("tool.idtoolkit.btn.copy"));
        JButton clearBtn = Buttons.snug(I18n.get("tool.idtoolkit.btn.clear"));
        Card output = Card.flush(I18n.get("tool.idtoolkit.gen.card.output"));
        output.setContent(Fields.scroll(genOutput));
        output.addHeaderAction(genStatus);
        output.addHeaderAction(copyBtn);
        output.addHeaderAction(clearBtn);

        JPanel page = Layouts.box(0, Tokens.SPACE_MD);
        page.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, 0, 0, 0));
        page.add(config, BorderLayout.NORTH);
        page.add(output, BorderLayout.CENTER);

        genTypeCombo.addActionListener(event -> updateGenerateOptions());
        genPresetCombo.addActionListener(event -> rebuildGenerateNodeFields());
        genNamespaceCombo.addActionListener(event ->
                genCustomNamespace.setEnabled(genNamespaceCombo.getSelectedIndex() == 4));
        genAlphabet.getDocument().addDocumentListener(onChange(this::updateNanoCaption));
        genNanoSize.addChangeListener(event -> updateNanoCaption());
        genButton.addActionListener(event -> generate());
        copyBtn.addActionListener(event -> copy(genOutput.getText()));
        clearBtn.addActionListener(event -> {
            genOutput.setText("");
            genStatus.setText("");
        });
        rebuildGenerateNodeFields();
        updateNanoCaption();
        return page;
    }

    private static String genTypeLabel(GenType type) {
        switch (type) {
            case SNOWFLAKE:
                return I18n.get("tool.idtoolkit.gen.type.snowflake");
            case UUID_V4:
                return I18n.get("tool.idtoolkit.gen.type.uuidV4");
            case UUID_V7:
                return I18n.get("tool.idtoolkit.gen.type.uuidV7");
            case UUID_V5:
                return I18n.get("tool.idtoolkit.gen.type.uuidV5");
            case UUID_V3:
                return I18n.get("tool.idtoolkit.gen.type.uuidV3");
            case UUID_V1:
                return I18n.get("tool.idtoolkit.gen.type.uuidV1");
            case UUID_V6:
                return I18n.get("tool.idtoolkit.gen.type.uuidV6");
            case ULID:
                return I18n.get("tool.idtoolkit.gen.type.ulid");
            case OBJECT_ID:
                return I18n.get("tool.idtoolkit.gen.type.objectId");
            case KSUID:
                return I18n.get("tool.idtoolkit.gen.type.ksuid");
            default:
                return I18n.get("tool.idtoolkit.gen.type.nanoid");
        }
    }

    private GenType selectedGenType() {
        int index = genTypeCombo.getSelectedIndex();
        return index < 0 ? GenType.SNOWFLAKE : GenType.values()[index];
    }

    private void updateGenerateOptions() {
        if (genTypeCombo == null) {
            return;
        }
        GenType type = selectedGenType();
        String card;
        if (type == GenType.SNOWFLAKE) {
            card = CARD_SNOWFLAKE;
        } else if (type.isNameBased()) {
            card = CARD_NAME;
        } else if (type == GenType.ULID) {
            card = CARD_ULID;
        } else if (type == GenType.NANOID) {
            card = CARD_NANOID;
        } else {
            card = CARD_NONE;
        }
        genOptionsLayout.show(genOptionsCards, card);
        // v3 / v5 是确定性的：同一名称永远得到同一 UUID，数量由名称行数决定
        genCount.setEnabled(!type.isNameBased());
        // KSUID / NanoID 区分大小写，转大写会改变取值；ULID 规范写法本就是大写
        genUpper.setEnabled(type.isUuid() || type == GenType.OBJECT_ID);
        genNoHyphens.setEnabled(type.isUuid());
    }

    /** 生成页当前选中的雪花布局；选「自定义」且布局页配置非法时为 null */
    private SnowflakeLayout selectedGenerateLayout() {
        int index = genPresetCombo.getSelectedIndex();
        if (index >= 0 && index < knownPresets.size()) {
            return knownPresets.get(index).layout();
        }
        return currentLayout;
    }

    private void rebuildGenerateNodeFields() {
        if (genNodeHost == null) {
            return;
        }
        for (Map.Entry<String, JSpinner> entry : genNodeSpinners.entrySet()) {
            genNodeMemory.put(entry.getKey(), ((Number) entry.getValue().getValue()).longValue());
        }
        genNodeSpinners.clear();
        genNodeHost.removeAll();
        SnowflakeLayout layout = selectedGenerateLayout();
        if (layout == null) {
            genSnowflakeCaption.setText(I18n.get("tool.idtoolkit.gen.layoutInvalid"));
            genSnowflakeCaption.setForeground(Tokens.danger());
        } else {
            for (SnowflakeLayout.Field field : layout.fields()) {
                if (field.sequence()) {
                    continue;
                }
                long remembered = genNodeMemory.getOrDefault(field.name(), 1L);
                JSpinner spinner = longSpinner(Math.min(remembered, field.maxValue()), field.maxValue());
                spinner.setToolTipText(I18n.get("tool.idtoolkit.gen.fieldRange",
                        String.valueOf(field.maxValue()), String.valueOf(field.bits())));
                genNodeSpinners.put(field.name(), spinner);
                genNodeHost.add(labeled(fieldLabel(field.name()), spinner));
            }
            if (genNodeSpinners.isEmpty()) {
                genNodeHost.add(Fields.caption(I18n.get("tool.idtoolkit.gen.noNodeFields")));
            }
            genSnowflakeCaption.setText(I18n.get("tool.idtoolkit.gen.snowflakeCaption",
                    layout.epoch().toString(), formatCount(layout.idsPerSecondPerNode()),
                    layout.exhaustedAt().toString()));
            genSnowflakeCaption.setForeground(layout.exhaustedAt().isBefore(Instant.now())
                    ? Tokens.danger() : Tokens.mutedForeground());
        }
        genNodeHost.revalidate();
        genNodeHost.repaint();
    }

    private void updateNanoCaption() {
        String alphabet = genAlphabet.getText();
        int size = ((Number) genNanoSize.getValue()).intValue();
        try {
            NanoIdGenerator.validate(alphabet, size);
            genNanoCaption.setText(I18n.get("tool.idtoolkit.gen.entropy",
                    String.valueOf(alphabet.length()),
                    String.format(Locale.ROOT, "%.1f", NanoIdGenerator.entropyBits(alphabet.length(), size))));
            genNanoCaption.setForeground(Tokens.mutedForeground());
        } catch (IdCodecException e) {
            genNanoCaption.setText(errorText(e));
            genNanoCaption.setForeground(Tokens.danger());
        }
    }

    /** 收集界面参数（EDT 上），把耗时的批量生成放到后台线程 */
    void generate() {
        if (genWorker != null && !genWorker.isDone()) {
            return;
        }
        Callable<List<String>> job;
        try {
            job = prepareJob();
        } catch (IdCodecException e) {
            setStatus(genStatus, errorText(e), Tokens.danger());
            return;
        }
        if (job == null) {
            return;
        }
        genButton.setEnabled(false);
        setStatus(genStatus, I18n.get("tool.idtoolkit.gen.status.running"), Tokens.mutedForeground());
        genWorker = new SwingWorker<>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return job.call();
            }

            @Override
            protected void done() {
                genButton.setEnabled(true);
                if (isCancelled()) {
                    return;
                }
                try {
                    List<String> ids = get();
                    genOutput.setText(String.join("\n", ids));
                    genOutput.setCaretPosition(0);
                    setStatus(genStatus, I18n.get("tool.idtoolkit.gen.status.done", String.valueOf(ids.size())),
                            Tokens.success());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause instanceof IdCodecException
                            ? errorText((IdCodecException) cause)
                            : I18n.get("tool.idtoolkit.gen.status.failed", String.valueOf(cause));
                    setStatus(genStatus, message, Tokens.danger());
                }
            }
        };
        genWorker.execute();
    }

    private Callable<List<String>> prepareJob() {
        GenType type = selectedGenType();
        int count = ((Number) genCount.getValue()).intValue();
        boolean upper = genUpper.isSelected();
        boolean hyphens = !genNoHyphens.isSelected();
        switch (type) {
            case SNOWFLAKE: {
                SnowflakeLayout layout = selectedGenerateLayout();
                if (layout == null) {
                    setStatus(genStatus, I18n.get("tool.idtoolkit.gen.layoutInvalid"), Tokens.danger());
                    return null;
                }
                SnowflakeCodec.Generator generator = obtainGenerator(layout);
                return () -> repeat(count, () -> Long.toString(generator.next()));
            }
            case UUID_V4:
                return () -> repeat(count, () -> UuidCodec.format(uuidCodec.v4(), upper, hyphens));
            case UUID_V7:
                return () -> repeat(count, () -> UuidCodec.format(uuidCodec.v7(), upper, hyphens));
            case UUID_V1:
                return () -> repeat(count, () -> UuidCodec.format(uuidCodec.v1(), upper, hyphens));
            case UUID_V6:
                return () -> repeat(count, () -> UuidCodec.format(uuidCodec.v6(), upper, hyphens));
            case UUID_V5:
            case UUID_V3: {
                UUID namespace = resolveNamespace();
                List<String> names = new ArrayList<>();
                for (String line : genNames.getText().split("\\R")) {
                    if (!line.isEmpty()) {
                        names.add(line);
                    }
                }
                if (names.isEmpty()) {
                    setStatus(genStatus, I18n.get("tool.idtoolkit.gen.namesEmpty"), Tokens.danger());
                    return null;
                }
                boolean v5 = type == GenType.UUID_V5;
                return () -> {
                    List<String> result = new ArrayList<>();
                    for (String name : names) {
                        UUID uuid = v5 ? UuidCodec.v5(namespace, name) : UuidCodec.v3(namespace, name);
                        result.add(UuidCodec.format(uuid, upper, hyphens));
                    }
                    return result;
                };
            }
            case ULID: {
                boolean monotonic = genMonotonic.isSelected();
                return () -> repeat(count, monotonic ? ulidCodec::generateMonotonic : ulidCodec::generate);
            }
            case OBJECT_ID:
                return () -> repeat(count, () -> {
                    String id = objectIdCodec.generate();
                    return upper ? id.toUpperCase(Locale.ROOT) : id;
                });
            case KSUID:
                return () -> repeat(count, ksuidCodec::generate);
            default: {
                String alphabet = genAlphabet.getText();
                int size = ((Number) genNanoSize.getValue()).intValue();
                NanoIdGenerator.validate(alphabet, size);
                return () -> repeat(count, () -> nanoIdGenerator.generate(alphabet, size));
            }
        }
    }

    private static List<String> repeat(int count, java.util.function.Supplier<String> supplier) {
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(supplier.get());
        }
        return result;
    }

    /**
     * 布局与节点字段都没变时复用同一个生成器：否则连续两次点击落在同一毫秒内，
     * 新生成器的序列号从 0 重来，会产出与上一批重复的 ID。
     */
    private SnowflakeCodec.Generator obtainGenerator(SnowflakeLayout layout) {
        Map<String, Long> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, JSpinner> entry : genNodeSpinners.entrySet()) {
            nodes.put(entry.getKey(), ((Number) entry.getValue().getValue()).longValue());
        }
        if (snowflakeGenerator == null || !snowflakeGenerator.layout().equals(layout)
                || !nodes.equals(snowflakeGeneratorNodes)) {
            snowflakeGenerator = new SnowflakeCodec.Generator(layout, nodes);
            snowflakeGeneratorNodes = nodes;
        }
        return snowflakeGenerator;
    }

    private UUID resolveNamespace() {
        switch (genNamespaceCombo.getSelectedIndex()) {
            case 1:
                return UuidCodec.NAMESPACE_URL;
            case 2:
                return UuidCodec.NAMESPACE_OID;
            case 3:
                return UuidCodec.NAMESPACE_X500;
            case 4:
                return UuidCodec.parse(genCustomNamespace.getText());
            default:
                return UuidCodec.NAMESPACE_DNS;
        }
    }

    // ==========================================
    // 布局页
    // ==========================================
    private JComponent buildLayoutTab() {
        SnowflakeLayout.Preset[] presets = SnowflakeLayout.Preset.values();
        String[] presetLabels = new String[presets.length];
        for (SnowflakeLayout.Preset preset : presets) {
            presetLabels[preset.ordinal()] = presetLabel(preset);
        }
        layoutPresetCombo = Fields.combo(presetLabels);
        epochField = Fields.mono("");
        epochField.putClientProperty("JTextField.placeholderText",
                I18n.get("tool.idtoolkit.layout.epoch.placeholder"));
        epochCaption = Fields.caption("");
        unitCombo = Fields.combo(new String[]{
                I18n.get("tool.idtoolkit.unit.millis"),
                I18n.get("tool.idtoolkit.unit.tenMillis"),
                I18n.get("tool.idtoolkit.unit.seconds")});
        timestampBitsSpinner = Fields.spinner(41, 1, SnowflakeLayout.USABLE_BITS, 1);
        layoutFieldHost = Layouts.wrapRow();

        JPanel epochBox = Layouts.box(0, Tokens.SPACE_XS);
        epochBox.add(epochField, BorderLayout.CENTER);
        epochBox.add(epochCaption, BorderLayout.SOUTH);

        FormGrid form = new FormGrid();
        form.row(I18n.get("tool.idtoolkit.label.preset"), layoutPresetCombo);
        form.row(I18n.get("tool.idtoolkit.layout.epoch"), epochBox);
        form.rowCompact(I18n.get("tool.idtoolkit.layout.unit"), unitCombo);
        form.rowCompact(I18n.get("tool.idtoolkit.layout.timestampBits"), timestampBitsSpinner);
        form.row(I18n.get("tool.idtoolkit.layout.fieldBits"), layoutFieldHost);
        form.caption(I18n.get("tool.idtoolkit.layout.fieldsHint"));

        diagram = new BitDiagram();
        capacityBits = Fields.caption("");
        capacityRate = Fields.caption("");
        capacityNodes = Fields.caption("");
        capacityLifetime = Fields.caption("");
        JPanel capacity = Layouts.stack(Tokens.SPACE_XS, capacityBits, capacityRate, capacityNodes, capacityLifetime);

        Card layoutCard = Card.titled(I18n.get("tool.idtoolkit.layout.card.layout"),
                I18n.get("tool.idtoolkit.layout.card.layoutHint"));
        layoutCard.setContent(Layouts.stack(Tokens.SPACE_MD, form, diagram, capacity));

        encodeInstantField = Fields.mono(Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        JButton nowBtn = Buttons.snug(I18n.get("tool.idtoolkit.btn.now"));
        encodeFieldHost = Layouts.wrapRow();
        encodeResultField = Fields.mono("");
        encodeResultField.setEditable(false);
        JButton copyResult = Buttons.snug(I18n.get("tool.idtoolkit.btn.copy"));
        encodeStatus = Fields.caption("");
        layoutDecodeField = Fields.mono("");
        layoutDecodeField.putClientProperty("JTextField.placeholderText",
                I18n.get("tool.idtoolkit.layout.decode.placeholder"));
        layoutDecodeOutput = Fields.output(5, 30);
        layoutDecodeOutput.setLineWrap(false);

        FormGrid encodeForm = new FormGrid();
        encodeForm.row(I18n.get("tool.idtoolkit.layout.instant"), encodeInstantField, nowBtn);
        encodeForm.caption(I18n.get("tool.idtoolkit.layout.instantHint", ZoneId.systemDefault().getId()));
        encodeForm.row(I18n.get("tool.idtoolkit.layout.fieldValues"), encodeFieldHost);
        encodeForm.row(I18n.get("tool.idtoolkit.layout.result"), encodeResultField, copyResult);
        encodeForm.row("", encodeStatus);
        encodeForm.row(I18n.get("tool.idtoolkit.layout.decodeInput"), layoutDecodeField);
        encodeForm.row("", Fields.scrollBoxed(layoutDecodeOutput));

        JButton encodeBtn = Buttons.primary(I18n.get("tool.idtoolkit.btn.encode"));
        Card encodeCard = Card.titled(I18n.get("tool.idtoolkit.layout.card.encode"),
                I18n.get("tool.idtoolkit.layout.card.encodeHint"));
        encodeCard.setContent(encodeForm);
        encodeCard.addHeaderAction(encodeBtn);

        layoutPresetCombo.addActionListener(event -> {
            if (updatingLayout) {
                return;
            }
            SnowflakeLayout.Preset preset = presets[Math.max(0, layoutPresetCombo.getSelectedIndex())];
            if (preset == SnowflakeLayout.Preset.CUSTOM) {
                // 选「自定义」保留当前数值，只是解除与预设的绑定
                refreshLayout();
            } else {
                applyLayoutPreset(preset);
            }
        });
        epochField.getDocument().addDocumentListener(onChange(this::onLayoutEdited));
        unitCombo.addActionListener(event -> onLayoutEdited());
        timestampBitsSpinner.addChangeListener(event -> onLayoutEdited());
        nowBtn.addActionListener(event ->
                encodeInstantField.setText(Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()));
        encodeBtn.addActionListener(event -> encode());
        copyResult.addActionListener(event -> copy(encodeResultField.getText()));
        layoutDecodeField.getDocument().addDocumentListener(onChange(this::decodeWithLayout));

        JPanel page = Layouts.box();
        page.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, 0, 0, 0));
        page.add(Fields.scrollVertical(Layouts.stack(Tokens.SPACE_LG, layoutCard, encodeCard)),
                BorderLayout.CENTER);
        return page;
    }

    void applyLayoutPreset(SnowflakeLayout.Preset preset) {
        SnowflakeLayout layout = preset.layout();
        updatingLayout = true;
        try {
            layoutPresetCombo.setSelectedIndex(preset.ordinal());
            epochField.setText(layout.epoch().toString());
            unitCombo.setSelectedIndex(layout.unit().ordinal());
            timestampBitsSpinner.setValue(layout.timestampBits());
            rebuildLayoutFields(layout.fields());
        } finally {
            updatingLayout = false;
        }
        refreshLayout();
    }

    private void rebuildLayoutFields(List<SnowflakeLayout.Field> fields) {
        layoutFieldNames.clear();
        layoutFieldSequence.clear();
        layoutFieldSpinners.clear();
        layoutFieldHost.removeAll();
        for (SnowflakeLayout.Field field : fields) {
            JSpinner spinner = Fields.spinner(field.bits(), 1, 62, 1);
            spinner.addChangeListener(event -> onLayoutEdited());
            layoutFieldNames.add(field.name());
            layoutFieldSequence.add(field.sequence());
            layoutFieldSpinners.add(spinner);
            layoutFieldHost.add(labeled(fieldLabel(field.name()), spinner));
        }
        layoutFieldHost.revalidate();
        layoutFieldHost.repaint();
    }

    /** 用户改动任一布局参数：预设不再成立，切到「自定义」并保留字段结构 */
    private void onLayoutEdited() {
        if (updatingLayout) {
            return;
        }
        int custom = SnowflakeLayout.Preset.CUSTOM.ordinal();
        if (layoutPresetCombo.getSelectedIndex() != custom) {
            updatingLayout = true;
            try {
                layoutPresetCombo.setSelectedIndex(custom);
            } finally {
                updatingLayout = false;
            }
        }
        refreshLayout();
    }

    private SnowflakeLayout buildLayoutFromControls() {
        Instant epoch = SnowflakeCodec.parseInstant(epochField.getText(), ZoneId.systemDefault());
        SnowflakeLayout.TickUnit unit = SnowflakeLayout.TickUnit.values()[Math.max(0, unitCombo.getSelectedIndex())];
        int timestampBits = ((Number) timestampBitsSpinner.getValue()).intValue();
        List<SnowflakeLayout.Field> fields = new ArrayList<>();
        for (int i = 0; i < layoutFieldNames.size(); i++) {
            int bits = ((Number) layoutFieldSpinners.get(i).getValue()).intValue();
            fields.add(new SnowflakeLayout.Field(layoutFieldNames.get(i), bits, layoutFieldSequence.get(i)));
        }
        SnowflakeLayout.Preset preset = SnowflakeLayout.Preset.values()[Math.max(0, layoutPresetCombo.getSelectedIndex())];
        long epochMillis;
        try {
            epochMillis = epoch.toEpochMilli();
        } catch (ArithmeticException e) {
            throw new IdCodecException(IdCodecException.Code.INVALID_TIME, "epoch out of range", epochField.getText());
        }
        return new SnowflakeLayout(preset.id(), epochMillis, unit, timestampBits, fields);
    }

    private void refreshLayout() {
        SnowflakeLayout layout = null;
        String error = null;
        try {
            layout = buildLayoutFromControls();
            epochCaption.setText(I18n.get("tool.idtoolkit.layout.epochResolved",
                    layout.epoch().toString(), String.valueOf(layout.epochMillis())));
            epochCaption.setForeground(Tokens.mutedForeground());
        } catch (IdCodecException e) {
            error = errorText(e);
            if (e.getCode() == IdCodecException.Code.INVALID_TIME || e.getCode() == IdCodecException.Code.EMPTY) {
                epochCaption.setText(error);
                epochCaption.setForeground(Tokens.danger());
            }
        }
        currentLayout = layout;
        updateDiagram(layout, error);
        updateCapacity(layout, error);
        rebuildEncodeFields(layout);
        decodeWithLayout();
        if (genPresetCombo != null && genPresetCombo.getSelectedIndex() == knownPresets.size()) {
            rebuildGenerateNodeFields();
        }
    }

    private void updateDiagram(SnowflakeLayout layout, String error) {
        if (layout == null) {
            diagram.setSegments(List.of(), error == null ? "" : error);
            return;
        }
        List<BitDiagram.Segment> segments = new ArrayList<>();
        segments.add(new BitDiagram.Segment(I18n.get("tool.idtoolkit.layout.segment.sign"), 1, BitDiagram.ROLE_SIGN));
        int unused = SnowflakeLayout.USABLE_BITS - layout.totalBits();
        if (unused > 0) {
            segments.add(new BitDiagram.Segment(I18n.get("tool.idtoolkit.layout.segment.unused"), unused,
                    BitDiagram.ROLE_UNUSED));
        }
        segments.add(new BitDiagram.Segment(I18n.get("tool.idtoolkit.layout.segment.timestamp"),
                layout.timestampBits(), 0));
        int role = 1;
        for (SnowflakeLayout.Field field : layout.fields()) {
            segments.add(new BitDiagram.Segment(fieldLabel(field.name()), field.bits(), role++));
        }
        diagram.setSegments(segments, null);
    }

    private void updateCapacity(SnowflakeLayout layout, String error) {
        if (layout == null) {
            capacityBits.setText(error == null ? "" : error);
            capacityBits.setForeground(Tokens.danger());
            capacityRate.setText(" ");
            capacityNodes.setText(" ");
            capacityLifetime.setText(" ");
            return;
        }
        capacityBits.setForeground(Tokens.mutedForeground());
        capacityBits.setText(I18n.get("tool.idtoolkit.layout.capacity.bits", String.valueOf(layout.totalBits()),
                String.valueOf(layout.timestampBits()), String.valueOf(layout.timestampShift())));
        double perTick = Math.pow(2, layout.sequenceBits());
        capacityRate.setText(I18n.get("tool.idtoolkit.layout.capacity.rate",
                formatCount(layout.idsPerSecondPerNode()), formatCount(perTick),
                formatCount(layout.idsPerSecondPerNode() / 1000.0)));
        capacityNodes.setText(I18n.get("tool.idtoolkit.layout.capacity.nodes", formatCount(layout.nodeCount()),
                String.valueOf(layout.nodeBits())));
        Instant exhausted = layout.exhaustedAt();
        double years = (layout.maxTicks() + 1.0) * layout.unit().millis() / MILLIS_PER_YEAR;
        String year = String.valueOf(exhausted.atZone(ZoneOffset.UTC).getYear());
        if (exhausted.isBefore(Instant.now())) {
            capacityLifetime.setText(I18n.get("tool.idtoolkit.layout.capacity.exhausted", exhausted.toString()));
            capacityLifetime.setForeground(Tokens.danger());
        } else {
            capacityLifetime.setText(I18n.get("tool.idtoolkit.layout.capacity.lifetime", formatCount(years), year));
            capacityLifetime.setForeground(Tokens.mutedForeground());
        }
    }

    private void rebuildEncodeFields(SnowflakeLayout layout) {
        for (Map.Entry<String, JSpinner> entry : encodeSpinners.entrySet()) {
            encodeMemory.put(entry.getKey(), ((Number) entry.getValue().getValue()).longValue());
        }
        encodeSpinners.clear();
        encodeFieldHost.removeAll();
        if (layout != null) {
            for (SnowflakeLayout.Field field : layout.fields()) {
                long remembered = encodeMemory.getOrDefault(field.name(), 0L);
                JSpinner spinner = longSpinner(Math.min(remembered, field.maxValue()), field.maxValue());
                spinner.setToolTipText(I18n.get("tool.idtoolkit.gen.fieldRange",
                        String.valueOf(field.maxValue()), String.valueOf(field.bits())));
                encodeSpinners.put(field.name(), spinner);
                encodeFieldHost.add(labeled(fieldLabel(field.name()), spinner));
            }
        }
        encodeFieldHost.revalidate();
        encodeFieldHost.repaint();
    }

    void encode() {
        SnowflakeLayout layout = currentLayout;
        if (layout == null) {
            setStatus(encodeStatus, I18n.get("tool.idtoolkit.gen.layoutInvalid"), Tokens.danger());
            return;
        }
        try {
            Instant instant = SnowflakeCodec.parseInstant(encodeInstantField.getText(), ZoneId.systemDefault());
            Map<String, Long> values = new LinkedHashMap<>();
            for (Map.Entry<String, JSpinner> entry : encodeSpinners.entrySet()) {
                values.put(entry.getKey(), ((Number) entry.getValue().getValue()).longValue());
            }
            long id = SnowflakeCodec.encode(layout, instant, values);
            encodeResultField.setText(Long.toString(id));
            long floored = layout.ticksToMillis(SnowflakeCodec.ticksOf(layout, instant.toEpochMilli()));
            setStatus(encodeStatus, I18n.get("tool.idtoolkit.layout.encoded", "0x" + Long.toHexString(id),
                    Instant.ofEpochMilli(floored).toString()), Tokens.success());
        } catch (IdCodecException e) {
            encodeResultField.setText("");
            setStatus(encodeStatus, errorText(e), Tokens.danger());
        }
    }

    /** 按布局页当前布局解码一个 ID，便于验证自定义布局 */
    private void decodeWithLayout() {
        if (layoutDecodeField == null) {
            return;
        }
        String text = layoutDecodeField.getText().trim();
        if (text.isEmpty()) {
            layoutDecodeOutput.setText("");
            return;
        }
        SnowflakeLayout layout = currentLayout;
        if (layout == null) {
            layoutDecodeOutput.setText(I18n.get("tool.idtoolkit.gen.layoutInvalid"));
            return;
        }
        long id;
        try {
            String lower = text.toLowerCase(Locale.ROOT);
            id = lower.startsWith("0x") ? Long.parseUnsignedLong(lower.substring(2), 16) : Long.parseLong(text);
        } catch (NumberFormatException e) {
            layoutDecodeOutput.setText(I18n.get("tool.idtoolkit.layout.decode.notNumber"));
            return;
        }
        SnowflakeCodec.Decoded decoded = SnowflakeCodec.decode(id, layout);
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder out = new StringBuilder();
        Instant time = decoded.timestamp();
        appendLine(out, I18n.get("tool.idtoolkit.decode.detail.local", zone.getId()), formatLocal(time, zone));
        appendLine(out, I18n.get("tool.idtoolkit.decode.detail.iso"), time.toString());
        appendLine(out, I18n.get("tool.idtoolkit.decode.detail.epochMillis"), epochMillis(time));
        appendLine(out, I18n.get("tool.idtoolkit.detail.ticks"), String.valueOf(decoded.ticks()));
        decoded.fields().forEach((name, value) -> appendLine(out, fieldLabel(name), String.valueOf(value)));
        for (SnowflakeCodec.Warning warning : decoded.warnings()) {
            String code = warning == SnowflakeCodec.Warning.SIGN_BIT_SET ? IdDetector.W_SIGN_BIT
                    : warning == SnowflakeCodec.Warning.UNUSED_BITS_SET ? IdDetector.W_UNUSED_BITS
                    : IdDetector.W_FUTURE;
            out.append("  ! ").append(warningText(code)).append('\n');
        }
        layoutDecodeOutput.setText(out.toString());
        layoutDecodeOutput.setCaretPosition(0);
    }

    // ==========================================
    // 通用小工具
    // ==========================================

    /** long 取值的微调器：Fields.spinner 只支持 int，而 62 位字段的上限远超 int */
    private static JSpinner longSpinner(long value, long max) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(Long.valueOf(value), Long.valueOf(0L),
                Long.valueOf(max), Long.valueOf(1L)));
        spinner.setFont(Tokens.fontBody());
        // 去掉千分位，便于直接粘贴数值
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
        Dimension size = new Dimension(max > 99_999_999L ? 180 : 110, Tokens.CONTROL_HEIGHT);
        spinner.setPreferredSize(size);
        spinner.setMinimumSize(size);
        return spinner;
    }

    private static JPanel labeled(String label, JComponent field) {
        JPanel pair = new JPanel(new BorderLayout(Tokens.SPACE_XS, 0));
        pair.setOpaque(false);
        JLabel text = new JLabel(label);
        text.setFont(Tokens.fontBody());
        pair.add(text, BorderLayout.WEST);
        pair.add(field, BorderLayout.CENTER);
        return pair;
    }

    private static DocumentListener onChange(Runnable action) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                action.run();
            }
        };
    }

    private static void copy(String text) {
        if (text != null && !text.isEmpty()) {
            UIUtils.copyToClipboard(text);
        }
    }

    private static void setStatus(JLabel label, String text, Color color) {
        label.setText(text);
        label.setForeground(color);
    }

    /** 容量数字：整数加千分位，小数保留两位，极大值用科学计数法 */
    static String formatCount(double value) {
        if (value >= 1e15) {
            return String.format(Locale.ROOT, "%.3e", value);
        }
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%,d", (long) value);
        }
        return String.format(Locale.ROOT, "%,.2f", value);
    }

    // ---------------------------------------------------------------- 测试钩子

    JTextArea decodeInputForTest() {
        return decodeInput;
    }

    JTable decodeTableForTest() {
        return decodeTable;
    }

    JTextArea decodeDetailForTest() {
        return decodeDetail;
    }

    SnowflakeLayout currentLayoutForTest() {
        return currentLayout;
    }

    JTextField epochFieldForTest() {
        return epochField;
    }

    JTextField encodeResultForTest() {
        return encodeResultField;
    }

    JTextField encodeInstantForTest() {
        return encodeInstantField;
    }

    JComboBox<String> layoutPresetComboForTest() {
        return layoutPresetCombo;
    }

    @Override
    public void closeResources() {
        Timer running = debounce;
        debounce = null;
        if (running != null) {
            running.stop();
        }
        SwingWorker<List<String>, Void> worker = genWorker;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
    }

    /**
     * 64 位布局示意图：每位一格，按段着色，段下方标注名称与位宽，上方标注段起始位号。
     *
     * <p>颜色在绘制时从 {@link Tokens} 取，切换深浅主题后无需重建。</p>
     */
    static final class BitDiagram extends JComponent {

        static final int ROLE_SIGN = -2;
        static final int ROLE_UNUSED = -1;
        private static final int CELL_HEIGHT = 22;

        /**
         * @param role 0 为时间戳，1.. 为各字段，负数为符号位 / 空闲位
         */
        record Segment(String label, int bits, int role) {
        }

        private List<Segment> segments = List.of();
        private String message;

        BitDiagram() {
            setOpaque(false);
            ToolTipManager.sharedInstance().registerComponent(this);
        }

        void setSegments(List<Segment> newSegments, String newMessage) {
            segments = List.copyOf(newSegments);
            message = newMessage;
            repaint();
        }

        List<Segment> segments() {
            return segments;
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(Tokens.fontCaption());
            return new Dimension(520, metrics.getHeight() * 2 + CELL_HEIGHT + Tokens.SPACE_SM);
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension preferred = getPreferredSize();
            return new Dimension(200, preferred.height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        private Color colorOf(int role) {
            if (role == ROLE_SIGN) {
                return Tokens.mutedForeground();
            }
            if (role == ROLE_UNUSED) {
                return Tokens.border();
            }
            Color[] palette = {
                    Tokens.accent(), Tokens.success(), Tokens.warning(), Tokens.danger(),
                    Tokens.blend(Tokens.accent(), Tokens.danger(), 0.5f),
                    Tokens.blend(Tokens.success(), Tokens.accent(), 0.5f)};
            return palette[role % palette.length];
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(Tokens.fontCaption());
                FontMetrics metrics = g.getFontMetrics();
                Insets insets = getInsets();
                int left = insets.left;
                int width = getWidth() - insets.left - insets.right - 1;
                if (message != null) {
                    g.setColor(Tokens.danger());
                    g.drawString(message, left, insets.top + metrics.getAscent() + CELL_HEIGHT / 2);
                    return;
                }
                double cell = width / 64.0;
                int top = insets.top + metrics.getHeight();
                Color card = Tokens.cardBackground();
                int bit = 63;
                int offset = 0;
                for (Segment segment : segments) {
                    int x0 = left + (int) Math.round(offset * cell);
                    int x1 = left + (int) Math.round((offset + segment.bits()) * cell);
                    Color color = colorOf(segment.role());
                    g.setColor(Tokens.blend(color, card, 0.72f));
                    g.fillRect(x0, top, x1 - x0, CELL_HEIGHT);
                    // 每一位的分隔细线
                    g.setColor(Tokens.blend(color, card, 0.45f));
                    for (int i = 1; i < segment.bits(); i++) {
                        int x = left + (int) Math.round((offset + i) * cell);
                        g.drawLine(x, top + CELL_HEIGHT - 5, x, top + CELL_HEIGHT - 1);
                    }
                    g.setColor(color);
                    g.drawRect(x0, top, x1 - x0, CELL_HEIGHT);

                    g.setColor(Tokens.mutedForeground());
                    String index = String.valueOf(bit);
                    if (metrics.stringWidth(index) + 2 <= x1 - x0 || segment.bits() > 1) {
                        g.drawString(index, x0 + 2, top - metrics.getDescent());
                    }
                    String full = segment.label() + " " + segment.bits();
                    String shortText = String.valueOf(segment.bits());
                    String text = metrics.stringWidth(full) + 4 <= x1 - x0 ? full
                            : metrics.stringWidth(shortText) + 2 <= x1 - x0 ? shortText : "";
                    if (!text.isEmpty()) {
                        g.setColor(Tokens.foreground());
                        int textX = x0 + (x1 - x0 - metrics.stringWidth(text)) / 2;
                        g.drawString(text, textX, top + CELL_HEIGHT + metrics.getAscent() + 2);
                    }
                    bit -= segment.bits();
                    offset += segment.bits();
                }
                if (!segments.isEmpty()) {
                    g.setColor(Tokens.mutedForeground());
                    String zero = "0";
                    g.drawString(zero, left + width - metrics.stringWidth(zero) - 2, top - metrics.getDescent());
                }
            } finally {
                g.dispose();
            }
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int width = getWidth() - getInsets().left - getInsets().right - 1;
            if (segments.isEmpty() || width <= 0) {
                return null;
            }
            int position = (int) ((event.getX() - getInsets().left) / (width / 64.0));
            int offset = 0;
            for (Segment segment : segments) {
                if (position >= offset && position < offset + segment.bits()) {
                    int high = 63 - offset;
                    int low = high - segment.bits() + 1;
                    return segment.label() + " [" + high + ".." + low + "] " + segment.bits();
                }
                offset += segment.bits();
            }
            return null;
        }
    }
}