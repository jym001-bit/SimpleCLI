package com.minblog;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MinBlog entry point.
 * Usage: java -Dfile.encoding=UTF-8 -cp out com.minblog.Main
 * Port via env PORT, default 8080.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        int port = defaultPort();
        Path dataFile = Paths.get("data", "posts.json");
        Path webDir = Paths.get("web");

        PostStore store = new PostStore(dataFile);
        store.load();

        BlogServer server = new BlogServer(port, store, webDir, "MinBlog");
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        System.out.println("========================================");
        System.out.println("  MinBlog started");
        System.out.println("  URL: http://localhost:" + server.port());
        System.out.println("  Data: " + dataFile.toAbsolutePath().normalize());
        System.out.println("  Press Ctrl+C to stop");
        System.out.println("========================================");
    }

    private static int defaultPort() {
        String raw = System.getenv("PORT");
        if (raw != null && raw.trim().length() > 0) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                // invalid PORT, use default
            }
        }
        return 8080;
    }
}
