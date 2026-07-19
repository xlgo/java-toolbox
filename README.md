# Java 工具箱

> 桌面端多功能工具箱，基于 Java Swing + FlatLaf 外观包。基础工具使用 JDK 8+；远程桌面的完整 ICE 直连功能要求 JDK 11+。双击 `run.bat` 或 `java -jar target/java-toolbox.jar` 启动。

<p align="center">
  <img src="screenshots/overview.png" alt="Java 工具箱概览" width="750"/>
</p>

## 功能一览

| 分组 | 工具 | 说明 |
|------|------|------|
| 加密 | 摘要与编解码 | MD5 / SHA-1 / SHA-256 / SM3 摘要与 Base64 编解码 |
| 加密 | 对称加密 | AES / DES / 3DES / SM4（支持 ECB/CBC 模式、PKCS5 填充、防乱码文本密钥生成） |
| 加密 | 非对称加密 | RSA / SM2（支持密钥对生成、公钥加密/私钥解密、私钥签名/公钥验签） |
| 转换 | 进制与编码 | 二/八/十/十六进制互转（二进制 4 位自动分组美化），UTF-8/GBK/URL 编码 |
| 转换 | 时间戳转换 | 秒/毫秒、自定义格式、时区 |
| 转换 | Base64 图片转换 | 图片文件与 Base64 字符串互转，支持比例自适应预览与本地保存 |
| 转换 | 格式转换 | JSON / XML / YAML / CSV / Properties 之间任意双向互相转换，完美支持嵌套与点分扁平化还原 |
| 算法 | 排序可视化 | 冒泡/选择/插入/快排/归并，逐帧动画 + 统计 |
| 算法 | 查找算法 | 二分查找（区间收缩过程）+ 线性查找 |
| 算法 | 汉诺塔 | 汉诺塔交互演示，支持手动拖盘与自动动画播放 |
| 计算 | 科学计算器 | 表达式求值（Nashorn 优先，回退自实现双栈求值器） |
| 计算 | 统计计算 | 均值/中位数/方差/标准差/极差 |
| 格式化 | JSON 格式化 | 无依赖 JSON 美化/压缩/树形折叠预览，支持彩虹括号语法高亮 |
| 格式化 | XML 格式化 | 无依赖 XML 美化/压缩/树形折叠预览，支持标签属性语法着色与语法错误校验 |
| 格式化 | SQL 格式化 | 对常见 SQL 关键字大写美化、换行与缩进优化 |
| 开发工具 | 正则测试 | 实时匹配高亮、分组捕获、匹配计数 |
| 开发工具 | JWT 编解码 | 支持 Header/Payload 实时解析与过期状态提示，支持 HS256 签名生成与 Token 构造 |
| 开发工具 | Cron 表达式解析 | 校验 Cron 表达式，并计算未来 5 次的预计执行时间 |
| 开发工具 | 文本对比 | 纯 Java 计算两端文本差异，并以彩色高亮显示结果（标记新增与删除行） |
| 开发工具 | Docker 转换 | 将 `docker run` 运行命令解析并一键转换为 `docker-compose` YAML 声明配置 |
| 开发工具 | 子网计算器 | 输入 IP/CIDR（如 `192.168.1.1/24`）计算网络地址、广播地址、掩码并展示二进制 |
| 开发工具 | HTTP 接口测试 | 轻量 HTTP 客户端，支持 GET / POST / PUT / DELETE，自定义请求头、Body 与 Content-Type |
| 开发工具 | 回调 Mock | 启动临时 HTTP 服务器接收回调请求，自定义响应状态码与内容，实时回显请求详情 |
| 开发工具 | 颜色转换 | HEX / RGB / HSL 互转，集成 **JColorChooser 调色板** 与 **一键复制** |
| 开发工具 | 证书管理 | X.509 证书管理：创建根证书 (Root CA)、CA 签发子证书（支持 DNS/IP/EMAIL/URI 多类型 SAN）、证书解析报告，支持一键下载 PEM 文件 |
| 开发工具 | K8s 部署生成 | Kubernetes 资源 YAML 生成器，支持 Deployment / Service / Ingress（含 TLS）/ ConfigMap 实时预览与导出 |
| 开发工具 | Redis 管理 | Redis 连接管理与键浏览器（列表/树形双视图），支持值编辑与命令控制台操作 |
| 开发工具 | 流程图与时序图设计 | 现代化流程图与时序图设计器，支持流程节点、生命线、激活条。支持多选拖动、节点修改文字、双击编辑、高阶撤销/重做（Ctrl+Z / Ctrl+Y）、中键/空白处拖拽画布滚动与 Ctrl+滚轮丝滑缩放，以及鼠标悬停节点控制点直接拖拽出连线吸附的极速连线交互，支持 PNG 导出 |
| 生成 | UUID 生成 | 批量生成、去横线、大写、一键复制 |
| 生成 | 密码生成器 | 基于 SecureRandom 的离线强密码生成与实时强度评估 |
| 监控 | 视频监控 | 视频监控面板，支持多路画面分屏布局、格子合并拆分与设备树管理 |
| 监控 | 远程桌面 | 纯 P2P 远程控制桌面，优先采用 ice4j 完整 ICE 状态机进行 UDP 双向打洞（多 STUN、triggered check、peer-reflexive candidate、角色冲突处理与候选对提名），失败后尝试带 UPnP/NAT-PMP 的 TCP 直连；不配置 TURN，信令服务器不转发桌面数据 |
| 运维 | Kafka 管理 | 管理 Kafka 集群，浏览主题与消费组，查看 Lag 详情，消息拉取/发布，以及**实时查看主题的订阅消费组与活跃成员分区分配** |
| 运维 | K8s 集群管理 | 多集群管理，Kubeconfig 导入与 Namespace 切换，浏览 Pod/Deployment/Service/ConfigMap/Node，日志追踪，Exec 容器终端，以及**容器文件上传与下载** |
| 其它 | 微信群发与通讯录 | 微信群发，以及自适应读取微信 SQLite 通讯录（展示昵称、备注、微信号、头像，支持 Excel 导出、一键追加群发、头像批量下载） |

