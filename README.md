# Java 工具箱

> 桌面端多功能工具箱，基于 Java Swing + FlatLaf 外观包，零三方依赖（除 FlatLaf 外观包外）。
> JDK 8+ 即可运行，双击 `run.bat` 或 `java -jar java-toolbox.jar` 启动。

## 功能一览

| 分组 | 工具 | 说明 |
|------|------|------|
| 加密 | 摘要与编解码 | MD5 / SHA-1 / SHA-256 / AES-CBC / Base64 |
| 转换 | 进制与编码 | 二/八/十/十六进制互转，UTF-8/GBK/URL 编码 |
| 转换 | 时间戳转换 | 秒/毫秒、自定义格式、时区 |
| 算法 | 排序可视化 | 冒泡/选择/插入/快排/归并，逐帧动画 + 统计 |
| 算法 | 查找算法 | 二分查找（区间收缩过程）+ 线性查找 |
| 计算 | 科学计算器 | 表达式求值（Nashorn 优先，回退自实现双栈求值器） |
| 计算 | 统计计算 | 均值/中位数/方差/标准差/极差 |
| 杂项 | 正则测试 | 实时匹配高亮、分组捕获、匹配计数 |
| 杂项 | UUID 生成 | 批量生成、去横线、大写、一键复制 |
| 杂项 | JSON 格式化 | 无依赖 JSON 美化/压缩状态机 |
| 杂项 | 颜色转换 | HEX / RGB / HSL 互转 + 实时预览 |

## 主题

基于 FlatLaf 外观包，内置 54 套主题实时切换（带过渡动画）：

- **核心 LAF（6）**：Flat Light / Flat Dark / IntelliJ / Darcula / macOS Light / macOS Dark
- **IntelliJ 主题包（48）**：Material、GitHub Dark、Solarized、One Dark、Dracula、Cobalt 2、Nord 等

## 界面布局

```
┌─────────────────────────────────────────────┐
│  Java 工具箱  · 算法/加密/转换/计算/杂项  [主题▾] │
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

# 方式二：命令行
java -jar java-toolbox.jar
```

## 编译

```bash
mvn clean package -DskipTests
# 产物：target/java-toolbox.jar
```

## 技术栈

- Java Swing（GUI）
- FlatLaf 3.5.4（外观包 + IntelliJ 主题包）
- Maven Shade（打 fat jar）
- 纯 JDK 实现：JSON 美化、中缀表达式求值、AES 加解密

## 项目结构

```
src/main/java/com/aqishi/toolbox/
├── Main.java              # 启动入口
├── ui/
│   ├── MainFrame.java     # 主窗口：页签 + 列表 + 主题
│   ├── ToolPanel.java     # 工具面板抽象基类
│   └── ThemeManager.java  # FlatLaf 主题管理
├── crypto/                # 加密
├── convert/               # 转换
├── algo/                  # 算法
├── calc/                  # 计算
├── misc/                  # 杂项
└── util/UIUtils.java      # UI 辅助
```

## License

MIT
