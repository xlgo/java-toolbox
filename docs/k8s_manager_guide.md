# K8s 集群管理 (K8s Manager) 使用与技术指南

## 概述

**K8s 集群管理面板 (`K8sManagerPanel`)** 是面向 Kubernetes 运维与开发人员打造的轻量可视化集群管理工具。支持导入多集群 Kubeconfig 配置，涵盖 Namespace 筛选、多资源浏览、Pod 实时日志流追踪、Web Terminal 交互，以及容器内部文件的上传与下载。

---

## 核心功能

### SSH 隧道访问集群

在 SSH 工具保存服务器，在 K8s 连接卡片勾选“启用 SSH 隧道”并选择服务器。API Server 地址填写**SSH 服务器可以访问的地址**，例如 `https://10.0.0.8:6443`。配置保存时记录 SSH 服务器 ID，密码和私钥仍由共享 SSH 配置及保险库管理。

工具自动分配本地回环端口。资源请求、YAML 操作、日志、Exec 和容器文件传输走同一隧道；Pod / Service / Deployment 的端口转发仍需要本机 `kubectl`，它也通过该隧道访问 API Server，无需在跳板机安装 kubectl。

TLS 默认校验原 API Server 的主机名；通过 IP 访问但证书只包含域名时，在“TLS 服务名”填写证书对应的服务名。导入 kubeconfig 时保留 `tls-server-name`。CA 和客户端证书认证保持有效，不需要为隧道勾选跳过 TLS。原理参考 [Kubernetes TLS 服务名配置](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_config/kubectl_config_set-cluster/)。

连接过程中锁定配置输入。连接失败、断开或关闭时，释放当前会话的隧道租约，关闭终端、日志与端口转发，不影响其他工具的 SSH 连接。保存的 SSH 服务器若已被删除，必须重新选择服务器才能连接。

### 1. 多集群与命名空间切换
- 支持读取本地 `~/.kube/config` 或粘贴文本/文件导入多集群环境。
- 提供顶栏下拉框在全局或指定 Namespace 之间快速切换。

### 2. 资源可视化浏览
- 支持浏览 **Pod**、**Deployment**、**Service**、**ConfigMap**、**Node** 等常用资源。
- 动态显示资源状态、副本数（Ready/Desired）、IP、创建时间等关健指标。
- 支持关键词实时模糊搜索过滤。

### 3. Pod 日志追踪 (Log Tail)
- 选中任意 Pod 后可直接开启日志查看窗口。
- 支持实时日志流滚动输出 (Follow)、行数限制与关键字筛选。

### 4. 容器 Web Terminal 交互 (Exec)
- 基于 `jediterm` / Swing 终端组件，使用 Kubernetes `v4.channel.k8s.io` WebSocket Exec 通道与 Pod 内指定 Container 交互。
- 提供接近原生 Shell (sh/bash) 的高保真交互体验。

### 5. 容器文件传输 (Upload & Download)
- **下载单个文件**：通过容器内 `cat` 读取指定文件并保存至本地。
- **上传单个文件**：通过容器内 `sh` / `cat` 写入指定路径；目标目录须存在且有写权限。

---

## 技术架构

```
 ┌─────────────────────────────────────────────────────────────┐
 │                    K8sManagerPanel UI                       │
 ├───────────────┬─────────────────┬─────────────┬─────────────┤
 │   资源列表    │  Log Streamer   │  Terminal   │ File Transfer│
 └───────┬───────┴────────┬────────┴──────┬──────┴──────┬──────┘
         │                │               │             │
 ┌───────▼────────────────▼───────────────▼─────────────▼──────┐
 │             KubernetesService / KubernetesClient            │
 │           HttpURLConnection / Java-WebSocket / kubectl       │
 └──────────────────────────────┬──────────────────────────────┘
                                │ REST / WebSocket / Exec API
                                ▼
                   ┌──────────────────────────┐
                   │  Kubernetes API Server   │
                   └──────────────────────────┘
```

---

## 使用指南

### 快捷操作流程
1. 打开 **运维 > K8s 集群管理** 面板。
2. 点击左上角 **“管理 Kubeconfig”** 导入集群配置。
3. 选择对应的 Namespace，在资源列表中找到目标 Pod。
4. 右键点击 Pod 列表项，弹出快捷操作菜单：
   - **查看日志**：开启日志流。
   - **进入终端 (Exec)**：打开 Terminal 执行命令。
   - **文件管理**：打开文件上传/下载模态框。
