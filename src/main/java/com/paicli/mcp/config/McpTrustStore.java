package com.paicli.mcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 项目级 MCP server 的确认记录（{@code ~/.paicli/mcp-trust.json}）。
 *
 * <p>按“项目配置文件绝对路径 + server 名 + 配置内容指纹”记录：仓库更新改了命令、参数、env、
 * url 或 headers 任何一项，指纹就变，需要重新确认。文件不存在或解析失败一律视为没有确认过。
 */
public final class McpTrustStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TRUSTED = "trustedProjectServers";

    private final Path file;

    public McpTrustStore(Path file) {
        this.file = file;
    }

    public static McpTrustStore defaultStore() {
        return new McpTrustStore(Path.of(System.getProperty("user.home"), ".paicli", "mcp-trust.json"));
    }

    public synchronized boolean isTrusted(Path configFile, String serverName, String fingerprint) {
        if (configFile == null || serverName == null || fingerprint == null) {
            return false;
        }
        String path = normalize(configFile);
        for (JsonNode entry : readRoot().path(TRUSTED)) {
            if (path.equals(entry.path("config").asText())
                    && serverName.equals(entry.path("name").asText())
                    && fingerprint.equals(entry.path("sha256").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 同一项目同名 server 只保留最新一次确认的指纹。 */
    public synchronized void trust(Path configFile, String serverName, String fingerprint) throws IOException {
        String path = normalize(configFile);
        ObjectNode root = readRoot();
        ArrayNode next = MAPPER.createArrayNode();
        for (JsonNode entry : root.path(TRUSTED)) {
            if (!(path.equals(entry.path("config").asText()) && serverName.equals(entry.path("name").asText()))) {
                next.add(entry);
            }
        }
        next.addObject().put("config", path).put("name", serverName).put("sha256", fingerprint);
        root.set(TRUSTED, next);
        write(root);
    }

    private static String normalize(Path configFile) {
        return configFile.toAbsolutePath().normalize().toString();
    }

    private ObjectNode readRoot() {
        if (file == null || !Files.exists(file)) {
            return MAPPER.createObjectNode();
        }
        try {
            String content = Files.readString(file);
            if (content.isBlank()) {
                return MAPPER.createObjectNode();
            }
            JsonNode root = MAPPER.readTree(content);
            return root instanceof ObjectNode object ? object : MAPPER.createObjectNode();
        } catch (Exception e) {
            System.err.println("⚠️ mcp-trust.json 解析失败，项目级 MCP server 需要重新确认: " + e.getMessage());
            return MAPPER.createObjectNode();
        }
    }

    private void write(ObjectNode root) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
