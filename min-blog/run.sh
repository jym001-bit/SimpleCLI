#!/usr/bin/env sh
# MinBlog 一键启动（macOS / Linux）
set -e
cd "$(dirname "$0")"

mkdir -p out

echo "[1/2] 编译中..."
javac -encoding UTF-8 -d out src/com/minblog/*.java

echo "[2/2] 启动服务..."
echo "浏览器打开 http://localhost:8080 （按 Ctrl+C 停止）"
echo
java -Dfile.encoding=UTF-8 -cp out com.minblog.Main
