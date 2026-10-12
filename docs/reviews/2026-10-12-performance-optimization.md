# 性能与可靠性优化（2026-10-12）

本轮处理文本对比、正则匹配、Redis 键浏览、SQL 执行及两个已有安全公告的依赖。开始时 Main.java、KafkaResource.java、MainFrame.java、logback.xml 已有未提交修改，本轮保留这些修改。

## 实现

| 功能 | 当前行为 |
|---|---|
| 文本对比 | 抽取 TextDiffService，使用 Hirschberg 线性空间 LCS，裁剪相同前后缀，预先处理忽略空白选项。计算和未挂载的样式文档生成在后台执行，支持取消和清空，忽略旧任务回调。每份文本最多 2,097,152 字符、20,000 行；对复杂差异限制计算工作量和计算时间。 |
| 正则 | 保留 Java Pattern 的后向引用、后顾和捕获组语义，200 ms 输入防抖，单个后台执行器，结果按输入代次更新。共享 CharSequence 在读取字符时检查中断及默认 500 ms 预算；对不读取字符的超大空重复另作重复次数及嵌套工作量检查。限制输入、捕获组、输出及 2000 条显示结果，处理正则引擎的栈溢出。 |
| Redis | 键浏览自动刷新改用 SCAN MATCH，每次加载至多 16 个扫描请求。默认每次最多追加 1000 条，可配置 1–10,000 条并记住设置，加载更多使用当前配置继续追加，累计数量不再受原 5000 条上限限制。超过本批大小的 SCAN 返回值保留到下一批；空批次不视为结束，COUNT 仅作为提示。键去重排序，树在后台按子节点名称索引构建，列表批量安装；取消、刷新、断开后忽略过期结果。 |
| SQL | 在驱动支持时设置 30 秒查询超时、5001 行驱动上限和 fetchSize 提示；保留恰好 5000 行时不误报截断。取消请求先设置非阻塞标记，再在后台调用 Statement.cancel。直到原执行结束才允许新查询；执行期间暂停库和模式切换；断开和关闭时请求取消，忽略旧连接返回的结果。耗时包含结果读取，执行前清除旧导出快照。 |
| 依赖 | Bouncy Castle 从 jdk15on 1.70 迁移到 jdk18on 1.86，PostgreSQL 驱动从 42.6.0 更新到 42.7.14。未新增第三方依赖。 |

同步更新 README、三份语言资源及硬编码文案基线。

## 验证

- 新增 32 个回归用例，覆盖最短行差异、重复行、保留原文及尾随空行、大输入、复杂回溯及空重复、Unicode 转义及字符类、扫描游标及重复键、分批大小和缓冲、SQL 的驱动配置及取消、界面任务代次与清空行为。
- JDK 17.0.20.1 的全量 `mvn -B -o verify` 成功：2490 个用例，0 失败、0 错误、1 跳过（Windows 符号链接权限）。这次全量之后新增空重复回归并调整正则边界识别，后续验证覆盖最终改动。
- JDK 17 定向 verify：43 个用例通过；覆盖文本、Redis、SQL、Swing 操作及国际化。
- 正则边界修正后的源码在 JDK 21.0.11 定向 verify：102 个用例通过；包括上述范围、证书签发、国密、加密及保险库密码学，打包与 dependency:analyze 检查通过。
- 最终正则边界识别及界面在 JDK 17.0.20.1 再次定向验证：16 个用例通过，包括最终 Unicode 转义、字符类与空重复边界及国际化门禁。
- Redis 每批数量配置调整后，JDK 21.0.11 定向 verify：18 个用例通过，打包与依赖声明检查通过。验证默认 1000 条、输入数量提交及设置记忆、继续加载时改变批大小、累计超过 5000 条，以及 SCAN 超量返回缓冲后无丢失。
- 用最终可执行 JAR 的类在独立 64 MiB 堆进程内对比两份各 6000 行的相同文本，得到 6000 条结果；旧实现在 128 MiB 堆下会因二维矩阵分配失败。
- `git diff --check` 通过。最终产物为 `target/java-toolbox.jar`。

没有连接真实 Redis、数据库、K8s 或外部证书服务；网络命令和 JDBC 取消用替身验证。SQL 超时、fetchSize 和取消的具体支持程度由驱动决定。没有进行 Linux/macOS 真机或人工窗口排版验收。

## 依据

- [Redis SCAN](https://redis.io/docs/latest/commands/scan/)：游标、空批次、重复返回以及 COUNT 提示的语义。
- [JDK 17 Statement](https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/Statement.html)：行数、超时、读取提示与取消接口。
- [Bouncy Castle Java 下载](https://www.bouncycastle.org/download/bouncy-castle-java/)：jdk18on 1.86 构件。
- [Bouncy Castle CVE-2024-29857](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902024%E2%80%9029857)：旧版本证书参数处理的修复公告。
- [pgJDBC 下载](https://jdbc.postgresql.org/download/)及 [42.6.1 安全公告](https://jdbc.postgresql.org/changelogs/2024-02-21-42.6.1-release/)：维护版本及旧驱动的安全修复。
