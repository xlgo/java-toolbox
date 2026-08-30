# java-toolbox 优化清单

> 审查时间：2026-08-30 ｜ 基线：`main` @ `cdd89cd`
> 规模：260 个 Java 文件 / 64,681 行 ｜ 53 个测试类 ｜ 10 个 feature 模块
> 路径前缀简写：`src/main/java/com/aqishi/toolbox/` → `…/`

## 进度

| 批次 | 内容 | 状态 |
|---|---|---|
| 第 1 批 | P0-1~P0-5、P1-1、P1-3、P1-6、P1-10、P1-27、B1、B6 | ✅ `515005c` |
| 第 2 批 | P2-5 monitor 分层、P2-6 ssh 分层、P2-7 打破包级环 | ✅ `e7ad260` / `b8c1a04` / `f2d9e31` |
| 第 3 批 | P2-1 K8s 模板收敛、P2-4 格式化与剪贴板收敛 | ✅ 部分完成（`a8edb67`、`4c3f8a1`） |
| 补漏 | P1-5 Pattern 缓存、P1-8 SQL 标识符校验、P1-9 HttpURLConnection disconnect | ✅ 2026-08-30 |
| 第 3 批 | P2-3 codec 面板继承（TreeFormat 骨架）、P2-4 ObjectMapper（util/Json）、P2-4 JOptionPane 收敛 | ✅ `747182b` / 本批 |
| 第 3 批 | P2-9 UI 逻辑下沉 | ⏳ 待做 |
| 第 4 批 | B7 CI 门禁、T1 data/cloud/compute 领域层测试（SimpleEval/QueryResult/SqlExecutionService/K8sResourceRef 共 20 例） | ✅ 部分完成 |
| 第 4 批 | T2 security 加密 round-trip（SymmetricUtils/RSAUtils/OtpUtils/SM3Utils 共 30 例）、T3 codec 格式化 round-trip（JsonFormatter 7 例） | ✅ 本轮完成 |
| 第 4 批 | T4 SSH 核心测试（SshSecurityUtils 凭据加密 round-trip 8 例，无需真服务端） | ✅ 本轮完成 |
| 第 5 批 | D1~D7 文档校准、I1~I2 i18n 分批 | ⏳ 待做 |

已完成项保留原文，作为问题背景与证据。

### 第 1 批落地要点

**新增类**

- `…/infra/kubernetes/KubernetesTls.java`：按连接构建 TLS 上下文（不再缓存进程级 trust-all 单例），支持集群 CA 作为唯一信任锚，缺省走系统信任库
- `…/infra/concurrency/DaemonThreads.java`：具名守护线程池 + `shutdownQuietly()`，漏关也不再阻止 JVM 退出
- `…/feature/monitor/RemoteSessionIds.java`：SecureRandom 生成 12 字符会话 ID（约 60bit）
- `…/feature/network/ssh/session/SshHostKeyPrompt.java` + `…/feature/network/ssh/ui/SshHostKeyPrompts.java`：SSH 主机指纹确认的接口与 Swing 实现

**接口调整**

- `KubernetesClient` / `KubernetesServiceFactory`：不再接收 `skipTls` 布尔量，改为接收 `SSLSocketFactory` + `HostnameVerifier`
- `KubernetesProfile` 新增 `caCertData`；`KubeconfigParser` 解析 `certificate-authority-data`，仅在明确 `insecure-skip-tls-verify` 且无 CA 时才跳过校验
- `SshSessionInstance` 新增 `(config, hostKeyPrompt)` 构造器，默认构造器等同「一律拒绝」
- `SymmetricUtils` 新增 GCM，`MODES = {GCM, CBC, ECB}`，GCM 下自动禁用 padding 选择
- `UIUtils` 新增 `confirm(parent, msg, title)`

**接入 `ManagedResourceOwner` 的面板（11 个）**

`RemoteDesktopPanel`、`CertPanel`、`WeChatPanel`、`PortScannerPanel`、`TotpPanel`、`TimePanel`、`PinGamePanel`、`HanoiPanel`、`SortPanel`、`QrCodePanel`、`Base64ImagePanel`

**构建**

