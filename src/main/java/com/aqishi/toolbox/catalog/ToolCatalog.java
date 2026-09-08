package com.aqishi.toolbox.catalog;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Java Toolbox 的唯一工具目录。稳定 ID、分类和搜索词在这里集中维护。
 */
public final class ToolCatalog {

    public static final ToolCategory SECURITY = category("security", 0);
    public static final ToolCategory CODEC = category("codec", 1);
    public static final ToolCategory NETWORK = category("network", 2);
    public static final ToolCategory DATA = category("data", 3);
    public static final ToolCategory CLOUD = category("cloud", 4);
    public static final ToolCategory SYSTEM = category("system", 5);
    public static final ToolCategory GENERATION = category("generation", 6);
    public static final ToolCategory COMPUTE = category("compute", 7);
    public static final ToolCategory DIAGRAM = category("diagram", 8);
    public static final ToolCategory MONITOR = category("monitor", 9);

    public static final ToolDescriptor HASH_CODEC = tool("hash.codec", "security",
            "MD5", "SHA-1", "SHA-256", "SHA256", "SM3", "哈希", "Hash", "消息摘要", "散列",
            "Base64", "编解码", "编码", "解码", "国密", "Hmac", "HMAC");
    public static final ToolDescriptor SYMMETRIC_CRYPTO = tool("symmetric.crypto", "security",
            "AES", "DES", "3DES", "SM4", "国密", "ECB", "CBC", "PKCS5", "密钥", "加密", "解密", "对称");
    public static final ToolDescriptor ASYMMETRIC_CRYPTO = tool("asymmetric.crypto", "security",
            "RSA", "SM2", "国密", "公钥", "私钥", "签名", "验签", "非对称", "密钥对", "数字签名");
    public static final ToolDescriptor CERT_MANAGEMENT = tool("cert.management", "security",
            "证书", "CA", "根证书", "自签证书", "X.509", "SSL", "TLS", "PKI", "证书解析",
            "certificate", "ACME", "Let's Encrypt", "免费证书", "Cloudflare");
    public static final ToolDescriptor ACCOUNT_MANAGER = tool("account.manager", "security",
            "密码管理", "账号密码", "密码簿", "Password Manager", "Account", "Keeper");
    public static final ToolDescriptor TOTP_AUTHENTICATOR = tool("totp.authenticator", "security",
            "谷歌验证器", "Google Authenticator", "2FA", "OTP", "MFA", "双因素认证", "身份验证", "totp", "authenticator");
    public static final ToolDescriptor JWT_CODEC = tool("jwt.codec", "security",
            "JWT", "Token", "HS256", "签名", "JWT解码", "JWT编码", "Json Web Token", "JWT验证", "令牌");
    public static final ToolDescriptor FILE_BATCH_DIGEST = tool("file.batch.digest", "security",
            "哈希", "摘要", "批量摘要", "Checksum", "sha256sum", "md5sum", "文件签名", "验签", "数字签名", "SM3", "SHA-256");
    public static final ToolDescriptor CERT_INSPECTOR = tool("cert.inspector", "security",
            "CSR", "PKCS12", "P12", "PFX", "证书链", "Chain", "证书检查", "Keystore", "密钥库", "证书诊断");

    public static final ToolDescriptor RADIX_ENCODING = tool("radix.encoding", "codec",
            "二进制", "八进制", "十进制", "十六进制", "Hex", "UTF-8", "UTF8", "GBK", "ISO-8859-1",
            "URL编码", "URL解码", "进制转换", "编码转换", "字符编码");
    public static final ToolDescriptor TIMESTAMP = tool("timestamp", "codec",
            "Unix", "Timestamp", "时间戳", "日期转换", "时区", "毫秒", "秒戳", "DateTime", "时间格式化");
    public static final ToolDescriptor BASE64_IMAGE = tool("base64.image", "codec",
            "Base64", "图片", "Image", "DataURI", "Data URI", "图片编码", "图片解码", "图片转换");
    public static final ToolDescriptor URL_TOOL = tool("url.tool", "codec",
            "url", "uri", "encode", "decode", "query", "parameter", "params", "http", "编码", "解码");
    public static final ToolDescriptor FORMAT_CONVERT = tool("format.convert", "codec",
            "JSON", "XML", "YAML", "CSV", "Properties", "格式互转", "数据转换", "序列化");
    public static final ToolDescriptor JSON_FORMAT = tool("json.format", "codec",
            "JSON", "美化", "压缩", "格式化", "Json美化", "Json压缩", "格式化JSON");
    public static final ToolDescriptor XML_FORMAT = tool("xml.format", "codec",
            "XML", "美化", "压缩", "格式化", "Xml美化", "Xml压缩");
    public static final ToolDescriptor SQL_FORMAT = tool("sql.format", "codec",
            "SQL", "美化", "格式化", "Sql美化", "SQL美化", "关键字大写");
    public static final ToolDescriptor STRING_TOOL = tool("string.tool", "codec",
            "String", "Length", "Delete", "Trim", "Uppercase", "Lowercase", "Regex", "字符串", "长度", "删除", "过滤", "大写", "小写", "统计");
    public static final ToolDescriptor REGEX_TESTER = tool("regex.tester", "codec",
            "Regex", "正则表达式", "匹配", "正则", "正则匹配", "正则测试", "Pattern");
    public static final ToolDescriptor TEXT_DIFF = tool("text.diff", "codec",
            "Diff", "差异", "对比", "文本差异", "差异比较", "LCS", "比较");
    public static final ToolDescriptor JSONPATH_TESTER = tool("jsonpath.tester", "codec",
            "JSONPath", "JsonPath", "JSON", "Path", "提取", "查询", "过滤器", "Query", "过滤", "JMESPath");

