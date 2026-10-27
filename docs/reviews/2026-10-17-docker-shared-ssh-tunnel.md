# Docker 复用公共 SSH 隧道

## 结构与行为

范围修正：SSH 终端与 SFTP 模块保持原版本。Docker 远程命令使用独立 `SshExecConnection`，TCP 模式只调用既有公共隧道接口；没有给终端会话添加 Docker 专用分支。

`SshTunnelBridge` 已是公共组件，数据库、Redis、Kafka、ZooKeeper、etcd、K8s 和 HTTP 客户端均使用同一套配置、凭据、主机指纹验证和租约管理。Docker 新增的 TCP 隧道模式直接调用它的 `bridgeEphemeral`。

- Docker 保留本机、SSH 远程命令两种既有方式，新增 SSH TCP 隧道。隧道模式可指定 SSH 服务器侧的目标主机和端口，本地监听只使用回环地址和自动端口。
- 本机 Docker CLI 通过固定的 `--host` 访问隧道端点；本地 Context 和 Docker 端点/TLS 环境变量不会覆盖该端点。该模式用于非 TLS TCP API，本机需要 Docker CLI。Unix Socket 和 TLS Context 可以继续使用 SSH 远程命令。
- 整个管理连接共用一份隧道租约，所有资源操作都经过同一个命令执行边界。取消与超时结束本工具的 CLI 和租约，公共 SSH 建连稍后返回时回收迟到租约；不打断共享 SSH 建连。
- UI 显示方式选择和目标设置，连接期间锁定参数，隧道模式禁用 Context。切回远程命令时保留原有 Context 输入。

## 验证结果

- JDK 17 离线 `mvn -B -o -Dtest=Docker*Test,SshSessionTunnelTest,SshTunnelBridgeTest,KafkaBrokerTunnelsTest,SshBackedPanelsTest,KubernetesEndpointTest,KubectlPortForwardTest,EtcdTlsTest,I18n*Test,ToolCatalogLabelsTest verify`：74 项，0 失败、0 错误、0 跳过；构建及依赖分析通过。
- 新增用例覆盖多个命令复用一个租约、独立消费者释放、取消不打断公共建连、超时后的迟到租约清理、执行中的 CLI 超时、无效路由与 CLI 初始化失败回收、环境变量隔离，以及 UI 方式切换和连接状态锁定。
- 使用最终 JAR、回环 Paramiko SSH 服务器、模拟 Docker API 和本机 CLI 测试程序验证真实 TCP 转发。Docker 和 K8s 获得同一个本地端口；Docker 关闭、错误目标连接均保留 K8s 访问能力；最后一个租约关闭后本地监听释放。测试服务器拒绝 SSH exec，确认 TCP 模式使用端口转发。
- 最终 JAR 内 1535 个应用文件与 `target/classes` 逐字节一致，新增类为 Java 17 字节码。`git diff --check` 通过。

未操作用户的真实 Docker daemon 或 Kubernetes 集群。本次没有修改公共隧道核心实现，也没有重跑完整回归；与本次相关的 Docker、SSH、Kafka 桥接、K8s/etcd TLS、目录和三语言资源均已验证。
