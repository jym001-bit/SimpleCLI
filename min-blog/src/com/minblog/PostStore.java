package com.minblog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文章持久化存储：全部文章存为一个 JSON 数组，写入 data/posts.json。
 * 线程安全（方法加锁），重启后自动加载。
 */
public final class PostStore {

    private final Path file;
    private final List<Post> posts = new ArrayList<>();
    private final Map<String, Post> byId = new LinkedHashMap<>();

    public PostStore(Path file) {
        this.file = file;
    }

    public synchronized void load() throws IOException {
        posts.clear();
        byId.clear();
        if (!Files.exists(file)) {
            return;
        }
        Object root = SimpleJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(root instanceof List)) {
            return;
        }
        for (Object o : (List<?>) root) {
            if (o instanceof Map) {
                Post p = Post.fromMap((Map<String, Object>) o);
                if (p != null) {
                    posts.add(p);
                    byId.put(p.id, p);
                }
            }
        }
        posts.sort(Comparator.comparingLong((Post p) -> p.createdAt).reversed());
    }

    public synchronized void save() throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Post p : posts) {
            list.add(p.toMap());
        }
        Files.writeString(file, SimpleJson.stringify(list), StandardCharsets.UTF_8);
    }

    public synchronized List<Post> all() {
        return new ArrayList<>(posts);
    }

    public synchronized Post byId(String id) {
        return byId.get(id);
    }

    public synchronized void add(Post p) {
        posts.add(0, p);
        byId.put(p.id, p);
    }

    public synchronized boolean delete(String id) {
        Post removed = byId.remove(id);
        if (removed == null) {
            return false;
        }
        posts.remove(removed);
        return true;
    }
}
