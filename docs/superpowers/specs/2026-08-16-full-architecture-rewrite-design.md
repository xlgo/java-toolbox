# Java Toolbox 全量架构重写设计

## 背景

Java Toolbox 当前有 50 个工具入口。工具注册集中在 `MainFrame#createTools()`，工具的稳定 ID、导航分类、搜索关键词和构造器则分散在各个 `ToolPanel` 子类中。源码包中有 33 个面板位于 `misc`，`dev` 导航组包含 19 个职责差异很大的工具，导致导航分类、源码目录和实际领域边界不一致。

项目还存在以下整理问题：

- `pom.xml` 的项目描述和多处依赖注释存在中文乱码。
- 三份语言资源文件带 UTF-8 BOM，首个资源键存在被读成 `\uFEFFapp.title` 的风险。
- `README.md` 的运行、编译、技术栈和项目结构内容重复，且遗漏近期新增入口。
- `K8sManagerPanel`、`KafkaPanel` 和 `DatabasePanel` 同时承担界面、网络、配置、持久化和业务编排职责。
- `FlowchartPanel`、`CanvasPanel`、流程图模型和 DTO 的边界不清晰。
- `tool.pingame` 曾只存在于英文资源，默认及中文资源会显示原始键名；本设计要求保留该修复。

本次工作采用全量架构重写，但用户明确要求不进行功能测试。因此设计重点是保持兼容契约、建立清晰的迁移边界，并使用编译和静态一致性检查作为本轮验收证据。

## 目标

1. 建立按领域组织的包结构，消除 `misc` 兜底包。
2. 用统一的工具目录和注册表管理 50 个入口。
3. 将 UI、应用编排、领域逻辑和外部系统适配分层。
4. 重新评估并落实所有工具的导航分类。
5. 完善核心类、接口、状态机和数据模型的中文注释。
6. 修正乱码、BOM、行尾和文档重复问题。
7. 输出一份可执行的新增功能路线图。
8. 保持已有用户数据和运行契约不变。

## 非目标

- 不删除任何现有工具能力或稳定工具入口。
- 不修改保险库文件格式、配置键、连接配置格式或迁移约定。
- 不在本轮重写业务算法或改变用户可观察的交互行为。
- 不连接真实数据库、集群、微信、SSH、远程桌面或其它外部服务。
- 不运行功能测试；只做编译和静态一致性核查。
- 不在新架构稳定前实现 P1/P2 路线图功能。

## 兼容契约

以下项目是硬约束：

- 50 个稳定工具 ID 保持不变，例如 `hash.codec`、`k8s.manager` 和 `pingame`。
- 配置键、保险库文件格式、SSH/K8s/Kafka/数据库连接配置格式保持不变。
- 所有现有 `tool.*` 和领域内 `remote_desktop.*` / `vault.*` 国际化键保持不变；旧 `group.*` 键保留为兼容别名，同时新增目标分类键供新导航使用。
- 导航分组 ID 可以从旧值迁移到新值，但必须在 `ToolNavigationState` 中提供一次性兼容映射；旧分组 ID 和旧 `group.*` 键在迁移窗口内继续可解析。
- 用户已保存的当前工具、展开分组、侧栏宽度和窗口状态不得因重构而失效。
- 对外可见的工具标签和功能入口保持等价；只有分类名称和包路径发生结构性调整。

## 目标架构

```text
com.aqishi.toolbox
├── app/                         启动、窗口生命周期、关闭协调
├── catalog/                     工具描述、分类、注册表、构造上下文
├── ui/                          主窗口、导航、共享组件和主题
├── feature/
│   ├── security/                安全与身份
│   ├── codec/                   编码、转换与文本
│   ├── network/                 网络与接口
│   ├── data/                    数据库与中间件
│   ├── cloud/                   云原生与运维
│   ├── system/                  系统与自动化
│   ├── generation/              生成与设计
│   ├── compute/                 计算与算法
│   ├── diagram/                 图表与建模
│   └── monitor/                 监控与远程协作
├── infra/                       网络、存储、协议和外部系统适配
└── support/                     配置、国际化、日志和通用基础设施
```

