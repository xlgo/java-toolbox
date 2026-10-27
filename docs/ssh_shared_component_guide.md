# 公共 SSH 隧道组件

`SshTunnelBridge` 是各工具共用的隧道桥接组件，源码位于 [SSH 基础设施](../src/main/java/com/aqishi/toolbox/feature/network/ssh/infra/SshTunnelBridge.java)。数据库、Redis、Kafka、ZooKeeper、etcd、K8s、HTTP 客户端和 Docker TCP 隧道均复用它。

共享内容包括 SSH 服务器配置、保险库凭据、主机指纹确认、活动会话、本地端口分配、转发目标检查和隧道租约。各工具只保存 SSH 配置 ID、目标地址和自己持有的租约。

## 接入方式

```java
try (var tunnel = SshTunnelBridge.bridgeEphemeral(sshConfigId, targetHost, targetPort)) {
    // targetHost 由 SSH 服务器访问；客户端连接下面的本地回环端点。
    client.connect(tunnel.getLocalHost(), tunnel.getLocalPort());
    // 当前连接的全部操作共用该租约。
}
```

| 方法 | 用途 |
|---|---|
| `bridgeEphemeral` | 使用 `127.0.0.1` 上自动分配的端口，适合 Docker、K8s、etcd 等普通连接 |
| `bridge` | 优先使用目标端口，本地占用时调整端口，兼容既有数据库、Redis 等工具 |
| `bridgeExact` | 指定回环地址和固定端口，适合 Kafka advertised broker 地址映射 |

相同 SSH 服务器和相同转发参数可以共享一条隧道。每次调用返回独立租约；`close()` 仅释放该调用方的租约，最后一个租约释放时才关闭转发。SSH 终端持有的会话继续由终端管理。

业务工具须在后台建立连接，并在失败、取消、断开及退出时释放自有租约。若取消发生在公共 SSH 建连过程中，应在建连返回后回收迟到的租约，避免关闭其他工具正在使用的会话。Docker 已覆盖这种取消与超时场景。

HTTPS/WSS 客户端通过隧道连接回环端口时，需要继续验证原服务器的 TLS 身份。K8s 使用 `RoutedTlsSocketFactory` 保留原始服务名，etcd 也保留原始服务名；不能因为连接本地端口就自动跳过 TLS 校验。

## Docker 的两种 SSH 用法

- **远程命令**：通过独立的 `infra/ssh/SshExecConnection` 执行远程 Docker CLI，仅打开 exec 通道，读取共享服务器配置和凭据。适合 Unix Socket，无需开放 Docker TCP 接口；不调用 SSH 终端会话实现，不操作 SFTP、终端状态或终端隧道列表。
- **TCP 隧道**：`DockerSshTunnelRunner` 调用公共 `bridgeEphemeral`，由本机 Docker CLI 连接本次转发的端点。所有资源操作复用该租约；关闭或超时只回收本工具的连接。

具体条件及步骤见 [Docker 管理指南](docker_manager_guide.md)。
