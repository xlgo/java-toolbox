# Docker 管理与 K8s SSH 隧道检查

范围修正：Docker 远程命令已迁移到独立的 `infra/ssh/SshExecConnection`。曾为 Docker 增加到 `SshSessionInstance` 的命令分支及相关测试已撤回，SSH 终端和 SFTP 模块与原版本一致；Docker TCP 模式继续调用原公共隧道接口。

## 交付范围

- 新增云原生与运维入口 `docker.management`，保留 `docker.convert`。
- Docker 使用现有 Docker CLI，无新增运行时依赖。本机模式继承 Docker Context；SSH 模式使用共享配置及主机指纹策略，在独立的 exec 会话运行远程 Docker，不要求本机 Docker CLI，不开放 daemon TCP 端口。
- 容器、镜像、网络、数据卷列表和详情，容器启停、重启、暂停/恢复、日志快照和单次统计，镜像拉取，创建容器/网络/卷以及非强制删除。
- 创建容器支持端口映射、环境变量、挂载和重启策略。所有参数独立传递，本机不经 shell，远程逐参数 POSIX 引号转义。
- 超时覆盖 SSH 建连、命令执行和输出读取；每路输出限制 8 MiB。取消关闭连接，写操作不自动重试，退出码非零明确报错。
- K8s 配置持久化 SSH 开关、共享服务器 ID 和 TLS 服务名；导入 kubeconfig 保留 `tls-server-name`。通过自动分配的回环端口访问 API，HTTP 和 WebSocket 仍验证原始服务器身份，CA 和客户端证书认证有效。
- 日志、Exec、文件与 kubectl 端口转发共用路由；断开连接关闭子窗口、HTTP 客户端及自有隧道租约。

## 验证

- 首轮 73 项专项测试通过，覆盖 CLI 参数/退出码/错误流、超时/取消/输出上限、Docker 页面加载和筛选、K8s SSH 服务器选择、TLS/mTLS、实际 HTTPS GET/POST、实际 WebSocket 正向与主机名不匹配拒绝、kubectl 配置中的 TLS 服务名，以及既有 SSH 桥接回归。
- 本地 Paramiko 模拟服务器验证实际 SSH exec：引号与命令替换字符保持字面值、退出码 17 和错误流、执行超时、会话复用、SSH 握手超时；服务器不提供 shell/SFTP，验证 Docker 命令模式不依赖它们。
- 页面离线渲染检查连接控件、可折叠 Docker 配置、资源表格与操作区；README、指南和三语言资源均已补充。
- 首次完整回归发现端口扫描测试未等待异步 SQLite 文件释放，Windows 临时目录清理出现竞态；已补测试清理等待，不改变生产环境异步关闭行为。
- 为 SSH 命令会话增加配置隧道隔离回归：关闭 Docker 命令会话不重置终端正在运行的配置隧道。WebSocket 拒绝证书后的测试清理改为直接释放连接，并为网络集成测试设置 45 秒上限，避免第三方库的关闭等待锁住整套回归。
- 最终专项 `mvn -B -o -Dtest=Docker*Test,Kubernetes*Test,Kubeconfig*Test,KubectlPortForwardTest,SshSessionTunnelTest,SshTunnelBridgeTest,SshBackedPanelsTest,PortScannerPanelTest,I18n*Test,ToolCatalogLabelsTest,Ice4jDirectConnectorTest -Dsurefire.rerunFailingTestsCount=1 verify`：119 项，0 失败、0 错误；既有 ICE 用例重试一次通过，构建成功，依赖分析无问题。
- 最后一轮完整回归共 2578 项，2 项失败、0 错误、1 项跳过：既有 ICE 大数据帧接收及 HTTP 压测速率计时断言出现波动。本次 Docker/K8s/SSH 用例均通过。ICE 在上述专项重试后通过；`HttpBenchRunnerTest` 独立复测 9 项也在重试一次后通过。未修改这两个模块的生产代码或断言，不将本次结果描述为完整回归单次全绿。
- 最终 JAR 中 1528 个应用文件与 `target/classes` 逐字节一致，新增类为 Java 17 字节码。直接使用最终 JAR 在本地 SSH 模拟服务器验证 Docker exec、超时、K8s API 前缀与 Bearer 认证、共享隧道租约和 Docker 会话关闭隔离，全部通过。
- 应用已启动并显示 Docker 管理入口，没有自动连接 Docker daemon 或 Kubernetes 集群。

未连接或修改用户实际 Docker daemon 和 Kubernetes 集群。持续日志、Docker 交互终端、Compose 和镜像构建留待后续；K8s 端口转发依赖本机 kubectl，SSH Docker 依赖远程 POSIX shell 和 Docker CLI。
