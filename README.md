# Java 工具箱

> 桌面端多功能工具箱，基于 Java Swing + FlatLaf 外观包，零三方依赖（除 FlatLaf 外观包和轻量级国密支持外）。
> JDK 8+ 即可运行，双击 `run.bat` 或 `java -jar java-toolbox.jar` 启动。

## 功能一览

| 分组 | 工具 | 说明 |
|------|------|------|
| 加密 | 摘要与编解码 | MD5 / SHA-1 / SHA-256 / SM3 摘要与 Base64 编解码 |
| 加密 | 对称加密 | AES / DES / 3DES / SM4（支持 ECB/CBC 模式、PKCS5 填充、防乱码文本密钥生成） |
| 加密 | 非对称加密 | RSA / SM2（支持密钥对生成、公钥加密/私钥解密、私钥签名/公钥验签） |
| 转换 | 进制与编码 | 二/八/十/十六进制互转（二进制 4 位自动分组美化），UTF-8/GBK/URL 编码 |
| 转换 | 时间戳转换 | 秒/毫秒、自定义格式、时区 |
| 算法 | 排序可视化 | 冒泡/选择/插入/快排/归并，逐帧动画 + 统计 |
| 算法 | 查找算法 | 二分查找（区间收缩过程）+ 线性查找 |
| 计算 | 科学计算器 | 表达式求值（Nashorn 优先，回退自实现双栈求值器） |
| 计算 | 统计计算 | 均值/中位数/方差/标准差/极差 |
| 杂项 | 正则测试 | 实时匹配高亮、分组捕获、匹配计数 |
| 杂项 | UUID 生成 | 批量生成、去横线、大写、一键复制 |
| 杂项 | JSON 格式化 | 无依赖 JSON 美化/压缩状态机 |
| 杂项 | 颜色转换 | HEX / RGB / HSL 互转，集成 **JColorChooser 调色板** 与 **一键复制** |

## 主题与体验优化

- **主题系统**：基于 FlatLaf 外观包，内置 54 套现代主题实时切换（带平滑过渡动画），如 Material、GitHub Dark、Solarized、One Dark 等。
- **排版与渲染**：输入输出区域字体统一采用 **微软雅黑 (Microsoft YaHei)**，不仅在英文字符下完美避免了连字现象（如等号正常分立），同时解决了中文字符显示为问号或乱码的渲染痛点。
- **主题高度自适应**：全局优化所有主题下的组件高度表现，按钮、输入框、下拉选择框等高度均自适应且全局最低保持 32 像素，防止元素在某些精简主题下显得局促或被裁剪。

## 界面布局

```
┌─────────────────────────────────────────────┐
│  Java 工具箱  · 算法 / 加密 / 转换 / 计算 / 杂项  [主题▾] │
├─────────────────────────────────────────────┤
│ [加密][转换][算法][计算][杂项]  ← 一级页签      │
├──────────┬──────────────────────────────────┤
│ 工具列表  │                                  │
│ • 工具A  │       内容区（CardLayout）         │
│ • 工具B  │                                  │
│ • 工具C  │                                  │
└──────────┴──────────────────────────────────┘
```

## 运行

```bash
# 方式一：双击
run.bat

# 方式二：命令行启动
java -Dfile.encoding=UTF-8 -jar java-toolbox.jar
```

## 编译

```bash
mvn clean package -DskipTests
# 产物：target/java-toolbox.jar
```

## 技术栈

- Java Swing（GUI）
- FlatLaf 3.5.4（外观包 + IntelliJ 主题包）
- BouncyCastle 1.70（国密 SM2/SM3/SM4 算法支持，提供与经典加解密的统一调用）
- Maven Shade（打 fat jar）
- 纯 JDK 实现：JSON 美化、中缀表达式求值、标准 AES/DES/3DES/RSA 加解密

## 项目结构

```
src/main/java/com/aqishi/toolbox/
├── Main.java              # 启动入口
├── ui/
│   ├── MainFrame.java     # 主窗口：页签 + 列表 + 主题
│   ├── ToolPanel.java     # 工具面板抽象基类
│   └── ThemeManager.java  # FlatLaf 主题管理
├── crypto/                # 加密相关
│   ├── CryptoPanel.java   # 摘要与编解码面板
│   ├── SymmetricPanel.java# 对称加密面板 (AES/DES/3DES/SM4)
│   ├── SymmetricUtils.java# 对称加密工具
│   ├── AsymmetricPanel.java# 非对称加密面板 (RSA/SM2)
│   ├── RSAUtils.java      # RSA 算法工具
│   ├── SM2Utils.java      # SM2 算法工具
│   └── SM3Utils.java      # SM3 算法工具
├── convert/               # 转换
├── algo/                  # 算法
├── calc/                  # 计算
├── misc/                  # 杂项
└── util/UIUtils.java      # UI 辅助
```

## License

MIT
