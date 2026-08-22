# 回调 Mock 多规则响应设计

## 背景

当前 `CallbackTestPanel` 直接在 `HttpHandler` 中读取三个 Swing 输入控件，并将同一套状态码、Content-Type 和响应体返回给所有请求。请求方法、路径、查询字符串、请求头和请求体只用于界面回显，不能影响响应。该结构还让后台 HTTP 线程直接访问 Swing 组件，无法安全地扩展为多规则配置。

本设计将回调 Mock 拆为独立的规则模型、请求解析、规则匹配、模板渲染、文件持久化和 Swing 编辑界面。它保留现有工具 ID、服务开关和请求历史能力，同时允许不同请求返回不同报文。

## 目标

1. 配置任意数量、可排序、可启停的响应规则，并以“从上到下，首条命中优先”决定响应。
2. 支持按 HTTP 方法、路径和请求参数匹配；参数来源覆盖 URL 查询、请求头、表单和 JSON 请求体。
3. 支持路径精确、前缀和正则匹配，以及参数等于、存在、包含和正则匹配。
4. 支持在响应体中引用请求值，例如 `${query.orderId}`、`${header.X-Request-Id}`、`${form.status}` 和 `${json.order.id}`。
5. 无规则命中时使用独立的默认响应。
6. 将规则和默认响应持久化到用户配置目录，在应用重启后恢复，且可安全保存较长响应报文。
7. 保证 HTTP 请求线程只读取不可变规则快照，不访问 Swing 组件；关闭主窗口时停止 Mock 服务。
8. 使用自动化测试覆盖匹配、模板、持久化、异常路径和本地 HTTP 集成行为。

## 非目标

- 不引入 WireMock、脚本执行引擎、外部模板引擎或新的第三方依赖。
- 不支持按客户端 IP、TLS、Cookie、二进制上传内容或 WebSocket 消息匹配。
- 不支持自定义响应头集合、延迟响应、随机响应、请求转发或代理录制。
- 不递归展开模板变量，不执行表达式，也不将规则条件的期望值作为模板。
- 不修改其它网络工具、稳定工具 ID、主框架导航或全局语言刷新机制。

## 组件边界

新增实现遵循“领域逻辑不依赖 Swing 或 `HttpExchange`”的边界。

| 层 | 位置 | 职责 |
| --- | --- | --- |
| 领域 | `feature.network.domain.callbackmock` | 不可变规则、请求、响应、条件、匹配器和模板渲染器。纯 Java，可直接单测。 |
| 应用 | `feature.network.application` | 协调规则快照、Mock 服务生命周期、请求记录和规则替换。 |
| 基础设施 | `feature.network.infra` | 将 `HttpExchange` 解析为领域请求；基于 `ApplicationPaths` 和 `AtomicFiles` 的 JSON 规则仓库。 |
| 界面 | `feature.network.ui` | 规则表、独立编辑对话框、默认响应编辑、请求历史显示和用户错误提示。 |

核心对象如下：

- `MockRuleSet`：版本、按优先级排列的规则列表和默认响应。
- `MockRule`：稳定 UUID、名称、启用状态、方法条件、路径条件、参数条件列表和响应定义。
- `MockRequest`：标准化后的请求方法、路径、`query`、`header`、`form`、`json` 命名空间与原始请求体摘要。
- `MockResponse`：状态码、Content-Type 和响应体模板。
- `MockRuleResolver`：从不可变 `MockRuleSet` 中返回第一条命中规则，或默认响应。
- `MockTemplateRenderer`：在已选择的响应体模板中做一次变量替换。
- `CallbackMockService`：持有当前不可变规则快照，控制 `HttpServer` 生命周期，并将请求记录发布给 UI。
- `CallbackMockRuleRepository`：加载和原子保存版本化 JSON 文件。

`CallbackTestPanel` 只负责编辑 `MockRuleSet`、调用 `CallbackMockService` 和在 EDT 更新列表。它不再包含 HTTP 匹配或模板逻辑。

## 规则匹配语义

### 优先级和默认响应

1. 规则按界面顺序从上到下处理。
2. 禁用规则永远跳过。
3. 第一条满足所有已填写条件的规则获胜。
4. 没有规则命中时使用 `MockRuleSet.fallbackResponse`。
5. 默认响应不是普通规则，不能删除、排序或添加条件，只能单独编辑。