- JDK 统一 17（`maven.compiler.release`），与 CI 一致
- `maven-resources-plugin` 2.6 → 3.3.1、`maven-surefire-plugin` 3.1.2 → 3.2.5（原版本在本地仓库不完整，无法构建和跑测试）
- `target/` 已 clean，回收 261MB

### 第 2 批落地要点

**monitor 分层**（19 个类，原全部平铺在 `feature/monitor` 根包）

- `domain`：DesktopChannel、DesktopMessage、IceProbeCodec、RemoteSessionIds
- `infra`：DesktopSignalClient/Server、Ice4jDirectConnector、P2PConnector、SocketChannelImpl、UdpChannelImpl、TcpDirectConnector、StunClient、NatPmpPortMapper、UpnpPortMapper
- `ui`：RemoteDesktopPanel、RemoteControlWindow、VideoMonitorPanel、FileTransferDialog、RemoteTerminalDialog、TransparentOverlayWindow

拆分暴露出 `IceProbeCodec` 是包级私有、跨包后不可见，已按「领域协议编解码」的定位提升为 public（连同 `ParsedMessage` 的取值方法）。

**ssh 分层**（18 个类，原自造 `model/session/sftp`）

- `domain`：RemoteEndpoint、SshConnectionConfig、SshTunnelConfig、SshSecurityUtils、SshHostKeyPrompt
- `infra`：SshSessionInstance、SshTunnelBridge、SshTtyConnector、PortUtils、SshConfigStore、KafkaTunnelSupport
- `ui`：SshSessionTabPanel、SshTunnelPanel、SftpPanel 等 7 个

`SftpPanel` 原本孤零零放在 `sftp` 包，现与其余界面类同处 `ui`。对外引用（DatabasePanel、KafkaPanel、RedisPanel、ZooKeeperPanel、HttpTestPanel、SshClientPanel、MainFrame 及 4 个测试）的 import 一并修正。

**打破包级环**

新增顶层 `com.aqishi.toolbox.domain`，移入 4 个被跨层共享的 Profile（Database、Redis、Kafka、Kubernetes，均为零依赖 POJO）。依赖方向由原来的环变为 `feature → infra → domain` 单向，`infra` 对 `feature` 的 import 已清零。

### 第 3 批已落地

- **P2-1 + P2-2**：K8s 九类资源加载模板收敛为 `loadResourceTable(model, path, label, rowMapper)`，抽出 `listPath`/`namespaceOf`/`nameOf`/`ageOf`/`readyCount`，427 行 → 222 行；表格回填改为批量写入数据向量后只广播一次 `fireTableDataChanged`（原逐个 `addRow` 会触发上千次事件）
- **P2-4（部分）**：新增 `util/FormatUtils`（`bytes()` 统一四份不一致的字节格式化、`duration()`）；7 处手写剪贴板改用 `UIUtils.copyToClipboard()`（`vault/SecureClipboard` 是独立的安全实现，保持不变）

### 补漏落地（2026-08-30）

- **P1-5**：`MockRuleResolver` 新增 `PATTERN_CACHE`（ConcurrentHashMap，上限 256，超限整体清空），REGEX 匹配不再逐请求重编译；非法正则不占缓存位
- **P1-8**：`DatabasePanel.switchSchema()` 改走 `safeIdentifier()` 白名单校验（`[A-Za-z_][A-Za-z0-9_$#]*`），Oracle `CURRENT_SCHEMA` 与 PostgreSQL `search_path` 拼接前先校验
- **P1-9**：`MermaidPanel` 云端渲染、`QrCodePanel` Replicate 提交与轮询共 3 处 `HttpURLConnection` 补 `finally { disconnect(); }`

### 第 3 批收尾落地（2026-08-30，`747182b` 及后续）

