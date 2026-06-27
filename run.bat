@echo off
chcp 65001 >nul
cd /d "%~dp0"
java -Xmx512m -Dfile.encoding=UTF-8 -jar .\target\java-toolbox.jar
if errorlevel 1 (
    echo.
    echo 运行失败，请确认已安装 JDK 8+ 并配置 java 到 PATH。
    pause
)