### 方法和路径

- 方法为 `ANY` 或一个大写 HTTP 方法。精确方法比较忽略调用方传入的大小写。
- 路径只使用 URI path，不包含查询字符串。
- `EXACT` 使用完全相等比较。
- `PREFIX` 使用 `startsWith` 比较。
- `REGEX` 在保存时编译，并以完整路径匹配。需要部分匹配时用户显式写入 `.*`。
- 精确和前缀路径都必须以 `/` 开头；正则路径不施加该限制。

### 参数条件

每个条件包含来源、字段路径、操作符和可选期望值。单条规则的全部条件以 AND 组合。

| 来源 | 规则字段示例 | 解析语义 |
| --- | --- | --- |
| `QUERY` | `orderId` | URL 查询参数，UTF-8 解码，保留重复值。 |
| `HEADER` | `X-Request-Id` | HTTP 请求头，字段名不区分大小写，保留重复值。 |
| `FORM` | `status` | `application/x-www-form-urlencoded` 与 `multipart/form-data` 的文本字段，UTF-8 解码。文件部分不解析。 |
| `JSON` | `order.id`、`items[0].sku` | `application/json` 或 `*+json` 请求体中的标量路径。 |

对于同一字段的多个请求值，只要任意一个值满足条件即可命中：

- `EQUALS`：某个值与期望值完全相等。
- `EXISTS`：字段至少有一个值，期望值不参与匹配。
- `CONTAINS`：某个值包含期望文本。
- `REGEX`：某个值被期望正则完全匹配；需要部分匹配时使用 `.*`。

正则在保存规则时编译。JSON 路径不存在、请求体格式错误或对应来源无法解析时，该条件不命中，而不是使整个 HTTP 服务抛出异常。

## 请求解析与模板渲染

`CallbackMockHttpServer` 读取请求并创建不可变 `MockRequest`。请求体以 UTF-8 处理，最多捕获 1 MiB 的文本内容；超出时记录“已截断”，且不会让依赖 `FORM` 或 `JSON` 的条件命中。方法、路径、查询和请求头仍可正常匹配。

模板变量语法固定为 `${namespace.path}`：

- `${query.orderId}`
- `${header.X-Request-Id}`
- `${form.status}`
- `${json.order.id}`

渲染器只对响应体做一次替换。标量值写入其文本表示；对象或数组值写入紧凑 JSON。未解析到的变量原样保留，例如 `${json.missing}` 仍以原字符串返回，以免悄然生成错误报文。变量值中的 `${...}` 不再进行第二次解析。

响应体以 UTF-8 写出，状态码和 Content-Type 来自规则。内容类型不参与模板计算，保持用户配置值。

## 持久化和迁移

规则文件位于 `ApplicationPaths` 的配置目录，文件名为 `callback-mock-rules.json`。Windows 默认路径为 `%APPDATA%\JavaToolbox\callback-mock-rules.json`；其它平台遵循现有 `ApplicationPaths` 规则。

文件格式为版本化 JSON：

```json
{
  "version": 1,
  "rules": [
    {
      "id": "0c4d1a00-bd17-4f43-a8f8-03c7f88e5e55",
      "name": "支付成功",
      "enabled": true,
      "method": "POST",
      "pathMode": "PREFIX",
      "path": "/payments",
      "conditions": [
        { "source": "JSON", "field": "status", "operator": "EQUALS", "expected": "paid" }
      ],
      "response": {
        "statusCode": 200,
        "contentType": "application/json",
        "body": "{\"id\":\"${json.paymentId}\",\"result\":\"paid\"}"
      }
    }
  ],
  "fallbackResponse": {
    "statusCode": 200,
    "contentType": "application/json",
    "body": "{\"status\":\"success\",\"message\":\"Callback received\"}"
  }
}
```

仓库使用 `AtomicFiles.write` 写入同目录临时文件再替换目标文件。加载时：

- 文件不存在则使用现有的默认 200/JSON/成功报文。
- 已知旧版本迁移到当前 `MockRuleSet`。
- JSON 损坏或版本未知时保留原文件，加载内置默认配置，并向面板展示可操作的配置错误提示。
- 用户显式保存新规则后才会覆盖损坏文件；保存失败时内存中和正在运行服务中的最后有效快照不变。

不使用 `JsonPreferencesStore`，因为响应体可能超过平台 Preferences 的单值限制。