- **P2-3**：新增 `codec/ui/AbstractTreeFormatPanel`（美化树 + 压缩文本双视图、清空/复制/返回源工具样板），`JsonPanel`/`XmlPanel` 改为继承，抽出共享 `CodeFolderNode`/`CodeTreeCellRenderer`/`escapeHtml`。`ConvertPanel`（页签容器）与 `FormatConvertPanel`（横向分栏 + 卡片头按钮）与基类形状不契合，明确不强行继承
- **P2-4 ObjectMapper**：新增 `util/Json`（共享 `mapper()` / `prettyMapper()`，配置后线程安全），替换 25 处 `new ObjectMapper()`（vault 构造注入默认值、`YAMLFactory`/`XmlMapper` 特例保留）；直调从 30 → 5
- **P2-4 JOptionPane**：`UIUtils` 补齐 `warn/error(parent,msg,title)`、`dialog`、`input(parent,msg,title,def)` 重载（消息参数放宽为 Object）；脚本化替换 102 处直调（83 message + 12 confirm 改 boolean + 7 input），直调从 155 → 20，剩余为自定义选择对话框、OK_CANCEL 语义与常量引用（合理保留）

## 0. 一句话结论

代码整体质量**中上**（JDBC 全面 try-with-resources、ThemeManager 与 vault 包堪称样板、infra 配置持久化复用良好）。
真正的债集中在四处：**资源生命周期不统一、UI 层承载过多业务逻辑（58%）、文档中 6 处与代码脱节、3 个模块 13.5k 行零测试**。
最高性价比的两件事：收敛 9 份重复的 SwingWorker 模板（-400 行）、4 个 codec 面板继承已有基类（-200 行）。

## 1. 量化指标（技术债体检）

| 指标 | 实测值 | 评价 |
|---|---|---|
| 空 `catch {}` | 41 处 | 偏高，含密钥/连接清理路径 |
| `printStackTrace()` | 40 处 | 应统一走日志/UI 提示 |
| `new ObjectMapper()` | 33 处 | 无共享实例，K8s 面板独占 5 处 |
| `JOptionPane.*` 直接调用 | 159 处 / 20 文件 | 已有 `UIUtils` 封装却未推广 |
| 源码含中文的行 | 5,002 行 | i18n 仅 16/207 文件接入，形同虚设 |
| `target/` 体积 | 261 MB（3 份同内容 85MB jar） | 176 MB 纯冗余 |
| 最大单文件 | `K8sManagerPanel.java` 2,941 行 | 需拆分 |
| UI 层占比 | 37,673 行 / 58% | 业务逻辑下沉空间大 |

---

## 2. P0 — 安全与稳定性（建议本迭代修）

| # | 问题 | 证据 | 修复建议 |
|---|---|---|---|
| P0-1 | **K8s 全量信任 TLS，可中间人** | `…/feature/cloud/ui/K8sManagerPanel.java:2585-2589`（checkServerTrusted 空实现）、`:2136`（HostnameVerifier 恒 true）、`:2616-2622`（重抄一份） | 加载集群 CA 证书；skipTls 需二次确认并限制内网 |
| P0-2 | **远程桌面线程池永不关闭，JVM 无法退出** | `…/feature/monitor/RemoteDesktopPanel.java:544`（非守护 `ScheduledExecutor`）；`P2PConnector.java:406/410`、`DesktopSignalClient.java:248`、`Ice4jDirectConnector.java:227`、`TcpDirectConnector.java:134` 均未实现 `ManagedResourceOwner`。而 `…/ui/MainFrame.java:156` 只遍历该接口 | 上述面板实现 `ManagedResourceOwner`，在 `closeResources()` 中 `shutdownNow()` |
| P0-3 | **EDT 上 `waitFor()` 阻塞进程** | `…/feature/system/ui/HostsManagerPanel.java:63` → `:245` `proc.waitFor()`（`sudo killall` 遇密码提示会永久挂起） | 整体移入 `SwingWorker`，结果 `invokeLater` 回写 |
| P0-4 | **ECB 模式作为默认可选、无 MAC 认证** | `…/feature/security/domain/SymmetricUtils.java:95-96`；`…/feature/security/ui/SymmetricPanel.java:47` 组合框含 `{"CBC","ECB"}` | 移除 ECB/DES/3DES 选项，改 AES-GCM 并校验认证标签 |
| P0-5 | **ACME HTTP 服务线程池非守护且不停** | `…/feature/security/infra/acme/AcmeChallengeHelper.java:83`；`stopHttpServer()` 仅 `…/feature/security/ui/CertPanel.java:705` 手动点击触发 | 传命名守护线程 executor，`stop()` 后 `shutdownNow()`；`CertPanel` 实现生命周期接口 |

