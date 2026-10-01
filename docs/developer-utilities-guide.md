# 开发工具：按优先级补齐的七项功能

本轮新增六个导航项，并在已有 HTTP 客户端中加入表单和文件上传。不新增 Java 运行依赖，沿用 Jackson、POI 和 JDK HTTP。

## 1. JSON / YAML 结构化对比（转换编码）

左右粘贴数据，选择 JSON 或 YAML。对象键顺序不影响结果；数值 `1` 和 `1.0` 按值比较，缺失字段与显式 null 区分。

- 忽略路径以逗号或换行分隔，精确匹配，例如 `$.time`、`$.meta.requestId`。忽略整个路径会跳过其子树。
- 数组身份字段留空时按下标比较；填写 `id` 时，对象数组按其直接 `id` 属性对齐。缺失、null 或重复 ID 会明确报错，字符串 ID 和数字 ID 不混同。
- 差异表列出新增、删除、修改的路径与前后值；选中行可查看完整内容，并可复制报告。
- 支持单份 JSON/YAML 文档，不支持 YAML 多文档流。最多 2 MB 输入、128 层递归和 10000 条变化；超限报错，不展示不完整的成功报告。

## 2. 表格转 SQL（数据与消息）

打开 UTF-8 CSV、XLS 或 XLSX，也可以直接粘贴 CSV。CSV 可选逗号、分号或 TAB；Excel 可以选择工作表。

首行是列名。导入后先查看前 100 行预览，再在“列映射”勾选要导出的列、修改目标列名和类型：TEXT、INTEGER、DECIMAL、BOOLEAN、DATE、DATETIME。

- 默认 TEXT，保留 `00123`、长编号、空格等原始文本。只有显式选择数字类型才会转换。
- NULL 标记默认 `\N`，可以修改或留空禁用；空字符串默认仍为空字符串，可单独勾选改为 NULL。
- BOOLEAN 接受 true/false/1/0；DATE 使用 ISO `yyyy-MM-dd`；DATETIME 使用 ISO 日期时间，也接受日期和时间之间的空格。
- Excel 读取显示值和已缓存的公式结果，不执行公式，也不连接外部数据源。日期显示格式不是 ISO 时，请先在表格中调整，或保持 TEXT。
- SQL 方言沿用数据库导出器：标准、MySQL、PostgreSQL、SQL Server、Oracle。按方言引用列名和转义字面量。只生成、复制或保存 INSERT 文件，不自动执行数据库写入。
- 最大 10000 行、256 列；CSV 最大 8 MB、Excel 文件最大 32 MiB。字段数量、列映射或类型不合法时停止生成，不静默丢列。

## 3. JSON 转 Java DTO（生成）

输入 JSON 对象或对象数组，选择包名、根类名、BEAN、LOMBOK 或 RECORD，可勾选 Jackson 注解。

- 根据所有数组元素合并属性和类型，不只读取第一个样本。整数按范围选 Integer/Long/BigInteger，小数选 BigDecimal。
- 嵌套对象生成同文件中的嵌套类型，数组使用 List；未知、空数组元素或无法统一的混合类型使用 Object。纯 null 字段也使用 Object。
- 字符串不自动识别成日期，避免把编号等数据误判；nullable 数值使用包装类型。
- 非法 Java 字段名做合法化，碰撞时追加后缀。可用 `原字段=新字段;其他字段=新字段` 明确重命名；Jackson 注解保留 JSON 原属性名。
- 保存结果为界面中配置的 `类名.java`。Record 需要 Java 16+；Lombok 模式需目标项目配置 Lombok，勾选 Jackson 注解需目标项目已有 Jackson annotations。
- 这是样本推断，不是接口契约：缺失字段是否必填、业务日期语义、枚举约束等仍需要开发者确认。

## 4. HTTP 表单与文件上传（已有 HTTP 接口测试）

请求体页新增 RAW / FORM / MULTIPART：

- RAW：保持原有文本请求体。
- FORM：按 UTF-8 URL 编码发送键值项，同名字段可以重复。
- MULTIPART：添加文本字段、勾选文件字段或通过“选择文件”批量添加附件。文件按流读取，不整份载入内存；单文件上限 1 GiB、最多 100 个字段。

在 POST、PUT、PATCH 或其他支持请求体的方法下发送；GET/HEAD 选了表单会提示切换方法。FORM/MULTIPART 自动设置正确的 Content-Type，边界由工具生成。状态条显示已提交给 HTTP 客户端的字节数，并不代表服务端已经落盘；“取消请求”中断传输和等待，最多等待 5 分钟。

