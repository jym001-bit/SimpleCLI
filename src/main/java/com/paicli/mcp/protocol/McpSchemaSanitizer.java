package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class McpSchemaSanitizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DESCRIPTION_CHARS = 1000;
    private static final Set<String> SIMPLE_OPTION_FIELDS = Set.of("type", "title", "description");

    private McpSchemaSanitizer() {
    }

    public static JsonNode sanitize(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");
            fallback.putObject("properties");
            return fallback;
        }
        JsonNode copy = schema.deepCopy();
        JsonNode cleaned = clean(copy);
        if (!cleaned.isObject()) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");
            fallback.set("description", cleaned);
            return fallback;
        }
        ObjectNode obj = (ObjectNode) cleaned;
        if (!obj.has("type")) {
            obj.put("type", "object");
        }
        if (!obj.has("properties")) {
            obj.putObject("properties");
        }
        return obj;
    }

    private static JsonNode clean(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove("$schema");
            object.remove("$id");
            object.remove("$ref");

            for (String keyword : new String[]{"anyOf", "oneOf"}) {
                JsonNode union = object.remove(keyword);
                if (union != null && union.isArray()) {
                    collapseUnion(object, keyword, union);
                }
            }

            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode child = field.getValue();
                if ("description".equals(field.getKey()) && child.isTextual()) {
                    object.put("description", truncateDescription(child.asText()));
                } else {
                    clean(child);
                }
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (JsonNode child : array) {
                clean(child);
            }
        }
        return node;
    }

    /**
     * 把 anyOf / oneOf 收成模型容易理解的形式，同时尽量保留真实类型：
     * 去掉 null 选项后只剩一个选项就直接合并进来（Optional[X] 最常见）；
     * 剩下的都是 {"type": X} 这种简单选项就写成 type 数组；
     * 其余复杂情况不写 type（任何值都行），只在 description 里列出各选项。
     * 节点本身已经声明了 type 时不覆盖，选项同样只写进 description。
     */
    private static void collapseUnion(ObjectNode object, String keyword, JsonNode union) {
        List<JsonNode> nonNull = new ArrayList<>();
        for (JsonNode option : union) {
            if (!"null".equals(option.path("type").asText())) {
                nonNull.add(option);
            }
        }
        if (nonNull.isEmpty()) {
            return;
        }
        if (!object.has("type")) {
            if (nonNull.size() == 1 && nonNull.get(0).isObject()) {
                // 先清洗选项本身，合并进来的字段里就不会再带 $ref / anyOf
                mergeMissingFields(object, (ObjectNode) clean(nonNull.get(0)));
                return;
            }
            Set<String> simpleTypes = simpleTypes(nonNull);
            if (simpleTypes != null) {
                if (simpleTypes.size() == 1) {
                    object.put("type", simpleTypes.iterator().next());
                } else {
                    ArrayNode types = object.putArray("type");
                    simpleTypes.forEach(types::add);
                }
                return;
            }
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode option : nonNull) {
            labels.add(describeOption(option));
        }
        String alternatives = keyword + " options: " + String.join(", ", labels);
        String existing = object.path("description").asText("");
        object.put("description", truncateDescription(
                existing.isBlank() ? alternatives : existing + " (" + alternatives + ")"));
    }

    private static void mergeMissingFields(ObjectNode target, ObjectNode option) {
        option.fields().forEachRemaining(field -> {
            if (!target.has(field.getKey())) {
                target.set(field.getKey(), field.getValue());
            }
        });
    }

    /** 选项全是只带 type（可附 title / description）的简单写法时返回去重后的类型，否则返回 null。 */
    private static Set<String> simpleTypes(List<JsonNode> options) {
        Set<String> types = new LinkedHashSet<>();
        for (JsonNode option : options) {
            if (!option.isObject() || !option.path("type").isTextual()) {
                return null;
            }
            Iterator<String> names = option.fieldNames();
            while (names.hasNext()) {
                if (!SIMPLE_OPTION_FIELDS.contains(names.next())) {
                    return null;
                }
            }
            types.add(option.path("type").asText());
        }
        return types;
    }

    private static String describeOption(JsonNode option) {
        String type = option.path("type").isTextual() ? option.path("type").asText() : "any";
        JsonNode properties = option.path("properties");
        if (properties.isObject() && properties.size() > 0) {
            List<String> names = new ArrayList<>();
            properties.fieldNames().forEachRemaining(names::add);
            return type + "(" + String.join(", ", names) + ")";
        }
        return type;
    }

    private static String truncateDescription(String description) {
        if (description == null || description.length() <= MAX_DESCRIPTION_CHARS) {
            return description;
        }
        return description.substring(0, MAX_DESCRIPTION_CHARS) + "...";
    }
}