## 3. P1 — 高优先级

| # | 问题 | 证据 | 修复建议 |
|---|---|---|---|
| P1-1 | **远控会话 ID 用 `Math.random()` 可预测** | `…/feature/monitor/RemoteDesktopPanel.java:289` `"RD-" + (int)((Math.random()*9+1)*100000)`；`:517`、`DesktopSignalServer.java:80` 同款 | 改 `SecureRandom` 生成 ≥128bit 随机串（远控场景 ID 泄露 = 被控） |
| P1-2 | **端口扫描线程池残留** | `…/feature/network/ui/PortScannerPanel.java:321`；`:362` 仅正常结束才 `shutdown()` | 复用静态池 + `closeResources()` 兜底 |
| P1-3 | **SSH 会话核心逻辑直接弹 `JOptionPane`** | `…/feature/network/ssh/session/SshSessionInstance.java:12` 导入，` :498-500`（主机指纹确认）、`:506` 直接弹窗 | 抽 `HostKeyVerifier`/`UiPrompter` 接口由 ui 层注入（同时解锁单测，见 T4） |
| P1-4 | **`newCachedThreadPool()` 无上界** | `…/feature/monitor/TcpDirectConnector.java:134`（重连路径可反复创建） | 固定大小 + 有界队列 |
| P1-5 | **正则热路径每次重编译** | `…/feature/network/domain/callbackmock/MockRuleResolver.java:63`、`:106`（每条请求每规则编译一次） | 提为 `static final Pattern` 或加缓存 |
| P1-6 | **大文件一次性读入内存** | `…/feature/codec/ui/Base64ImagePanel.java:131` `Files.readAllBytes()`（历史 OOM 点，线程已修，内存上限未加） | 加文件大小上限校验；统一走 `SwingWorker` |
| P1-7 | **`String.format("%02x")` 在字节循环内** | `…/feature/security/domain/SymmetricUtils.java:163`；同类 `ConvertPanel.java:194`、`SM3Utils.java:118`、`CryptoPanel.java:128` | 改用查表 `HEX[b & 0xff]`，快一个数量级 |
| P1-8 | **SQL 标识符直接拼接** | `…/feature/data/ui/DatabasePanel.java:1047` `ALTER SESSION SET CURRENT_SCHEMA = " + schemaName`；`:1051` 同理 | `DatabaseMetaData` 白名单校验或 `quoteIdentifier()` |
| P1-9 | **HttpURLConnection 未 disconnect** | `…/feature/diagram/ui/MermaidPanel.java:243`、`…/feature/generation/ui/QrCodePanel.java:339/392` | `finally { conn.disconnect(); }` |
| P1-10 | **Python 子进程窗口关闭后不销毁** | `…/feature/system/ui/WeChatPanel.java:1508`；仅 `:1641` 手动停止 | 实现生命周期接口，`destroyForcibly()` |
| P1-11 | **`Runtime.exec(String)` 单字符串解析** | `…/feature/system/ui/HostsManagerPanel.java:239/241` | 改 `ProcessBuilder` 参数数组 |
| P1-12 | **BC 注册失败被静默吞掉** | `…/feature/security/domain/SymmetricUtils.java:23` `catch (Throwable ignored) {}` → SM4 全线失效且无提示 | 注册失败必须抛异常或 UI 提示 |

## 4. P2 — 结构性重构（收益大，可分期）

### 4.1 重复代码收敛（约 -600 行）

