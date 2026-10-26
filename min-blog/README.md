# MinBlog — 迷你个人博客网站

零第三方依赖的个人博客，纯 JDK 实现（HTTP 服务用 `com.sun.net.httpserver`），
不需要 Maven、不需要下载任何 jar，装好 JDK 8+ 就能跑。

## 功能

- 首页文章列表（标题 / 日期 / 摘要）
- 文章详情页，支持简易 Markdown 渲染（标题、代码块、列表、引用、粗体、行内代码、链接）
- 在线写文章（表单提交，自动生成 id 与时间戳）
- 删除文章
- 数据持久化到 `data/posts.json`，重启不丢
- 简洁响应式样式，桌面 / 手机都能看

## 快速开始

Windows（双击或命令行）：

```bat
run.bat
```

macOS / Linux：

```sh
sh run.sh
```

然后浏览器打开 <http://localhost:8080>。

## 手动运行

```sh
# 编译（输出到 out/ 目录）
javac -encoding UTF-8 -d out src/com/minblog/*.java

# 运行
java -Dfile.encoding=UTF-8 -cp out com.minblog.Main
```

## 配置

- 端口：环境变量 `PORT`，默认 `8080`，例如 `PORT=9000 java -cp out com.minblog.Main`
- 数据文件：`data/posts.json`（首次运行自动创建）
- 静态资源：`web/` 目录，可自行修改 `style.css`

## 目录结构

```
min-blog/
├── src/com/minblog/
│   ├── Main.java               # 入口
│   ├── BlogServer.java         # HTTP 路由与页面渲染
│   ├── Post.java               # 文章模型
│   ├── PostStore.java          # JSON 文件持久化
│   ├── SimpleJson.java         # 极简 JSON 解析/生成器
│   └── MarkdownRenderer.java   # 简易 Markdown → HTML
├── web/style.css               # 页面样式
├── data/posts.json             # 运行时生成
├── run.bat                     # Windows 一键运行
├── run.sh                      # macOS/Linux 一键运行
└── README.md
```
