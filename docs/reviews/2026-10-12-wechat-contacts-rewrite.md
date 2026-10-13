# 微信通讯录自动读取重写（2026-10-12）

目标是登录微信后一次点击即可取得通讯录，取消手动选中首个好友、模式选择、点击间隔和鼠标焦点依赖。按用户要求不引入 OCR。

## 当前实现

- 删除原 UIAutomation/Tkinter 模拟点击脚本，改为 `wechat_export.py` 的 JSON Lines 入口和 `wechat_contacts.py` 的本地只读快照读取。
- 自动识别微信配置目录与重定向 Documents 路径，覆盖非默认存储位置；用数据库第一页 HMAC 匹配运行中账号，排除不能验证的旧账号目录。
- 以只读进程权限取得当前通讯录读取参数，不写入微信进程或客户端文件。密钥仅存内存，不缓存到配置、日志或磁盘。
- 对 SQLCipher 4 的每一页验证 HMAC，校验 WAL 头与帧校验和，仅合并完整已提交事务。只读取 contact.db 及其 WAL，不读取聊天数据库，解密后使用内存 SQLite。
- 默认读取个人联系人及企业微信联系人，排除本人、已删除记录、群聊、群成员缓存和公众号。2026-10-13 去除误用的 flag 第 2 位条件，避免 flag=1 的个人联系人被丢弃。支持稳定 UserName、自定义微信号、昵称、备注、标签、性别和头像链接；同名好友不合并。
- 工具箱去掉获取参数弹窗，单击开始、再次单击停止。完成协议和数量验证通过后整体替换表格；错误、停止和旧任务回调不覆盖已有数据。失败不会报为“0 人读取成功”。
- Excel 导出增加性别、原始 UserName 和头像链接，并支持往返导入。维持原文件导入、剪贴板导入和头像下载入口。
- 使用现有 Python 的 cryptography 进行 AES；缺少时自动安装。未加入识别模型、UIAutomation 或 OCR 依赖。Maven 打包两个读取脚本，CI 增加 Python 快照回归。

## 证据

- 本机 Windows 微信 4.1.13.65：读取 588 个联系人，588 个唯一 UserName，与客户端通讯录显示数量一致。该初次验证仅比对总数，随后发现漏读，不能作为名单完整性证据；同时发现默认目录旧数据和当前自定义目录，当前账号通过 HMAC 选择。后续修正见 2026-10-13 检查记录。
- Java 实际启动助手并消费 JSON Lines 后导出、导回 Excel：588 条往返，稳定 ID、标签、性别和头像链接逐条相同。验证文件随后删除，验证输出只含状态与数量。
- Python 13 个用例通过，覆盖错误密钥、篡改页、认证页码、WAL 提交边界、尾部未提交帧、事务缩小数据库、校验失败、原文件不变、联系人分类、同名联系人、字段解析及不支持的表结构。
- Java 定向回归 16 个用例通过，含完成协议、错误/停止、同名好友、字段保存、Excel 往返和 Swing 一键读取/取消后迟到结果。
- JDK 17.0.20.1 执行 `mvn -B -o verify`：2502 个用例，0 失败、0 错误、1 跳过（Windows 符号链接权限）。依赖声明分析与 JAR 打包通过。
- `git diff --check` 通过。工作区原有 Main.java、KafkaResource.java、MainFrame.java、logback.xml 修改保留。

本轮的微信读取已在真实登录账号验证；未来微信版本的数据库或内存配置结构变动仍可能需要适配。需要 Windows、已登录微信 4.x、64 位 Python 3.11+ 和 SQLite 内存反序列化能力。

## 参考

- [SQLCipher 设计](https://www.zetetic.net/sqlcipher/design/)：AES-CBC、页认证、HMAC 派生及 WAL 加密。
- [SQLite 文件格式](https://sqlite.org/fileformat2.html#walformat)：WAL 帧、提交标记和校验和。
- [SQLite 内存反序列化](https://sqlite.org/c3ref/deserialize.html)：内存快照读取约束。
- [WCDB 配置读取的公开实现](https://github.com/fanyuantaier/wechatauto-replica/blob/main/wechatauto/db.py)：读取参数的结构资料；本项目独立实现并通过实际数据库 HMAC 验证，不引用其运行依赖。