集合和历史保存模式、字段值及文件路径，**不保存文件内容**。每次发送读取当时的文件，路径不可读会报错。环境变量可用于字段名、值和路径，预览隐藏标记为敏感的变量；文件选择之后更改环境可能改变实际读取路径，发送前应核对预览。旧集合缺少 bodySpec 时按 RAW 读取。

目前 cURL 导入/导出仍以 RAW 为主，表单/附件导出会明确提示使用集合，不生成可能失真的命令。旧版本不能识别新增 bodySpec 属性，保存新格式后不应降级使用旧 HTTP 集合实现。

## 5. MyBatis SQL 日志还原（数据与消息）

粘贴同一次调用的一行 `Preparing:` 和随后一行 `Parameters:`，选择 SQL 方言。工具按日志中的类型渲染字面量，并替换真正的问号占位符；字符串、引用标识符、注释和 PostgreSQL dollar 引用内的问号不替换。

支持 String/Character、整数、小数、Boolean、Date/Time/Timestamp 及对应 java.time 简名；null 独立标记输出 SQL NULL。参数数量不符、未知类型、不完整或多组交错日志会报错。

**结果只用于排查**。MyBatis 普通日志没有无损转义机制，参数本身若含 `(String), ` 之类分隔符，日志可能无法唯一解释；二进制对象打印值、截断字符串、丢失的时区也不能恢复。工具不会执行 SQL，也不把还原结果当作原 PreparedStatement 的执行证明。

## 6. Maven 依赖来源分析（系统）

在项目中执行 `mvn dependency:tree -Dverbose`，复制输出或打开输出文件。也支持依赖插件提供的 JSON 树（JSON 输出需要插件 3.7.0+）。

按 groupId/artifactId 过滤，报告包含版本、scope、从根项目到依赖的完整引入路径、原有 omitted/conflict 说明。多版本的构件附带 exclusion XML 片段，需自行选择添加在哪条直接依赖上。

这是离线报告分析，不解析 POM 的全部继承/依赖管理，也不联网搜索最新版本、不修改 POM、不假设最高版本一定获选。没有开启 verbose 的输入可能缺少被省略节点，因此没有发现多版本不代表没有冲突。与 JAR 分析工具配合时，可用报告中的坐标再去检查实际 JAR。

## 7. SSE 流式调试（网络与接口）

填写 GET 或 POST 地址、请求头和 POST 请求体，连接后逐条显示接收时间、连接开始后的毫秒数、事件 ID、事件类型和数据。点击行查看完整 data，可复制并清空记录。

- 解析 BOM、注释心跳、CR/LF/CRLF、多行 data、event/id/retry 字段；到空行才派发事件，EOF 未完成的事件丢弃。
- 可提供自定义 Last-Event-ID；GET 自动重连时沿用最新 ID，响应 204 后停止。尊重 retry 并限制在 100–30000 ms，单次启动最多重连 20 次。
- POST 不自动重连，避免重复业务请求。重定向直接报状态码，不把凭据转发至其他地址。
- 响应头最多等待 15 秒；收到合法流后可持续等待事件，用户停止会关闭网络流和工作线程。
- 最多显示最近 500 个事件；单事件/单行最大 65536 个字符，待刷新队列 128 项，超速丢弃数量通过状态提示告知。旧事件移除不等于服务器没有发过。
- SSE 输入只存在本次界面会话，不持久化；当前没有复用 HTTP 集合、SSH 隧道或自动业务级 token 刷新。

## 验证与来源

定向测试覆盖结构化差异、表格空值/编号/Excel 显示格式、生成代码实际 javac 编译、multipart 回环传输和字节进度、旧集合兼容、SQL 占位符、Maven 文本/JSON 路径、SSE 解析/续传/取消，以及新增面板构建和示例按钮调用。没有向生产数据库执行 SQL，也没有上传用户真实文件。

协议依据：[multipart/form-data RFC 7578](https://www.rfc-editor.org/rfc/rfc7578.html)、[WHATWG SSE](https://html.spec.whatwg.org/multipage/server-sent-events.html)、[Maven dependency:tree](https://maven.apache.org/plugins/maven-dependency-plugin/tree-mojo.html)。

最终验证：JDK 17 的 `mvn -B -o verify` 为 2448 个用例、0 失败、1 个 Windows 权限相关跳过，依赖分析和打包成功。JDK 21 对最终调整的相关路径再跑 31 个用例通过。详细记录见[交付检查](reviews/2026-10-01-developer-tools.md)。