    public static final ToolDescriptor HTTP_CLIENT = tool("http.client", "network",
            "HTTP", "接口测试", "API", "Request", "Postman", "Curl");
    public static final ToolDescriptor OPENAPI_WORKBENCH = tool("openapi.workbench", "network",
            "OpenAPI", "Swagger", "接口文档", "API", "工作台", "调试", "cURL", "Postman", "Schema", "REST");
    public static final ToolDescriptor CALLBACK_MOCK = tool("callback.mock", "network",
            "回调", "接口测试", "Mock", "Webhook", "Server", "服务器", "HTTP Mock");
    public static final ToolDescriptor WEBSOCKET_CLIENT = tool("websocket.client", "network",
            "websocket", "ws", "wss", "socket", "connect", "client", "测试", "长连接");
    public static final ToolDescriptor MQTT_CLIENT = tool("mqtt.client", "network",
            "mqtt", "iot", "emqx", "broker", "publish", "subscribe", "消息队列", "物联网", "测试");
    public static final ToolDescriptor SUBNET_CALC = tool("subnet.calc", "network",
            "Subnet", "CIDR", "IP", "子网掩码", "网络地址", "广播地址", "子网");
    public static final ToolDescriptor PORT_SCANNER = tool("port.scanner", "network",
            "port", "scanner", "network", "ping", "nmap", "tcp", "端口扫描", "网络诊断");
    public static final ToolDescriptor SSH = tool("ssh", "network",
            "ssh", "terminal", "sftp", "shell", "服务器", "远程连接");

    public static final ToolDescriptor DATABASE_CONNECTOR = tool("database.connector", "data",
            "Database", "SQL", "MySQL", "Postgres", "Oracle", "JDBC", "连接器", "客户端");
    public static final ToolDescriptor REDIS_MANAGEMENT = tool("redis.management", "data",
            "Redis", "缓存", "NoSQL", "Key-Value", "数据库", "命令行", "Console");
    public static final ToolDescriptor KAFKA_CONNECTOR = tool("kafka.connector", "data",
            "Kafka", "Message", "Consumer", "Group", "Lag", "Topic", "队列", "消息");
    public static final ToolDescriptor ZOOKEEPER_MANAGEMENT = tool("zookeeper.management", "data",
            "ZooKeeper", "Zookeeper", "ZK", "节点", "分布式协调", "注册中心");

    public static final ToolDescriptor DOCKER_CONVERT = tool("docker.convert", "cloud",
            "Docker", "Compose", "容器", "docker-compose", "docker run", "容器编排");
    public static final ToolDescriptor K8S_DEPLOYMENT = tool("k8s.deployment", "cloud",
            "K8s", "Kubernetes", "部署", "YAML", "容器", "Deployment", "Service", "Ingress", "ConfigMap", "编排");
    public static final ToolDescriptor K8S_MANAGER = tool("k8s.manager", "cloud",
            "k8s", "kubernetes", "容器", "集群", "运维", "kubeconfig", "docker", "pod", "deployment");

    public static final ToolDescriptor CHMOD_CALC = tool("chmod.calc", "system",
            "chmod", "permission", "linux", "octal", "rwxrwxrwx", "755", "777", "644", "权限");
    public static final ToolDescriptor CRON_PARSER = tool("cron.parser", "system",
            "Cron", "定时", "调度", "表达式", "Cron表达式", "定时任务", "crontab");
    public static final ToolDescriptor HOSTS_MANAGER = tool("hosts.manager", "system",
            "hosts", "domain", "dns", "ip", "environment", "环境", "域名", "解析");
    public static final ToolDescriptor WECHAT_SENDER = tool("wechat.sender", "system",
            "微信", "群发", "WeChat", "批量", "发送", "模拟按键", "联系人");

    public static final ToolDescriptor DATA_GENERATOR = tool("data.generator", "generation",
            "生成", "Generator", "数据", "密码", "UUID", "假数据", "Mock");
    public static final ToolDescriptor QRCODE = tool("qrcode", "generation",
            "qrcode", "qr", "barcode", "2dcode", "scan", "generate", "decode", "encode", "二维码", "条码");
    public static final ToolDescriptor COLOR_CONVERT = tool("color.convert", "generation",
            "HEX", "RGB", "HSL", "调色板", "Color", "颜色", "颜色选择", "色值");