### 依赖方向

```text
ui → feature application → feature domain
                         ↘ infra adapters
catalog → feature descriptors
support ← all layers
```

UI 层不得直接创建数据库、Kafka、K8s、SSH 或远程桌面客户端。外部连接、文件读写和配置持久化由 `infra` 实现，应用服务负责编排，领域对象保持无 Swing 依赖。

### 工具注册表

新增 `ToolCategory`、`ToolDescriptor`、`ToolRegistry` 和 `ToolboxContext`：

- `ToolDescriptor` 保存稳定 ID、分类 ID、本地化键、搜索关键词、构造器和能力标记。
- `ToolRegistry` 维护唯一 ID、注册顺序、分类顺序和按 ID 查找索引。
- `ToolboxContext` 注入保险库、剪贴板、配置和共享基础设施，解决敏感工具构造器差异。
- `MainFrame` 只依赖注册表，不再维护 50 个面板的 import 和 Supplier 数组。
- 语言切换只刷新描述和视图文本，不改变稳定 ID 或已挂载状态。

## 目标功能分类

| 新分类 ID | 中文名称 | English | 工具入口 |
|---|---|---|---|
| `security` | 安全与身份 | Security & Identity | 摘要、对称加密、非对称加密、证书、账号密码、TOTP、JWT |
| `codec` | 编码、转换与文本 | Encoding, Conversion & Text | 进制、时间戳、Base64 图片、URL、格式转换、JSON、XML、SQL、字符串、正则、文本对比 |
| `network` | 网络与接口 | Network & API | HTTP、回调 Mock、WebSocket、MQTT、子网、端口扫描、SSH |
| `data` | 数据库与中间件 | Databases & Middleware | 数据库、Redis、Kafka、ZooKeeper |
| `cloud` | 云原生与运维 | Cloud Native & Operations | Docker Compose、K8s 部署生成、K8s 集群管理 |
| `system` | 系统与自动化 | System & Automation | Chmod、Cron、Hosts、微信工具 |
| `generation` | 生成与设计 | Generation & Design | 随机测试数据、二维码、颜色转换 |
| `compute` | 计算与算法 | Computing & Algorithms | 计算器、统计、排序、查找、汉诺塔、见缝插针 |
| `diagram` | 图表与建模 | Diagrams & Modeling | BPMN、Mermaid、流程图/时序图 |
| `monitor` | 监控与远程协作 | Monitoring & Remote Collaboration | 视频监控、远程桌面 |

特殊归属说明：

- JWT 归入安全与身份，因为它涉及令牌、签名和密钥，而不仅是文本编解码。
- Cron 归入系统与自动化，因为其核心用途是调度表达式和执行计划。
- `CanvasPanel`、`FlowNode`、`FlowEdge`、`FlowchartDataDto` 随流程图功能迁入 `feature.diagram`，不作为独立入口。
- 微信导出脚本作为系统自动化基础设施，与 `WeChatPanel` 共用文档，但不新增导航入口。

## 分层拆分规则

每个领域按需要使用以下层次：

- `ui`：收集输入、绑定事件、显示状态和结果，不持有协议细节。
- `application`：编排用例、校验输入、管理异步任务和取消。
- `domain`：纯数据模型、解析器、计算器和状态转换。
- `infra`：HTTP、JDBC、JSch、Kafka、Kubernetes、文件和系统 API 适配。

### 大型面板迁移

- `K8sManagerPanel` 拆为集群配置/ProfileStore、KubernetesClient、资源查询服务、Exec/文件传输服务和视图层。
- `KafkaPanel` 拆为连接/隧道适配、主题与消费组服务、消息查询服务、订阅状态模型和视图层。
- `DatabasePanel` 拆为连接工厂、元数据服务、SQL 执行服务、ProfileStore 和结果模型/视图。
- `FlowchartPanel` 拆为画布视图、拓扑编辑服务、历史快照模型、导出器和节点/边模型。
- SSH、Redis、远程桌面和微信工具沿用同样边界，先抽出资源生命周期和外部连接，再迁移视图。

