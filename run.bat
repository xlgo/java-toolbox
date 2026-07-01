@echo off
chcp 65001 >nul
cd /d "%~dp0"
java -Xmx512m -Dfile.encoding=UTF-8 -jar .\target\java-toolbox.jar