## 主题与体验优化

- **主题系统**：基于 FlatLaf 外观包，内置 54 套现代主题实时切换（带平滑过渡动画），如 Material、GitHub Dark、Solarized、One Dark 等。
- **排版与渲染**：输入输出区域字体统一采用 **微软雅黑 (Microsoft YaHei)**，不仅在英文字符下完美避免了连字现象（如等号正常分立），同时解决了中文字符显示为问号或乱码的渲染痛点。
- **主题高度自适应**：全局优化所有主题下的组件高度表现，按钮、输入框、下拉选择框等高度均自适应且全局最低保持 32 像素，防止元素在某些精简主题下显得局促或被裁剪。
- **流程图与时序图高阶画布交互**：
  - **撤销与重做**：配备完整历史快照栈，支持无限制的 `Ctrl+Z` 撤销与 `Ctrl+Y` 重做，保证编辑流程的安全。
  - **缩放与滚动**：支持 `Ctrl+鼠标滚轮` 的丝滑无缝画布缩放，支持鼠标中键按住或拖动空白处进行无限制的丝滑视口滚动，免除传统滚动条卡顿局限。
  - **极速连线**：无需切换连线工具，鼠标滑过任何图形时其四周将自动高亮 4/8 个连接锚点（Port），用户在小圆圈上直接按住并拖拽便能一气呵成地拖出新连线并实现智能吸附。
  - **几何边缘吸附**：针对椭圆、菱形、平行四边形、六边形等异形图形，引入了外轮廓边界射线投影算法。不管是从中点还是从顶角引出/指向的连线，端点都会自动贴合在其物理图形的真实几何边缘上，彻底杜绝了连线在空中悬空不挨着图形的视觉硬伤。
  - **文字即时编辑**：支持选中节点直接在属性面板进行文字修改，或双击节点直接触发编辑。

## 界面布局

```
┌──────────────────────────────────────────────────────┐
│  Java 工具箱  · 加密 / 转换 / 算法 / 计算 / 格式化 / 开发工具 / 生成 / 监控  │
├──────────────────────────────────────────────────────┤
│ [加密][转换][算法][计算][格式化][开发工具][生成][监控] │
├──────────┬───────────────────────────────────────────┤
│ 工具列表  │                                           │
│ • 工具A  │           内容区（CardLayout）              │
│ • 工具B  │                                           │
│ • 工具C  │                                           │
└──────────┴───────────────────────────────────────────┘
```

## 运行

```bash
# 方式一：双击或直接执行
run.bat

# 方式二：命令行启动（指定最大堆内存和文件编码）
java -Xmx512m -Dfile.encoding=UTF-8 -jar .\target\java-toolbox.jar
```

## 编译

```bash
mvn clean package -DskipTests
# 产物：target/java-toolbox.jar
```

## 开发

```bash
# 推送代码（本地已缓存 GitHub 凭据）
git push
```

基于 GitHub MCP 进行开发，提交后直接 `git push` 即可推送代码到远程。

