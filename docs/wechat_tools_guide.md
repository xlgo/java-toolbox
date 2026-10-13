# 微信通讯录读取指南

在 Windows 微信 4.x 完成登录后，打开工具箱“微信群发与通讯录”的“通讯录管理”页，点击“自动读取通讯录”。无需切换微信页面、选择第一个好友、配置点击间隔或停止使用鼠标。

工具自动识别微信配置指定的数据目录，兼容 Documents 重定向和非默认存储位置。读取程序用数据库页的 HMAC 验证当前登录账号，避免把旧账号目录误当成当前账号。读取本地个人联系人及企业微信联系人，排除本人、已删除记录、群聊、群成员缓存及公众号；同名联系人按稳定 UserName 保留。个人记录不要求 flag 的第 2 位为 1，避免漏掉 flag=1 的联系人。

表格展示昵称、微信号、备注和标签。Excel 导出还保留性别、稳定 UserName 和头像链接，可原样导回；头像下载仍需选择联系人后单独执行。读取结束前不替换当前表格，停止或错误会保留原列表。

## 环境与读取方式

- Windows，已登录微信 4.x。
- 64 位 Python 3.11 或更新版本，包含 SQLite 内存反序列化支持。自动检测 python、py -3、python3。
- 本地 AES 解密使用 cryptography，缺少时助手自动通过当前 Python 的 pip 安装。不包含 OCR 模型，不再依赖 UIAutomation 或 Tkinter。

运行中的微信进程只以读权限打开；当前通讯录文件和 WAL 也只读取。助手验证每页认证值、WAL 头及帧校验和，只合并完整已提交事务，在内存 SQLite 中读取联系人。密钥与解密数据库不写入磁盘，不修改微信进程、客户端文件或系统无障碍状态，也不读取聊天记录。

本机微信 4.1.13.65 初次读取 588 条，但随后按具体联系人核查发现筛选漏读；已修正为读取 665 个唯一个人联系人。数量与客户端列表一致不能证明名单完整。本地记录的昵称和备注可能与手机端或当前客户端显示不同，支持按微信号和稳定 UserName 搜索。未来微信版本修改数据库结构或读取参数时可能需要适配；不能读取时会明确报错，不把失败当成空通讯录。

## 命令行

```powershell
python tools/wechat_export.py
# 可选：包含数据库中的群聊和公众号
python tools/wechat_export.py --all-contacts
```

输出为 UTF-8 JSON Lines：status、contact、complete 或 error。仅收到 count 与联系人数量一致的 complete 才认为读取成功。工具箱通过私有进程管道消费结果，不把联系人行打印到日志。

“导入外部通讯录文件”和剪贴板导入继续可用。WeChatContactReader.readContacts 也保留用于读取用户提供的已解密旧版 Contact/rcontact SQLite 文件；该旧接口不会自动解密原文件。

## 验证

```powershell
python -m unittest discover -s tools/tests -v
mvn -B -o verify
```

Python 回归覆盖页认证、错误密钥、WAL 提交边界、校验失败、联系人分类、同名好友、标签/性别和原文件不变；Java 回归覆盖完成协议、字段保留、Excel 往返、一键读取与停止后过期回调。
