# 远程桌面 (Remote Desktop) 使用与技术指南

## 概述

**远程桌面面板 (`RemoteDesktopPanel`)** 提供纯 P2P（点对点）桌面控制与画面共享功能。优先基于完整 ICE/STUN UDP 打洞状态机直连，在 UDP 失败时可自动回退到基于 UPnP/NAT-PMP 端口映射的 TCP 直连通道。信令服务器仅在建立连接前协助交换 Candidate 与 Session 信息，桌面图像与控制指令绝不上报或转发至公网第三方服务器。

---

## 核心架构与原理

```
                           ┌──────────────────────────┐
                           │   信令服务器 (Signal Server)│
                           │   (WebSocket / TCP 信令)  │
                           └─────────────┬────────────┘
                                         │ 信令交换 (Room / Candidates)
                  ┌──────────────────────┴──────────────────────┐
                  ▼                                             ▼
       ┌────────────────────┐                        ┌────────────────────┐
       │   控制端 (Client)   │ ◄════════════════════► │   被控端 (Host)    │
       └────────────────────┘      P2P UDP / TCP     └────────────────────┘
                                   (ICE 打洞 / 直连)
```

1. **信令交互 (`DesktopSignalClient`)**
   - 控制端与被控端连接到指定的信令服务器（可私有部署）。
   - 通过房间号 (Room ID) 配对，互相交换 Candidate 地址列表（Host、Reflexive）。
2. **ICE UDP 直连 (`Ice4jDirectConnector`)**
   - 集成 `ice4j` 库，建立 Full ICE 状态机。
   - 自动向多组 STUN 服务器发包获取公网反射 IP/端口 (`StunClient`)。
   - 自动完成 Triggered Check、Peer-reflexive candidate 处理与 Role Conflict 提名。
3. **TCP 直连回退 (`TcpDirectConnector`)**
   - 如果 UDP 打洞受限于严苛的对称型 NAT（Symmetric NAT），自动尝试 TCP 模式。
   - 被控端通过 `UpnpPortMapper` (UPnP IGD) 与 `NatPmpPortMapper` 申请公网 Gateway 端口映射，建立 TCP 监听。
4. **图像采集与传输**
   - 被控端使用 `java.awt.Robot` 捕获当前屏幕图像，按帧差分/压缩后经数据通道 (`UdpChannelImpl` / `SocketChannelImpl`) 发送。
   - 控制端监听鼠标与键盘事件（点击、拖拽、滚轮、按键），编码为二进制操作指令投递给被控端执行。

---

## 使用说明

### 1. 被控端 (Host) 开启共享
1. 打开 **监控 > 远程桌面** 面板。
2. 切换到 **“被控端”** 页签。
3. 配置信令服务器地址（默认内置公共信令，亦可启动本地信令中转服务 `DesktopSignalServer`）。
4. 点击 **“启动被控端服务”**，系统会自动生成 6 位连接验证码。
5. 将验证码提供给控制端操作人员。

### 2. 控制端 (Client) 发起连接
1. 切换到 **“控制端”** 页签。
2. 输入被控端提供的 6 位验证码。
3. 点击 **“连接远程桌面”**。
4. 连接成功后弹出会话窗口，可实时查看被控端桌面画面，并使用本地鼠标键盘操作远程主机。

---

## 运维与自建信令服务器

若需完全隔离局域网或搭建专网环境，可直接使用本工具箱内置的 `DesktopSignalServer` 启动轻量信令服务：

```bash
# 启动内置信令服务器（指定端口，如 9090）
java -cp target/java-toolbox.jar com.aqishi.toolbox.monitor.DesktopSignalServer 9090
```

---

## 常见问题与排查

| 现象 | 可能原因 | 解决办法 |
|------|----------|----------|
| 信令连接失败 | 防火墙拦截信令端口或 WebSocket 地址填写错误 | 检查信令服务器端口开通情况及网络连通性 |
| 打洞持续超时 | 两端处于双重 Symmetric NAT 环境 | 勾选或开启 UPnP/NAT-PMP 路由器支持，或切换到相同局域网组网 |
| 画面卡顿或延迟大 | 屏幕分辨率过高或网速受限 | 在被控端设置中降低帧率（如 15 fps）或开启画面压缩比例 |
