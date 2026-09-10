# 文档索引

这里集中列出当前项目的使用文档、路线图、优化记录和设计资料。新功能应先登记路线图，再补充对应的使用说明和生命周期约定。

## 产品与计划

- [功能路线图](feature-roadmap.md)：已交付能力、P0/P1/P2 候选项及进入实现前的检查门槛。
- [优化清单](OPTIMIZATION-BACKLOG.md)：技术债证据、批次状态、已落地修复和剩余风险。

## 使用与运维指南

- [Kubernetes 集群管理](k8s_manager_guide.md)：集群配置、Namespace、日志、Exec 和文件传输。
- [远程桌面](remote_desktop_guide.md)：ICE/STUN、TCP 回退、信令和会话生命周期。
- [微信工具](wechat_tools_guide.md)：通讯录读取、导出和 Windows UIAutomation 脚本。

## 设计记录

- [`superpowers/specs/`](superpowers/specs/)：架构、工作台、保险库和回调 Mock 等设计规格。
- [`superpowers/plans/`](superpowers/plans/)：架构迁移、基础设施拆分、工具目录和功能路线图实施计划。
- [`superpowers/checkpoints/`](superpowers/checkpoints/)：阶段性进度与恢复记录。

## 文档约定

功能描述以 `ToolCatalog` 和实际面板行为为准；版本、JDK 和构建命令以根目录 [README](../README.md) 与 `pom.xml` 为准。设计记录保留历史背景，不自动代表当前实现状态。