## Swing 交互

主页面保留服务端口、启动/停止按钮、请求历史和请求详情页签。原“自定义响应数据”区域替换为“响应规则”卡片：

- 规则表显示启用状态、名称、请求条件摘要和响应状态码。
- 标题操作为“新增规则”“编辑”“删除”“上移”“下移”和“编辑默认响应”。未选择普通规则时，依赖选择的操作禁用。
- 列表顺序就是运行时优先级；上移和下移立即持久化并发布新快照。
- 请求历史列表在标签中保留方法和路径，详情概要增加“命中规则”和“实际响应”字段。

新增和编辑规则使用可调整大小的独立 `JDialog`。当前面板在 820×520 最小窗口下无法同时容纳路径、条件表、响应模板和请求详情，独立窗口能让配置保持完整、可读且具备原子“取消/保存”语义。

编辑窗口分为三段：

1. 基本信息：规则名称、启用状态、方法、路径匹配方式和路径。
2. 参数条件：来源下拉框、字段、操作符、期望值，以及新增和删除行。选择 `EXISTS` 时期望值禁用。
3. 响应：状态码、Content-Type、响应体和变量帮助文字。

保存前显示字段级错误。新增 UI 文案写入三个消息资源文件；本轮不改变当前已挂载工具在运行中切换语言后的重建策略。

## 并发、资源和错误处理

- 编辑成功后先保存，再将完整、不可变的 `MockRuleSet` 原子替换给 `CallbackMockService`。保存失败不发布变更。
- 每个 HTTP 请求只读取一次快照，后续匹配和模板渲染都使用该快照，保证同一请求的规则视图一致。
- 请求记录在 HTTP 线程构造后通过 `SwingUtilities.invokeLater` 加入 Swing 列表。时间戳使用线程安全的 `DateTimeFormatter`，不再共享 `SimpleDateFormat`。
- `CallbackTestPanel` 实现 `ManagedResourceOwner`，其 `closeResources()` 可重复调用，并在主窗口关闭时停止 `HttpServer`。
- 无效端口、端口占用、规则加载失败和保存失败使用清晰的本地化提示；用户不会看到原始堆栈。
- 保存校验包括规则名称、状态码 100–599、路径格式、正则编译、JSON 路径格式、条件字段和响应模板语法。

## 测试策略

新增测试遵循红—绿—重构，并以纯逻辑测试优先：

1. `MockRuleResolverTest`：方法、三种路径方式、禁用规则、顺序优先、四种条件操作符、四类参数来源、重复值和默认回退。
2. `MockTemplateRendererTest`：四类变量、嵌套 JSON、对象/数组紧凑 JSON、缺失变量原样保留和单次渲染。
3. `CallbackMockRuleRepositoryTest`：首次默认配置、完整往返、规则顺序、版本迁移、损坏文件、原子写入失败和测试隔离配置根目录。
4. `CallbackMockServiceTest`：在随机空闲端口启动本地服务，发送不同方法、路径、查询、Header、URL 编码表单、multipart 文本字段和 JSON 请求，断言状态码、Content-Type、响应报文和命中规则各不相同。
5. `CallbackTestPanelTest`：在 EDT 创建面板，验证规则表的选择状态、默认响应不可删除、保存失败不替换服务快照，以及关闭资源可重复调用。
6. `I18nResourceTest`：确认新增回调 Mock 文案在默认、简体中文和英文资源中均存在。

完整验证包括 `mvn test` 和 `mvn package`。集成测试只使用回环地址与临时配置根目录，不连接真实外部系统，也不读写用户真实规则文件。

## 验收标准

1. 用户可通过界面创建、编辑、启停、删除和排序多条规则，并在重启后看到同样的规则和顺序。
2. 相同服务端口能够根据方法、路径、查询、Header、表单和 JSON 参数返回不同的状态码、Content-Type 和响应体。
3. 路径和参数的全部约定匹配方式均可用，且多条件严格按 AND 组合。
4. 响应体能正确渲染四类请求变量；缺失变量、损坏请求体和无命中规则不会中断服务。
5. 请求详情明确显示命中规则或默认响应。
6. HTTP 线程不访问 Swing 控件，关闭应用后不遗留 Mock 服务端口。
7. 新增单测、集成测试、完整测试和 Maven 打包均通过。