    public static final ToolDescriptor CALCULATOR = tool("calculator", "compute",
            "表达式", "求值", "计算器", "Calc", "数学", "函数", "sqrt", "pow");
    public static final ToolDescriptor STATISTICS = tool("statistics", "compute",
            "均值", "中位数", "标准差", "方差", "Variance", "统计", "平均值", "极差", "总和", "最大", "最小");
    public static final ToolDescriptor SORT_VISUALIZER = tool("sort.visualizer", "compute",
            "冒泡", "Bubble", "选择", "Selection", "插入", "Insertion", "快速", "Quick", "归并", "Merge", "排序算法", "排序动画", "算法可视化");
    public static final ToolDescriptor SEARCH_ALGORITHM = tool("search.algorithm", "compute",
            "二分查找", "Binary Search", "线性查找", "Linear Search", "搜索算法", "查找");
    public static final ToolDescriptor HANOI = tool("hanoi", "compute", "Hanoi", "汉诺塔", "递归", "Tower of Hanoi");
    public static final ToolDescriptor PINGAME = tool("pingame", "compute", "见缝插针", "AA Pin Game", "Pin Game", "游戏", "AA", "见缝插针游戏");

    public static final ToolDescriptor BPMN_DESIGNER = tool("bpmn.designer", "diagram",
            "BPMN", "工作流", "设计器", "Workflow", "Process", "流程图", "Camunda", "Activiti");
    public static final ToolDescriptor MERMAID = tool("mermaid", "diagram",
            "Mermaid", "绘图", "画图", "流程图", "时序图", "UML", "图表", "diagram");
    public static final ToolDescriptor FLOWCHART = tool("flowchart", "diagram",
            "Flowchart", "流程图", "画图", "设计器", "ProcessOn", "Draw.io", "Diagram");

    public static final ToolDescriptor VIDEO_MONITOR = tool("video.monitor", "monitor",
            "视频", "监控", "摄像头", "Video", "camera", "RTSP", "分屏", "合并", "直播");
    public static final ToolDescriptor REMOTE_DESKTOP = tool("remote_desktop", "monitor",
            "p2p", "desktop", "control", "远程桌面", "远程控制");

    private static final List<ToolCategory> CATEGORIES = Collections.unmodifiableList(Arrays.asList(
            SECURITY, CODEC, NETWORK, DATA, CLOUD, SYSTEM, GENERATION, COMPUTE, DIAGRAM, MONITOR));
    private static final List<ToolDescriptor> DESCRIPTORS = Collections.unmodifiableList(Arrays.asList(
            HASH_CODEC, SYMMETRIC_CRYPTO, ASYMMETRIC_CRYPTO, CERT_MANAGEMENT, ACCOUNT_MANAGER,
            TOTP_AUTHENTICATOR, JWT_CODEC, FILE_BATCH_DIGEST, CERT_INSPECTOR,
            RADIX_ENCODING, TIMESTAMP, BASE64_IMAGE, URL_TOOL,
            FORMAT_CONVERT, JSON_FORMAT, XML_FORMAT, SQL_FORMAT, STRING_TOOL, REGEX_TESTER, TEXT_DIFF,
            JSONPATH_TESTER,
            HTTP_CLIENT, OPENAPI_WORKBENCH, CALLBACK_MOCK, WEBSOCKET_CLIENT, MQTT_CLIENT, SUBNET_CALC, PORT_SCANNER, SSH,
            DATABASE_CONNECTOR, REDIS_MANAGEMENT, KAFKA_CONNECTOR, ZOOKEEPER_MANAGEMENT,
            DOCKER_CONVERT, K8S_DEPLOYMENT, K8S_MANAGER, CHMOD_CALC, CRON_PARSER, HOSTS_MANAGER,
            WECHAT_SENDER, DATA_GENERATOR, QRCODE, COLOR_CONVERT, CALCULATOR, STATISTICS,
            SORT_VISUALIZER, SEARCH_ALGORITHM, HANOI, PINGAME, BPMN_DESIGNER, MERMAID, FLOWCHART,
            VIDEO_MONITOR, REMOTE_DESKTOP));

    private ToolCatalog() {
    }

    public static List<ToolCategory> categories() {
        return CATEGORIES;
    }

    public static List<ToolDescriptor> descriptors() {
        return DESCRIPTORS;
    }

    private static ToolCategory category(String id, int order) {
        return new ToolCategory(id, "group." + id, order);
    }

    private static ToolDescriptor tool(String id, String categoryId, String... keywords) {
        return new ToolDescriptor(id, categoryId, "tool." + id, Arrays.asList(keywords));
    }
}