## 技术栈

- Java Swing（GUI）
- FlatLaf 3.5.4（外观包 + IntelliJ 主题包）
- BouncyCastle 1.70（国密 SM2/SM3/SM4 算法支持，提供与经典加解密的统一调用）
- ice4j 3.2（远程桌面完整 ICE/STUN UDP 直连；未启用 TURN）
- Maven Shade（打 fat jar）
- 纯 JDK 实现：JSON 美化、中缀表达式求值、标准 AES/DES/3DES/RSA 加解密

## 项目结构

```
src/main/java/com/aqishi/toolbox/
├── Main.java              # 启动入口
├── ui/
│   ├── MainFrame.java     # 主窗口：8 大页签 + 搜索 + 主题
│   ├── ToolPanel.java     # 工具面板抽象基类（含搜索关键词匹配）
│   └── ThemeManager.java  # FlatLaf 主题管理（54 套主题）
├── crypto/                # 加密
│   ├── CryptoPanel.java   # 摘要与编解码
│   ├── SymmetricPanel.java# 对称加密
│   ├── AsymmetricPanel.java# 非对称加密
│   ├── CertUtils.java     # 证书工具（X.509 PEM 解析、根证书创建、签发）
│   └── RSAUtils/SM2Utils/SM3Utils/SM4Utils/SymmetricUtils.java
├── convert/               # 转换
│   ├── ConvertPanel.java  # 进制与编码
│   ├── TimePanel.java     # 时间戳
│   ├── Base64ImagePanel.java # Base64 图片
│   └── FormatConvertPanel.java # 格式互转
├── algo/                  # 算法
│   ├── SortPanel.java     # 排序可视化
│   ├── SearchPanel.java   # 查找算法
│   └── HanoiPanel.java    # 汉诺塔
├── calc/                  # 计算
│   ├── CalculatorPanel.java # 科学计算器
│   └── StatisticsPanel.java # 统计计算
├── misc/                  # 格式化 / 开发工具 / 生成
│   ├── JsonPanel.java     # JSON 格式化
│   ├── JsonFormatter.java # JSON 格式化状态机
│   ├── XmlPanel.java      # XML 格式化
│   ├── SqlPanel.java      # SQL 格式化
│   ├── RegexPanel.java    # 正则测试
│   ├── JwtPanel.java      # JWT 编解码
│   ├── CronPanel.java     # Cron 表达式
│   ├── TextDiffPanel.java # 文本对比
│   ├── DockerComposePanel.java # Docker 转换
│   ├── SubnetPanel.java   # 子网计算器
│   ├── HttpTestPanel.java # HTTP 接口测试
│   ├── CallbackTestPanel.java # 回调 Mock
│   ├── ColorPanel.java    # 颜色转换
│   ├── CertPanel.java     # 证书管理
│   ├── K8sPanel.java      # K8s 部署生成
│   ├── K8sManagerPanel.java # K8s 集群管理（终端、日志、文件上传下载）
│   ├── KafkaPanel.java    # Kafka 管理（含主题订阅者视图）
│   ├── RedisPanel.java    # Redis 管理
│   ├── FlowchartPanel.java # 流程图与时序图设计（多功能拓扑、撤销重做、极速连线）
│   ├── UuidPanel.java     # UUID 生成
│   ├── PasswordPanel.java # 密码生成器
│   ├── WeChatPanel.java   # 微信工具（群发与通讯录读取）
│   └── WeChatContactReader.java # 微信数据库解析工具
├── monitor/               # 监控
│   ├── VideoMonitorPanel.java # 视频监控
│   ├── RemoteDesktopPanel.java # 远程桌面（控制端/被控端面板）
│   ├── Ice4jDirectConnector.java # 完整 ICE UDP 直连协商器（远程桌面当前使用）
│   ├── P2PConnector.java       # 旧版轻量 UDP 打洞实现（兼容与测试保留）
│   ├── TcpDirectConnector.java # UDP 失败后的 TCP 直连协商器
│   ├── StunClient.java         # STUN 客户端，获取公网反射 IP 与映射端口
│   ├── UpnpPortMapper.java     # UPnP IGD 公网端口映射与双重 NAT 校验
│   ├── NatPmpPortMapper.java   # NAT-PMP 公网端口映射
│   ├── UdpChannelImpl.java     # 基于 UDP 协议的 P2P 通信通道
│   ├── DesktopSignalClient.java# 信令服务器客户端
│   └── DesktopSignalServer.java# 局域网/公网多端同组信令中转服务器
└── util/UIUtils.java      # UI 辅助
```

## License

MIT