| # | 重复点 | 证据 | 建议 |
|---|---|---|---|
| P2-1 | **9 份逐字复制的 `SwingWorker<List<Object[]>,Void>` 模板** | `…/feature/cloud/ui/K8sManagerPanel.java:738/788/832/894/932/974/1016/1058/1096`（均 `setRowCount(0)` → `doInBackground` → `done()` 逐行 `addRow`） | 抽 `loadTable(model, path, rowMapper)` 泛型方法 |
| P2-2 | **表格逐行 `addRow` 触发 N 次事件** | 同上 `:772-773`（千级 Pod 即千次 fire） | 自定义 `AbstractTableModel` + `fireTableDataChanged()` |
| P2-3 | **codec 面板不继承已有基类** | `AbstractFormatPanel.java:38-91` 已实现清空/复制样板，却只有 `SqlPanel.java:20` 继承；`JsonPanel.java:62`、`XmlPanel.java:59`、`ConvertPanel`、`FormatConvertPanel.java:55` 各自手抄 | 全部继承 `AbstractFormatPanel` |
| P2-4 | **公共能力四处造轮子** | — | 见下表 |

| 能力 | 已有封装 | 重复实现 |
|---|---|---|
| 字节格式化 | 无 | 4 份：`ui/MainFrame.java:549`、`network/ui/HttpTestPanel.java:495`、`network/ssh/sftp/SftpPanel.java:440`、`monitor/FileTransferDialog.java:328` |
| 剪贴板 | `util/UIUtils.java:111`、`vault/SecureClipboard.java:82` | 10 处手写：`UrlToolPanel.java:322`、`QrCodePanel.java:511`、`UuidPanel.java:78`、`SshTunnelPanel.java:297`、`MqttClientPanel.java:269`、`PortScannerPanel.java:408`、`WebSocketClientPanel.java:191`、`ChmodPanel.java:383`、`HostsManagerPanel.java:227`、`WeChatPanel.java:1338` |
| 通知弹窗 | `util/UIUtils.java:117-130` | **159 处** `JOptionPane` 散落 20 文件 |
| ObjectMapper | 无 | **33 处** `new ObjectMapper`（K8s 面板 5 处、monitor 包 7 处） |

建议新建 `util/FormatUtils`、`util/Clipboards`、`ui/kit/Dialogs`、`util/Json`。

### 4.2 分层归位

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| P2-5 | **`monitor` 模块 19 文件 / 7041 行完全无分层** | `feature/monitor/*.java` 全部平铺根包，UI 与传输实现混杂；`catalog/ToolRegistry.java:51-52` 只能从根包导入面板 | 拆 `monitor/{domain,application,infra,ui}` |
| P2-6 | **`network/ssh` 自造分层 31 个文件** | `ssh/model/*`、`ssh/session/*`、`ssh/sftp/SftpPanel.java` | `model→domain`、`session→infra`、`sftp/SftpPanel→ui` |
| P2-7 | **`infra` 与 `feature` 双向依赖（包级环）** | `infra/database/DatabaseProfileStore.java:3`→`feature.data.domain.DatabaseProfile`；`infra/redis/RedisProfileStore.java:3`、`infra/kubernetes/KubeconfigStore.java:3`、`KubeconfigParser.java:3` 同类 | Profile 上提共享 `domain`，或 `infra` 只面向接口 |
| P2-8 | **非 ui 目录 16 处 `javax.swing` 依赖 / 11 文件** | `SshSessionInstance.java:12`、`monitor/VideoMonitorPanel.java:392-432`、`network/ssh/sftp/SftpPanel.java:252/380/404` | 抽接口由 ui 层注入 |
| P2-9 | **`application` 层形同虚设（6 文件 / 397 行）** | 仅 cloud/data/network 有；security、codec、compute、diagram、monitor、system、generation 全空。持久化关注点还分居两处：`data/application/KafkaProfileStore` vs `infra/database/DatabaseProfileStore` | 统一持久化归属 `infra`；UI 业务逻辑（K8s YAML 转换 `:2573-2907`、Kafka 管理）下沉 `application` |
| P2-10 | **`JsonFormatter` 被 3 个模块跨引用却放在 codec** | `security/ui/JwtPanel.java:3`、`network/ui/HttpTestPanel.java:3`、`network/ui/CallbackTestPanel.java:3` | 上提至 `util/` |

> 正面：`feature/**` 无任何 `import ...catalog`，**不存在 catalog 环**；`catalog` 作为唯一装配枢纽是合理设计。

