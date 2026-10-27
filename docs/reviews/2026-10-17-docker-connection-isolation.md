# Docker 连接与 SSH 终端 / SFTP 的边界修正

Docker 是独立的管理工具，通过 Docker CLI 管理资源，可读取共享 SSH 配置或调用公共 TCP 隧道接口。

## 本次修正

- 撤回 `SshSessionInstance` 中为 Docker 引入的 `commandOnly`、命令通道及生命周期分支；撤回原 SSH 会话测试中的对应增补。SSH 终端与 SFTP 模块的源码和测试均与原版本一致，SSH 客户端面板及保险库面板也没有改动。
- Docker 远程命令改用新建的 `infra/ssh/SshExecConnection`，只持有自己的 JSch session 与 exec 通道，不注册终端会话、不打开 shell 或 SFTP、不读取或改写终端隧道列表。
- Docker 连接界面向独立连接注入主机指纹确认回调。默认拒绝未知主机；已保存指纹可复用，已变更的密钥拒绝连接。共享配置的密码、私钥文件、私钥文本及保险库凭据仍然可用。
- Docker TCP 模式继续使用现有 `SshTunnelBridge` 的公共接口，没有修改其内部实现。

## 验证

- 相关验证共 91 项，0 失败、0 错误、0 跳过；包含独立连接、Docker 操作与 UI、SSH 原有会话和配置、Kafka 桥接、K8s/etcd TLS、目录及国际化。JDK 17 离线构建和依赖检查通过。
- 本地 SSH 模拟器验证密码、加密私钥文件、加密私钥文本、未知主机拒绝、已保存指纹复用、变更密钥拒绝、实际退出码/标准错误、执行超时和握手超时。逐会话检查确认 Docker 命令连接没有打开 shell 或 SFTP 通道。
- 复测 Docker 与 K8s 共用 TCP 转发、失败目标隔离和租约释放；Docker 命令连接和隧道连接关闭后均保留其他调用方的连接。
- 原 SSH 模块差异检查为空，关键恢复文件与 Git 原版本逐字节比较一致（仅统一换行符后比较）。最终 JAR 内 1538 个应用文件与 `target/classes` 一致，新增类为 Java 17 字节码。`git diff --check` 通过。

验证使用回环 SSH 服务器与模拟 API，没有操作用户的 Docker daemon、集群或现有 SSH 终端。
