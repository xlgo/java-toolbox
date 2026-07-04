package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.List;

/**
 * 随机数据生成器：生成测试用的姓名、手机号、邮箱、地址、身份证号、银行卡号。
 */
public class RandomNumberPanel extends ToolPanel {

    private JSpinner countSpinner;
    private JCheckBox nameCb, phoneCb, emailCb, addrCb, idCardCb, bankCb;
    private JRadioButton lineMode, tableMode;
    private JTextArea out;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SEP = "\t";

    // ==================== 百家姓 TOP 120 ====================
    private static final String[] SURNAMES = {
        "赵","钱","孙","李","周","吴","郑","王","冯","陈","褚","卫","蒋","沈","韩","杨",
        "朱","秦","尤","许","何","吕","施","张","孔","曹","严","华","金","魏","陶","姜",
        "戚","谢","邹","喻","柏","水","窦","章","云","苏","潘","葛","奚","范","彭","郎",
        "鲁","韦","昌","马","苗","凤","花","方","俞","任","袁","柳","酆","鲍","史","唐",
        "费","廉","岑","薛","雷","贺","倪","汤","滕","殷","罗","毕","郝","邬","安","常",
        "乐","于","时","傅","皮","卞","齐","康","伍","余","元","卜","顾","孟","平","黄",
        "和","穆","萧","尹","姚","邵","湛","汪","祁","毛","禹","狄","米","贝","明","臧",
        "计","伏","成","戴","谈","宋","茅","庞"
    };

    private static final String[] GIVEN_NAMES_2 = {
        "伟","芳","娜","敏","静","丽","强","磊","军","洋","勇","艳","杰","倩","鹏",
        "宇","鑫","志","飞","斌","浩","凯","超","明","建","国","文","华","德","海",
        "峰","林","平","刚","东","辉","松","胜","婷","凡","涛","亮","波","晨","霖",
        "浩","龙","威","成","康","欢","玲","颖","丹","霞","娟","秀兰","秀英","桂英"
    };

    private static final String[] GIVEN_NAMES_1 = {
        "雪","云","梅","莲","杰","涛","峰","林","鹏","浩","龙","威","娟","玲","军"
    };

    // ==================== 手机号前缀 ====================
    private static final String[] PHONE_PREFIXES = {
        "130","131","132","133","134","135","136","137","138","139",
        "145","147","149",
        "150","151","152","153","155","156","157","158","159",
        "166","167",
        "170","171","172","173","175","176","177","178",
        "180","181","182","183","184","185","186","187","188","189",
        "190","191","193","195","196","197","198","199"
    };

    // ==================== 邮箱域名 ====================
    private static final String[] EMAIL_DOMAINS = {
        "qq.com","163.com","gmail.com","outlook.com","sina.com",
        "foxmail.com","126.com","yeah.net","hotmail.com","aliyun.com",
        "sina.cn","sohu.com","21cn.com","139.com"
    };

    // ==================== 地址数据（省/市/区/路） ====================
    private static final String[][] CITIES = {
        {"北京市","北京市","东城区","西城区","朝阳区","海淀区","丰台区","石景山区","通州区","大兴区"},
        {"上海市","上海市","浦东新区","黄浦区","徐汇区","静安区","长宁区","普陀区","杨浦区","闵行区"},
        {"广州市","广东省","天河区","越秀区","海珠区","白云区","番禺区","荔湾区","黄埔区","花都区"},
        {"深圳市","广东省","南山区","福田区","罗湖区","宝安区","龙岗区","盐田区","龙华区","坪山区"},
        {"杭州市","浙江省","西湖区","上城区","拱墅区","滨江区","萧山区","余杭区","临平区","钱塘区"},
        {"成都市","四川省","武侯区","锦江区","青羊区","金牛区","成华区","高新区","天府新区","双流区"},
        {"武汉市","湖北省","武昌区","江汉区","江岸区","硚口区","洪山区","汉阳区","青山区","东西湖区"},
        {"南京市","江苏省","鼓楼区","玄武区","秦淮区","建邺区","栖霞区","雨花台区","江宁区","浦口区"},
        {"重庆市","重庆市","渝中区","江北区","南岸区","沙坪坝区","九龙坡区","渝北区","巴南区","大渡口区"},
        {"西安市","陕西省","雁塔区","碑林区","莲湖区","新城区","未央区","长安区","灞桥区","临潼区"},
        {"天津市","天津市","和平区","河东区","河西区","南开区","河北区","红桥区","滨海新区","东丽区"},
        {"苏州市","江苏省","姑苏区","虎丘区","吴中区","相城区","吴江区","苏州工业园区","常熟市","张家港市"},
        {"长沙市","湖南省","岳麓区","芙蓉区","天心区","开福区","雨花区","望城区","长沙县","浏阳市"},
        {"郑州市","河南省","金水区","中原区","二七区","管城区","惠济区","郑东新区","高新区","经开区"},
        {"东莞市","广东省","莞城街道","东城街道","南城街道","万江街道","虎门镇","长安镇","厚街镇","塘厦镇"}
    };

