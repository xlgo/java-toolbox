# 七项开发工具交付检查

基于 `0127e6f`，按高优先级四项、随后中优先级三项实现。新增六个工具目录项，HTTP 上传扩展已有面板；未增加运行依赖，未修改已有 i18n 硬编码基线。

## 实现顺序和入口

1. `structured.diff`：JSON / YAML 结构化对比（转换编码）。
2. `table.sql`：CSV / Excel 生成 SQL（数据与消息）。
3. `json.dto`：JSON 转 Java DTO（生成）。
4. 原 HTTP 请求体页：RAW / FORM / MULTIPART、重复字段和多文件。
5. `mybatis.sql`：MyBatis 日志还原（数据与消息）。
6. `maven.tree`：Maven 依赖来源分析（系统）。
7. `sse.client`：SSE 流式客户端（网络与接口）。

具体格式、用法和限制见 [开发工具指南](../developer-utilities-guide.md)。

## 验证证据

- DTO 样本合并后通过 javac `--release 17` 编译 JavaBean 和 Record，包含嵌套对象、混合数组、重命名、特殊属性名和 Object 方法同名字段。
- CSV 测试检查空字符串与 NULL、前导零、多行字段和引号；Excel 测试用 POI 实际创建工作簿，再读取其指定工作表和格式化编号。
- multipart 测试使用本地 HTTP 服务接收二进制和中文附件，核对重复字段、总字节数、Content-Type 和进度；另以 32 MiB 临时文件验证取消。
- 取消上传的测试在 JDK 17 暴露文件句柄未及时释放，已在产品代码中跟踪并显式释放上传流。修复后测试在请求结束时立即删除文件，Windows 下也能成功，不靠等待或跳过规避。
- SSE 使用本地 HTTP 服务验证 GET 续传 ID、204 停止、POST 请求体和取消空闲流；解析测试覆盖 BOM、三种换行、多行数据、ID 清空和未完成事件。
- Maven 文本/JSON 样本测试覆盖 scope、classifier、冲突与多模块；另运行本项目真实的 `mvn dependency:tree -Dverbose`，成功解析 169 个节点并确认 Jackson 的引入路径。
- Swing 测试构建六个新面板，并实际触发 DTO/MyBatis/Maven 示例按钮。中文浅色和英文深色使用真实 Swing 组件离屏渲染检查，图片保留在本地 `target/devtools-*.png`，不发布到源码。

只连接了本地测试 HTTP 服务，没有上传用户文件或执行生成的 SQL。没有宣称 MyBatis 日志可无损还原任意参数、DTO 可从样本推断业务约束，也没有自动改写用户 POM。

## 最终检查结果

- JDK 17.0.20.1：最终源码执行 `mvn -B -o verify` 成功，2448 个用例、0 失败、0 错误、1 跳过（Windows 符号链接权限）；包含本轮新增 42 个用例，打包和依赖分析通过。
- JDK 21.0.11：较早快照完整 verify 通过；最后对 DTO 特殊字段、上传文件释放、SSE、面板示例及国际化再做定向回归，31 个用例全部通过。最终全量结果以 JDK 17 为准。
- `git diff --check` 通过，三份语言资源同步，`pom.xml` 和硬编码中文基线没有变更。
- 本轮变更可与基线 `0127e6f` 对比审阅。
