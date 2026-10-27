# 文档索引

这里集中列出当前项目的使用文档、路线图、优化记录和设计资料。新功能应先登记路线图，再补充对应的使用说明和生命周期约定。

## 产品与计划

- [功能路线图](feature-roadmap.md)：已交付能力、P0/P1/P2 候选项及进入实现前的检查门槛。
- [优化清单](OPTIMIZATION-BACKLOG.md)：技术债证据、批次状态、已落地修复和剩余风险。

## 使用与运维指南

- [Kubernetes 集群管理](k8s_manager_guide.md)：集群配置、共享 SSH 隧道、TLS、Namespace、日志、Exec、文件和端口转发。
- [远程桌面](remote_desktop_guide.md)：ICE/STUN、TCP 回退、信令和会话生命周期。
- [微信工具](wechat_tools_guide.md)：Windows 微信通讯录只读快照、文本群发定位与发送记录核验、图片兼容模式及运行要求。
- [etcd 管理](etcd_tools_guide.md)：v2/v3 键值浏览和编辑、TTL、认证、TLS/mTLS 与 SSH 隧道。
- [Docker 管理](docker_manager_guide.md)：本机/SSH、容器操作、镜像、网络和数据卷、超时与取消。
- [公共 SSH 隧道组件](ssh_shared_component_guide.md)：各工具的共享配置、回环端口、租约、取消和 Docker TCP 隧道接入。
- [批量端口扫描](port_scanner_guide.md)：IPv4/IPv6 段/CIDR、目标文件、命名方案、自定义端口组、所选端点诊断和结果导出。

## 设计记录

- [启动器式主界面重构](reviews/2026-10-28-launcher-ui.md)：卡片启动器、一级分类导航、顶栏搜索/收藏/一键主题，三档皮肤与功能对账清单。
- [Docker 管理与 K8s SSH 检查记录](reviews/2026-10-17-docker-k8s-ssh.md)：交付范围、生命周期、测试与已知限制。
- [Docker 公共 SSH 隧道检查](reviews/2026-10-17-docker-shared-ssh-tunnel.md)：TCP 方式、共享租约、超时取消和验证记录。
- [Docker 连接边界修正](reviews/2026-10-17-docker-connection-isolation.md)：恢复原 SSH 终端和 SFTP，Docker 使用独立命令连接及公共隧道接口。

- [批量端口扫描与网络诊断规划](superpowers/specs/2026-10-16-batch-port-scanner.md)：IP 段/CIDR、自定义端口集合、预设扩展、有界调度、结果导出与分批验收。
- [`superpowers/specs/`](superpowers/specs/)：架构、工作台、保险库和回调 Mock 等设计规格。
- [`superpowers/plans/`](superpowers/plans/)：架构迁移、基础设施拆分、工具目录和功能路线图实施计划。
- [`superpowers/checkpoints/`](superpowers/checkpoints/)：阶段性进度与恢复记录。

## 文档约定

功能描述以 `ToolCatalog` 和实际面板行为为准；版本、JDK 和构建命令以根目录 [README](../README.md) 与 `pom.xml` 为准。设计记录保留历史背景，不自动代表当前实现状态。