## 5. 构建与工程化

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| B1 | **Java 8 与 CI JDK 17 冲突** | `pom.xml:16-17`（source/target 8）、`:278-279`（重复声明 1.8）vs `.github/workflows/release.yml:33`（JDK 17）。README:3 又是第三种口径 | 统一为 17，用 `maven.compiler.release` |
| B2 | **无 `dependencyManagement`，20 处版本散落** | `bcprov/bcpkix` 1.70（`:63/:68`）、jackson 2.15.2（`:93/:98/:104`） | 抽 `<dependencyManagement>` + 版本属性 |
| B3 | **git 散列动态版本** | `pom.xml:123` `ice4j 3.2-10-gfcadc70` | 换官方稳定版或注释锁版理由 |
| B4 | **大体积依赖无 scope 标注** | `pom.xml:154` ojdbc8 19.18，源码 0 处 import，仅 `infra/database/DatabaseConnectionFactory.java:35` 反射 | 改 `<scope>runtime</scope>` |
| B5 | **无依赖体检** | pom 无 `maven-dependency-plugin:analyze` | 加入并 `failOnWarning` |
| B6 | **`target/` 261MB，176MB 冗余** | 3 份同内容 85MB jar：`java-toolbox.jar`、`original-java-toolbox.jar`、`java-toolbox-1.8.1-shaded.jar`（后者为陈旧产物，当前 `finalName=java-toolbox`，pom:211） | `mvn clean`；shade 加 `<shadedArtifactAttached>` |
| B7 | **CI 无门禁** | 仅 `release.yml`（`tags: v*` + `workflow_dispatch`），缺 push/PR 触发校验；GUI 测试依赖 `xvfb-run`（`:37`）脆弱 | 新增 `.github/workflows/ci.yml`（push/PR 触发，headless 分组） |
| B8 | **无本地 jpackage 配置** | 仅 CI 有打包 | 加 `jpackage` profile 便于本地验证 |
| ✅ | 良好项 | `pom.xml:18` 已配 `sourceEncoding=UTF-8`、`:280` `-encoding UTF-8`；`run.bat:3` `chcp 65001` + `-Dfile.encoding=UTF-8`；`:291` surefire 隔离 configRoot | 保持 |

## 6. 测试覆盖

| # | 缺口 | 证据 | 建议 |
|---|---|---|---|
| T1 | **3 个模块零测试，13,513 行无覆盖** | `feature/data`（6,618）、`feature/cloud`（4,623）、`feature/compute`（2,272） | 优先补 domain 层纯函数 |
| T2 | **security 加密覆盖不全** | `security/domain/` 7 类仅 4 个测试；**缺 `RSAUtils`、`SymmetricUtils`（AES/DES/3DES）、`CertUtils`**；`infra/acme/`（`AcmeClient`、`CloudflareDnsProvider`、`AcmeChallengeHelper`）**全部无测试** | 补齐，尤其加解密 round-trip |
| T3 | **codec 几乎裸奔** | `codec/domain/JsonFormatter.java` 被 3 模块跨引用**却无测试**；`XmlFormatter`/`SqlFormatter`/`TextDiff`/`FormatConvert` 亦无（仅 `UrlToolPanelTest` 1 个） | 补格式化 round-trip 测试 |
| T4 | **SSH 核心不可测** | `feature/network/` 9,350 行，16 个测试全在外围；`SshSessionInstance` 因 P1-3 的 `JOptionPane` 阻塞无法单测 | 先修 P1-3，再补测试 |

## 7. 文档一致性（与代码脱节 7 处）