    private static final String[] STREET_TYPES = {"路","大道","街","巷","弄"};
    private static final String[] BUILDING_TYPES = {"号","号院","小区","大厦","座"};

    // ==================== 身份证地区代码（前6位） ====================
    private static final String[] ID_AREA_CODES = {
        "110101","110102","110105","110106","110107","110108","110109","110111",
        "310101","310104","310105","310106","310107","310109","310110","310112",
        "440101","440103","440104","440105","440106","440113","440114","440118",
        "440301","440303","440304","440305","440306","440307","440308","440309",
        "330101","330102","330103","330105","330106","330108","330109","330110",
        "510101","510104","510105","510106","510107","510108","510112","510113",
        "420101","420102","420103","420104","420105","420106","420111","420112",
        "320101","320102","320104","320105","320106","320111","320113","320114",
        "500101","500102","500103","500104","500105","500106","500107","500108",
        "120101","120102","120103","120104","120105","120106","120107","120108"
    };

    // ==================== 银行卡 BIN 码 ====================
    private static final String[][] BANK_BINS = {
        {"622202","工商银行"},{"622200","工商银行"},{"955880","工商银行"},
        {"622848","农业银行"},{"95599","农业银行"},{"622820","农业银行"},
        {"621700","建设银行"},{"622700","建设银行"},{"622168","建设银行"},
        {"601382","中国银行"},{"621661","中国银行"},{"621660","中国银行"},
        {"622580","招商银行"},{"622588","招商银行"},{"621485","招商银行"},
        {"622262","交通银行"},{"622260","交通银行"},{"405512","交通银行"},
        {"622658","邮储银行"},{"621098","邮储银行"},{"622150","邮储银行"},
        {"622908","兴业银行"},{"622909","兴业银行"},{"486497","兴业银行"},
        {"622689","中信银行"},{"622698","中信银行"},{"433669","中信银行"},
        {"622155","浦发银行"},{"622156","浦发银行"},{"984301","浦发银行"},
        {"622919","民生银行"},{"622620","民生银行"},{"415599","民生银行"}
    };

    // ==================== 构造方法 ====================
    public RandomNumberPanel() {
        super("generate", "random.data",
                "随机数据", "Mock Data", "假数据", "测试数据",
                "姓名", "手机号", "邮箱", "地址", "身份证", "银行卡");
    }

    // ==================== 界面构建 ====================
    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部：配置区 =====
        JPanel config = new JPanel(new GridBagLayout());
        config.setBorder(BorderFactory.createTitledBorder("随机数据配置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 8, 5, 8);

        // 第1行：数据类型勾选
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0; gbc.gridwidth = 1;
        config.add(new JLabel("数据类型："), gbc);

        gbc.gridx = 1; gbc.gridwidth = 4; gbc.weightx = 1.0;
        JPanel typePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        nameCb   = new JCheckBox("姓名", true);
        phoneCb  = new JCheckBox("手机号", true);
        emailCb  = new JCheckBox("邮箱", true);
        addrCb   = new JCheckBox("地址", true);
        idCardCb = new JCheckBox("身份证号", true);
        bankCb   = new JCheckBox("银行卡号", true);
        JCheckBox[] cbs = {nameCb, phoneCb, emailCb, addrCb, idCardCb, bankCb};
        for (JCheckBox cb : cbs) {
            cb.setFont(UIUtils.plainFont());
            typePanel.add(cb);
        }
        config.add(typePanel, gbc);

        // 第2行：数量 / 输出格式 / 按钮
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0; gbc.gridwidth = 1;
        config.add(new JLabel("生成数量："), gbc);

        gbc.gridx = 1; gbc.weightx = 0.15;
        countSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 5000, 1));
        countSpinner.setEditor(new JSpinner.NumberEditor(countSpinner, "#"));
        config.add(countSpinner, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        config.add(new JLabel("输出格式："), gbc);

        gbc.gridx = 3; gbc.weightx = 0.3;
        JPanel fmtPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ButtonGroup fmtGroup = new ButtonGroup();
        lineMode  = new JRadioButton("逐行", false);
        tableMode = new JRadioButton("表格（Tab分隔）", true);
        lineMode.setFont(UIUtils.plainFont());
        tableMode.setFont(UIUtils.plainFont());
        fmtGroup.add(lineMode);
        fmtGroup.add(tableMode);
        fmtPanel.add(lineMode);
        fmtPanel.add(tableMode);
        config.add(fmtPanel, gbc);

        gbc.gridx = 4; gbc.weightx = 0.5;
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton genBtn  = UIUtils.button("生成数据", 100);
        JButton copyBtn = UIUtils.button("复制全部", 100);
        btnPanel.add(genBtn);
        btnPanel.add(copyBtn);
        config.add(btnPanel, gbc);

        root.add(config, BorderLayout.NORTH);

        // ===== 中部：输出区 =====
        out = new JTextArea(18, 50);
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);
        root.add(UIUtils.scrollText(out, "生成的随机数据"), BorderLayout.CENTER);

