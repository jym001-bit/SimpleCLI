package com.minblog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 博客文章模型。
 * id 为短随机字符串；createdAt / updatedAt 为 epoch 毫秒。
 */
public final class Post {

    public final String id;
    public final String title;
    public final String content;
    public final long createdAt;
    public final long updatedAt;

    public Post(String id, String title, String content, long createdAt, long updatedAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 纯文本摘要（去掉换行，截断）。 */
    public String summary(int maxLen) {
        String plain = content.replaceAll("(?s)[#*`>\\[\\]()!-]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (plain.length() <= maxLen) {
            return plain;
        }
        return plain.substring(0, maxLen) + "…";
    }

    /** 格式化日期：yyyy-MM-dd HH:mm。 */
    public String formattedTime() {
        java.time.format.DateTimeFormatter f =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return java.time.Instant.ofEpochMilli(createdAt)
                .atZone(java.time.ZoneId.systemDefault())
                .format(f);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("content", content);
        m.put("createdAt", createdAt);
        m.put("updatedAt", updatedAt);
        return m;
    }

    public static Post fromMap(Map<String, Object> m) {
        try {
            String id = (String) m.get("id");
            String title = (String) m.get("title");
            String content = (String) m.get("content");
            long createdAt = ((Number) m.get("createdAt")).longValue();
            long updatedAt = ((Number) m.get("updatedAt")).longValue();
            if (id == null || title == null || content == null) {
                return null;
            }
            return new Post(id, title, content, createdAt, updatedAt);
        } catch (Exception e) {
            return null;
        }
    }
}
