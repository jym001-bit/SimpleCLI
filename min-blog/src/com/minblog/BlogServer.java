package com.minblog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 博客 HTTP 服务与页面渲染。
 *
 * 路由：
 *   GET  /                 首页（文章列表）
 *   GET  /post/{id}        文章详情
 *   GET  /new              新建文章表单
 *   POST /new              提交新文章
 *   POST /delete/{id}      删除文章
 *   GET  /static/...       静态资源（web/ 目录）
 */
public final class BlogServer {

    private final PostStore store;
    private final Path webDir;
    private final String blogName;
    private final HttpServer server;

    public BlogServer(int port, PostStore store, Path webDir, String blogName) throws IOException {
        this.store = store;
        this.webDir = webDir;
        this.blogName = blogName;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::dispatch);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ---------------- 路由 ----------------

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            if (path.equals("/") || path.equals("/index.html")) {
                home(ex);
            } else if (path.equals("/new")) {
                if (method.equalsIgnoreCase("GET")) {
                    newPostForm(ex);
                } else if (method.equalsIgnoreCase("POST")) {
                    submitPost(ex);
                } else {
                    methodNotAllowed(ex);
                }
            } else if (path.startsWith("/post/")) {
                postDetail(ex, path.substring("/post/".length()));
            } else if (path.startsWith("/delete/")) {
                if (method.equalsIgnoreCase("POST")) {
                    deletePost(ex, path.substring("/delete/".length()));
                } else {
                    methodNotAllowed(ex);
                }
            } else if (path.startsWith("/static/")) {
                staticFile(ex, path.substring("/static/".length()));
            } else {
                notFound(ex);
            }
        } finally {
            ex.close();
        }
    }

    // ---------------- 页面 ----------------

    private void home(HttpExchange ex) throws IOException {
        List<Post> posts = store.all();
        StringBuilder cards = new StringBuilder();
        if (posts.isEmpty()) {
            cards.append("<div class=\"empty\">还没有文章，写下第一篇吧 🖊️</div>");
        }
        for (Post p : posts) {
            cards.append("<article class=\"card\">")
                    .append("<h2><a href=\"/post/").append(p.id).append("\">")
                    .append(MarkdownRenderer.escape(p.title)).append("</a></h2>")
                    .append("<div class=\"meta\">").append(p.formattedTime()).append("</div>")
                    .append("<p class=\"summary\">").append(MarkdownRenderer.escape(p.summary(120))).append("</p>")
                    .append("</article>\n");
        }
        send(ex, 200, page(blogName,
                "<div class=\"toolbar\"><h1>" + MarkdownRenderer.escape(blogName) + "</h1>"
                        + "<a class=\"btn\" href=\"/new\">✍️ 写文章</a></div>"
                        + cards));
    }

    private void postDetail(HttpExchange ex, String id) throws IOException {
        Post p = store.byId(id);
        if (p == null) {
            notFound(ex);
            return;
        }
        String body = "<a class=\"back\" href=\"/\">← 返回首页</a>"
                + "<article class=\"card post\">"
                + "<h1>" + MarkdownRenderer.escape(p.title) + "</h1>"
                + "<div class=\"meta\">" + p.formattedTime() + "</div>"
                + "<div class=\"content\">" + MarkdownRenderer.render(p.content) + "</div>"
                + "</article>"
                + "<form method=\"post\" action=\"/delete/" + p.id + "\" class=\"inline-form\" "
                + "onsubmit=\"return confirm('确定删除这篇文章吗？');\">"
                + "<button type=\"submit\" class=\"btn danger\">🗑️ 删除</button></form>";
        send(ex, 200, page(p.title + " · " + blogName, body));
    }

    private void newPostForm(HttpExchange ex) throws IOException {
        String body = "<a class=\"back\" href=\"/\">← 返回首页</a>"
                + "<article class=\"card\">"
                + "<h1>写新文章</h1>"
                + "<form method=\"post\" action=\"/new\">"
                + "<label>标题</label>"
                + "<input type=\"text\" name=\"title\" required maxlength=\"120\" placeholder=\"文章标题\">"
                + "<label>内容（支持 Markdown）</label>"
                + "<textarea name=\"content\" rows=\"18\" required placeholder=\"支持 # 标题、``` 代码块、- 列表、> 引用、**加粗**、[链接](url)\"></textarea>"
                + "<button type=\"submit\" class=\"btn\">发布</button>"
                + "</form></article>";
        send(ex, 200, page("写文章 · " + blogName, body));
    }

    private void submitPost(HttpExchange ex) throws IOException {
        Map<String, String> form = readForm(ex);
        String title = form.getOrDefault("title", "").trim();
        String content = form.getOrDefault("content", "").trim();
        if (title.isEmpty() || content.isEmpty()) {
            redirect(ex, "/new?error=empty");
            return;
        }
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        store.add(new Post(id, title, content, now, now));
        store.save();
        redirect(ex, "/post/" + id);
    }

    private void deletePost(HttpExchange ex, String id) throws IOException {
        store.delete(id);
        store.save();
        redirect(ex, "/");
    }

    private void staticFile(HttpExchange ex, String name) throws IOException {
        // 防目录穿越：只允许普通文件名
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            notFound(ex);
            return;
        }
        Path file = webDir.resolve(name).normalize();
        if (!Files.isRegularFile(file)) {
            notFound(ex);
            return;
        }
        byte[] data = Files.readAllBytes(file);
        String type = name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : name.endsWith(".png") ? "image/png"
                : name.endsWith(".svg") ? "image/svg+xml"
                : "application/octet-stream";
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private void notFound(HttpExchange ex) throws IOException {
        send(ex, 404, page("404 · " + blogName,
                "<h1>404</h1><p>页面不存在。</p><a class=\"back\" href=\"/\">← 返回首页</a>"));
    }

    private void methodNotAllowed(HttpExchange ex) throws IOException {
        send(ex, 405, page("405 · " + blogName, "<p>方法不允许。</p>"));
    }

    // ---------------- 工具 ----------------

    private String page(String title, String body) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + MarkdownRenderer.escape(title) + "</title>"
                + "<link rel=\"stylesheet\" href=\"/static/style.css\">"
                + "</head><body><div class=\"container\">"
                + body
                + "<footer>Powered by MinBlog · 纯 JDK 零依赖</footer>"
                + "</div></body></html>";
    }

    private void send(HttpExchange ex, int code, String html) throws IOException {
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
    }

    /** 读取 application/x-www-form-urlencoded 请求体（兼容 JDK 8，不用 readAllBytes）。 */
    private Map<String, String> readForm(HttpExchange ex) throws IOException {
        Map<String, String> map = new HashMap<>();
        byte[] body = readAll(ex.getRequestBody());
        String raw = new String(body, StandardCharsets.UTF_8);
        if (raw.isEmpty()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                continue;
            }
            String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
