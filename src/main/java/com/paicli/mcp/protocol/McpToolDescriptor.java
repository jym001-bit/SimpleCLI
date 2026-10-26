package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @param inputSchema      清洗后给模型看的参数 Schema
 * @param validationSchema server 返回的原始 Schema，执行前按它校验参数（清洗会去掉 null 选项、把复杂的 anyOf / oneOf 放宽，不能拿来判对错）
 */
public record McpToolDescriptor(
        String serverName,
        String name,
        String namespacedName,
        String description,
        JsonNode inputSchema,
        JsonNode validationSchema
) {
    /** PaiCLI 自己定义的虚拟工具：给模型看的和校验用的是同一份 Schema。 */
    public McpToolDescriptor(String serverName, String name, String namespacedName, String description,
                             JsonNode inputSchema) {
        this(serverName, name, namespacedName, description, inputSchema, inputSchema);
    }

    public static String namespaced(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }
}