| # | 不一致 | 证据 | 建议 |
|---|---|---|---|
| D1 | **工具数 49 vs 50** | 代码有文档未写：`port.scanner`（`ToolRegistry.java:105`）、`zookeeper.management`（`:110`）；README:49-50 的「UUID 生成」「密码生成器」实为 `generation/ui/DataGeneratorPanel.java:33-34` 两个内嵌 tab | 校准功能表 |
| D2 | **README:67「内置 54 套现代主题」无代码依据** | `ui/ThemeManager.java:46` 由 `FlatAllIJThemes` **动态**构建（:57、:74），数量随 FlatLaf 版本漂移；README:138 又锁定 3.5.4 | 改为「跟随 FlatLaf 版本」或由测试断言实际数量 |
| D3 | **README:146-184 项目结构图遗漏** | 漏 `infra/config/`（含被 4 个 Store 依赖的 `JsonPreferencesStore`）、`infra/InfrastructureException.java`、`infra/ManagedResource*.java`、`catalog/ToolCategory.java` | 补全结构图 |
| D4 | **DESIGN.md:9 与实际不符** | 称「所有工具面板都基于 `ui/kit` 装配」，但 `monitor` 6 个类、`ssh/sftp`、`ui/MainFrame.java` 仍裸写 Swing；`ui/kit` 8 个组件仅被部分面板采用 | 改为「新面板统一基于 ui/kit，历史面板迁移中」 |
| D5 | **README:30「无依赖 JSON 美化」与 pom 矛盾** | `pom.xml:90` 已引 `jackson-databind`，且 `codec/ui/JsonPanel.java:196/348` 同时用了 Jackson 与自研 `JsonFormatter`；README:142 同样失效 | 改口径 |
| D6 | **13 篇文档成为孤岛** | `docs/superpowers/` 下 13 个 plan/spec md（如 `2026-08-16-domain-package-migration.md`）未被 README 或任何索引引用；根目录 `.superpowers/` 为空目录 | 新增 `docs/INDEX.md` |
| D7 | **JDK 版本三重口径** | README:3「JDK 8+，ICE 需 11+」/ `pom.xml:16-17` target 8 / CI `release.yml:33` JDK 17 | 统一（见 B1） |

> 补充：`docs/` 实际 19 个 md（4 顶层 + 13 superpowers + 2 plan/checkpoint），非 20 个；已失效路径：无。

## 8. 国际化

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| I1 | **i18n 形同虚设** | 资源文件 3 份 × 290 行（`resources/com/aqishi/toolbox/util/messages*.properties`），但仅 **16/207 文件**接入 `I18n.get(`（163 处）；源码含中文 **5,002 行** | 按模块分批抽取，先做 `security`/`monitor` |
| I2 | **中英混用** | `monitor/RemoteDesktopPanel.java:353` 硬编码英文 `"Please select target ID"` 与中文混排 | 一并纳入 i18n |
| ✅ | **主题切换质量良好** | `ui/ThemeManager.java` 为唯一入口，`FlatLaf.setup` 仅 :99/:125，`FlatAnimatedLafChange` 仅 :114/:139，:126 正确处理 defaults 覆盖顺序 | 作为其他重构的样板 |

---

## 9. 建议修复顺序

| 批次 | 内容 | 预期收益 |
|---|---|---|
| **第 1 批（本迭代）** | P0-1~P0-5、P1-1、P1-3、B1、B6 | 安全与稳定性止血，回收 176MB，统一 JDK 口径 |
| **第 2 批** | P2-5/P2-6（monitor + ssh 分层归位）、P2-7（打破包级环） | 架构归位，50 个文件各就各位 |
| **第 3 批** | P2-1~P2-4（重复收敛约 -600 行）、P2-9（UI 逻辑下沉） | 减重、消除风格漂移 |
| **第 4 批** | T1~T4（测试补齐）、B7（CI 门禁） | 回归保护网 |
| **第 5 批** | D1~D7（文档校准）、I1~I2（i18n 分批） | 文档与代码对齐 |

## 10. 已验证无问题的部分

- `DatabasePanel` JDBC 全面 try-with-resources（14 处）
- `RedisPanel` 用 `redisLock` 保护 Jedis 连接，`checkConnection()` 正确自动重连（`:666-701`）
- `SshTunnelBridge.shutdown()` 完整释放桥接与会话（`:89-104`）
- `vault` 包质量最高：SecureRandom + 守护线程 + 显式 shutdown
- `Base64ImagePanel` 历史 OOM/EDT 卡死 **已修复**（`:122`/`:193` 后台线程 + `:136`/`:198` `invokeLater` 回写）
- `infra/config/JsonPreferencesStore` 被 4 个 Store 一致复用（唯一例外：`network/ssh/model/SshConfigStore.java:94-97` 自行 `Files.write`，应统一）
- `ui/ThemeManager` 主题切换实现统一
- 无 `feature/** → catalog` 反向依赖