## 注释与编码规范

- 新增或迁移的公共类、接口、枚举和关键方法必须有中文 Javadoc，说明职责、输入输出、线程模型、资源生命周期和异常语义。
- 状态机必须列出合法状态迁移；数据模型必须说明可变字段、坐标单位、序列化约束和兼容字段。
- UI 类注释说明 EDT 要求；异步服务注释说明回调线程、取消和关闭行为。
- `pom.xml` 中乱码注释和项目描述全部改为有效 UTF-8 中文。
- 三份 `messages*.properties` 统一为 UTF-8 无 BOM，并统一行尾。
- `tool.pingame` 在默认、中文和英文资源中均必须存在。
- 删除重复注释、无效兼容分支和重复脚本源；微信导出脚本保留一个权威源。

## 迁移阶段

1. **契约与目录基线**：建立 50 个入口清单、旧分类映射和资源键清单。
2. **核心目录层**：实现 `catalog`、`ToolRegistry`、`ToolboxContext` 和分类迁移。
3. **领域迁移**：按 `security → codec → network → data → cloud → system → generation → compute → diagram → monitor` 迁移面板和模型。
4. **基础设施抽取**：拆分 K8s、Kafka、数据库、SSH、Redis、远程桌面和微信连接/持久化职责。
5. **旧包清理**：删除 `misc` 中已迁出的类、修正全部导入、清理死代码和重复资源。
6. **文档与路线图**：重写 README、补充架构说明、分类矩阵、迁移记录和新增功能清单。

每个阶段都必须保持主源码可编译；不在阶段中顺便改变业务行为或新增路线图功能。

## 新增功能路线图

### P0

- OpenAPI / Swagger 工作台：导入定义、参数编辑、鉴权、请求和示例管理。
- JSONPath / JMESPath 查询：补强 JSON 工具的结构化筛选。
- YAML / TOML / INI 转换与校验：扩展格式转换器。
- 文件批量摘要与签名校验：支持 MD5、SHA、SM3、SHA-3 和完整性验证。
- CSR / PKCS#12 / 证书链检查：完善证书和 ACME 工作流。
- DNS / TLS / HTTP 诊断：统一解析、握手、证书和响应耗时检查。
- 日志查看与过滤器：大文件、实时追踪、正则过滤和高亮。
- 工作区导入导出：保存工具配置、请求模板和常用输入。

### P1

- GraphQL 客户端。
- gRPC 客户端。
- OAuth2 / OIDC / JWK 工具。
- Git 仓库辅助工具。
- Docker / K8s Manifest Diff 与 Port Forward。
- XPath / XSLT 工具。
- Webhook 签名验证器。
- Cron 可视化编辑和冲突检测。

### P2

- 插件 SDK 与插件目录。
- 自动化脚本/宏执行器。
- OpenTelemetry Trace 查看器。
- 系统密钥链集成。
- 配置版本历史与差异恢复。
- 多窗口/标签页工作区。

P0 在新架构完成后优先评估；P1 需要基于 P0 的共享模型；P2 等插件和工作区边界稳定后再排期。

## 静态验收

本轮不运行功能测试，使用以下检查：

```bash
mvn -Dmaven.test.skip=true package
```

另外执行：

- 工具 ID、分类 ID、构造器和本地化键一致性扫描。
- 新旧包路径 import 扫描，确认没有悬挂引用。
- 三份资源键集合、BOM、乱码和行尾扫描。
- README 的入口、目录和发布示例与仓库实际内容对照。
- `git diff --check` 检查空白和冲突标记。

验收报告必须列出未执行功能测试这一限制，以及无法通过静态检查证明的外部服务行为。
