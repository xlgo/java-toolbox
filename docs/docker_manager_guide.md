# Docker 管理

入口：**云原生与运维 → Docker 管理**。原有 Docker Run / Compose 转换保留独立入口。

## 连接

Docker 是独立管理工具。远程命令使用独立的 `SshExecConnection`，只打开 exec 通道；SSH 终端和 SFTP 的界面、会话实现及原有功能保持原样。Docker 读取已保存的 SSH 配置，TCP 模式调用现有公共隧道接口。

- 本机：安装 Docker CLI，并启动 Docker Desktop 或 Docker Engine。默认运行 `docker`，沿用 CLI 的当前 Context 和环境变量。展开“连接设置”可以填写可执行文件完整路径、Context 名称和任务超时（1～600 秒，默认 60 秒）。支持 Windows、Linux 和 macOS 上的本机 Docker CLI。
- SSH 远程命令（默认）：先在 SSH 工具保存服务器，再勾选“SSH 远程 Docker”、选择服务器及“远程命令”。工具通过已有凭据和主机指纹验证建立独立命令会话，在远程执行 Docker CLI；本机无需安装 Docker。远程服务器须提供 POSIX shell、Docker CLI，SSH 用户须有访问 Docker 的权限。可执行文件和 Context 设置此时属于远程服务器；不支持把 `sudo docker` 当作可执行文件填写。

- SSH TCP 隧道：勾选 SSH 并选择“TCP 隧道”，填写 Docker 目标主机和端口，例如 `127.0.0.1:2375` 或 `10.0.0.23:2375`。目标是 **SSH 服务器侧可访问的非 TLS Docker TCP API**，可以是另一台内网主机；本机需安装 Docker CLI。此模式直接复用公共 `SshTunnelBridge`，自动分配回环端口，所有管理操作使用同一租约。隧道模式固定本次转发端点，禁用 Context，并隔离本机 `DOCKER_HOST` / `DOCKER_CONTEXT` / TLS 环境变量及回环代理设置，避免连接到其他 daemon。TLS TCP 接口可使用“远程命令”配合远程 Context。
- 本机也可在 Context 中选择已配置的远程 TCP/TLS/SSH daemon；对应认证由本机 Docker CLI 管理。
- SSH 密码和私钥复用 SSH 工具及保险库，不在 Docker 页面重复保存。“远程命令”无需开放 2375/2376；“TCP 隧道”需要已有 TCP API，可仅监听远程回环地址。工具不会修改 Docker daemon 的监听配置。

公共组件与各工具的接入约定见 [公共 SSH 隧道](ssh_shared_component_guide.md)。

## 功能

| 资源 | 操作 |
|---|---|
| 容器 | 包括已停止容器的列表、筛选、完整详情、启动/停止/重启/暂停/恢复、删除、最近日志、单次 CPU/内存/网络用量 |
| 镜像 | 列表、筛选、详情、拉取、删除 |
| 网络 | 列表、筛选、详情、按名称和驱动创建、删除 |
| 数据卷 | 列表、筛选、详情、按名称和驱动创建、删除 |

创建容器支持镜像、名称、多个端口映射、逐行环境变量和卷挂载，以及重启策略。创建后以后台模式启动；缺少镜像时由 Docker 自动拉取。端口示例：`127.0.0.1:8080:80,127.0.0.1:8443:443`。SSH 模式下绑定目录是**远程主机目录**。

日志默认最近 200 行，可调到 1～10000 行；同时收集容器标准输出与错误输出。当前提供日志快照和单次统计，不包含持续追踪、交互终端、Compose 项目管理、构建镜像或批量清理。

## 结果、超时与关闭

所有命令在后台运行，退出码为 0 才显示成功；失败时展示 Docker 返回的错误。停止、重启、暂停、恢复和删除需要操作确认。删除不使用强制参数，不自动删除容器数据卷；Docker 会拒绝仍在使用的资源。

拉取镜像等任务可提前调大超时。取消任务会关闭本次 Docker 连接，终止本地子进程或远程 exec 通道，重新连接后刷新核对实际状态。超时或 SSH 断开不代表 daemon 已撤销操作，工具不会自动重试写操作。命令单路输出限制为 8 MiB，超限报错，避免无限占用内存。

连接、断开和退出只释放本工具拥有的命令会话或隧道租约，不影响其他工具的 SSH 隧道。TCP 隧道的建立与 CLI 执行共用任务超时；取消期间若公共 SSH 建连稍后才完成，会自动回收迟到的租约。

实现依据：[Docker CLI](https://docs.docker.com/reference/cli/docker/)、[容器列表与 JSON 格式](https://docs.docker.com/reference/cli/docker/container/ls/)。