        // ===== 事件 =====
        genBtn.addActionListener(e -> generate());
        copyBtn.addActionListener(e -> {
            String text = out.getText();
            if (!text.isEmpty()) {
                UIUtils.copyToClipboard(text);
                UIUtils.info(root, "已复制到剪贴板");
            }
        });

        generate();
        return root;
    }

    // ==================== 核心生成逻辑 ====================
    private void generate() {
        int count = (Integer) countSpinner.getValue();

        // 校验至少勾选一个
        if (!nameCb.isSelected() && !phoneCb.isSelected() && !emailCb.isSelected()
                && !addrCb.isSelected() && !idCardCb.isSelected() && !bankCb.isSelected()) {
            UIUtils.error(out, "请至少勾选一种数据类型！");
            return;
        }

        // 收集哪些类型被选中
        List<String> activeFields = new ArrayList<>();
        if (nameCb.isSelected())   activeFields.add("name");
        if (phoneCb.isSelected())  activeFields.add("phone");
        if (emailCb.isSelected())  activeFields.add("email");
        if (addrCb.isSelected())   activeFields.add("addr");
        if (idCardCb.isSelected()) activeFields.add("idcard");
        if (bankCb.isSelected())   activeFields.add("bank");

        StringBuilder sb = new StringBuilder();

        if (tableMode.isSelected()) {
            // ===== 表格模式 =====
            // 表头
            for (int i = 0; i < activeFields.size(); i++) {
                if (i > 0) sb.append(SEP);
                sb.append(fieldLabel(activeFields.get(i)));
            }
            sb.append('\n');

            // 数据行
            for (int r = 0; r < count; r++) {
                for (int i = 0; i < activeFields.size(); i++) {
                    if (i > 0) sb.append(SEP);
                    sb.append(generateField(activeFields.get(i)));
                }
                sb.append('\n');
            }
        } else {
            // ===== 逐行模式 =====
            for (int r = 0; r < count; r++) {
                sb.append("--- 第 ").append(r + 1).append(" 条 ---\n");
                for (String f : activeFields) {
                    sb.append(fieldLabel(f)).append("：").append(generateField(f)).append('\n');
                }
                sb.append('\n');
            }
        }

        out.setText(sb.toString());
    }

    // ==================== 字段标签 ====================
    private String fieldLabel(String field) {
        switch (field) {
            case "name":   return "姓名";
            case "phone":  return "手机号";
            case "email":  return "邮箱";
            case "addr":   return "地址";
            case "idcard": return "身份证号";
            case "bank":   return "银行卡号";
            default:       return field;
        }
    }

    // ==================== 单个字段生成 ====================
    private String generateField(String field) {
        switch (field) {
            case "name":   return genName();
            case "phone":  return genPhone();
            case "email":  return genEmail();
            case "addr":   return genAddress();
            case "idcard": return genIdCard();
            case "bank":   return genBankCard();
            default:       return "???";
        }
    }

    // ==================== 生成姓名 ====================
    private String genName() {
        String sur = SURNAMES[RANDOM.nextInt(SURNAMES.length)];
        // 3/4 概率双字名，1/4 概率单字名
        if (RANDOM.nextInt(4) < 3) {
            return sur + GIVEN_NAMES_2[RANDOM.nextInt(GIVEN_NAMES_2.length)];
        } else {
            return sur + GIVEN_NAMES_1[RANDOM.nextInt(GIVEN_NAMES_1.length)];
        }
    }

    // ==================== 生成手机号 ====================
    private String genPhone() {
        String prefix = PHONE_PREFIXES[RANDOM.nextInt(PHONE_PREFIXES.length)];
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < 8; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    // ==================== 生成邮箱 ====================
    private String genEmail() {
        String domain = EMAIL_DOMAINS[RANDOM.nextInt(EMAIL_DOMAINS.length)];
        StringBuilder local = new StringBuilder();
        int len = 5 + RANDOM.nextInt(10);
        // 先拼字母数字部分
        for (int i = 0; i < len; i++) {
            if (RANDOM.nextBoolean()) {
                local.append((char)('a' + RANDOM.nextInt(26)));
            } else {
                local.append(RANDOM.nextInt(10));
            }
        }
        // 30% 概率在中间加一个点（更真实，最多一个点）
        if (RANDOM.nextInt(10) < 3) {
            int pos = 2 + RANDOM.nextInt(local.length() - 2);
            local.insert(pos, '.');
        }
        return local.toString() + "@" + domain;
    }

    // ==================== 生成地址 ====================
    private String genAddress() {
        int ci = RANDOM.nextInt(CITIES.length);
        String city = CITIES[ci][0];
        String province = CITIES[ci][1];
        String district = CITIES[ci][2 + RANDOM.nextInt(CITIES[ci].length - 2)];

        // 30% 概率只生成到区/县
        if (RANDOM.nextInt(10) < 3) {
            return province + city + district;
        }

        String street = genStreetName();
        String detail = RANDOM.nextInt(5000) + STREET_TYPES[RANDOM.nextInt(STREET_TYPES.length)];
        if (RANDOM.nextBoolean()) {
            detail += BUILDING_TYPES[RANDOM.nextInt(BUILDING_TYPES.length)];
            if (RANDOM.nextBoolean()) {
                detail += RANDOM.nextInt(30) + "单元" + (RANDOM.nextInt(30) + 1) + "室";
            }
        }

        return province + city + district + street + detail;
    }

    private String genStreetName() {
        String[] roadNames = {
            "中山","人民","解放","建设","和平","长安","朝阳","胜利",
            "新华","南京","北京","上海","广州","深圳","科技","创新",
            "发展","创业","滨江","学府","文化","花园","明珠","东门",
            "西湖","天一","春熙","中关村","陆家嘴","金融"
        };
        return roadNames[RANDOM.nextInt(roadNames.length)];
    }

    // ==================== 生成身份证号 ====================
    private String genIdCard() {
        String area = ID_AREA_CODES[RANDOM.nextInt(ID_AREA_CODES.length)];

        // 出生日期：1970-2003 年
        int year = 1970 + RANDOM.nextInt(34);
        int month = 1 + RANDOM.nextInt(12);
        int maxDay;
        switch (month) {
            case 2: maxDay = (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28; break;
            case 4: case 6: case 9: case 11: maxDay = 30; break;
            default: maxDay = 31;
        }
        int day = 1 + RANDOM.nextInt(maxDay);
        String birth = String.format("%04d%02d%02d", year, month, day);

        // 顺序码（3位）
        String seq = String.format("%03d", RANDOM.nextInt(1000));

        // 前17位
        String body = area + birth + seq;
        char check = calcIdCardCheck(body);
        return body + check;
    }

    /** ISO 7064:1983, MOD 11-2 身份证校验码 */
    private char calcIdCardCheck(String body17) {
        int[] w = {7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};
        char[] checkCode = {'1','0','X','9','8','7','6','5','4','3','2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (body17.charAt(i) - '0') * w[i];
        }
        return checkCode[sum % 11];
    }

    // ==================== 生成银行卡号 ====================
    private String genBankCard() {
        String[] binInfo = BANK_BINS[RANDOM.nextInt(BANK_BINS.length)];
        String bin = binInfo[0];
        // 借记卡一般 16-19 位，前面已6位BIN
        int totalLen = 16 + RANDOM.nextInt(4); // 16-19位
        int remainLen = totalLen - bin.length() - 1; // 减1给校验位

        StringBuilder sb = new StringBuilder(bin);
        for (int i = 0; i < remainLen; i++) {
            sb.append(RANDOM.nextInt(10));
        }

        // Luhn 校验位
        char luhn = calcLuhn(sb.toString());
        sb.append(luhn);
        return sb.toString();
    }

    /** Luhn 算法计算银行卡校验位 */
    private char calcLuhn(String withoutCheck) {
        int sum = 0;
        boolean alternate = true;
        for (int i = withoutCheck.length() - 1; i >= 0; i--) {
            int n = withoutCheck.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) n = n - 9;
            }
            sum += n;
            alternate = !alternate;
        }
        int check = (10 - (sum % 10)) % 10;
        return (char) ('0' + check);
    }
}
