#!/usr/bin/env sh
# macOS / Linux 启动脚本；Windows 请继续使用 run.bat。
# 显式指定 UTF-8，保证中文文件名、中文输出名与本地化资源在各平台一致。
set -e
cd "$(dirname "$0")"
exec java -Xmx512m -Dfile.encoding=UTF-8 -jar ./target/java-toolbox.jar
