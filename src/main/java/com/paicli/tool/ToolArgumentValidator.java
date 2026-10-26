package com.paicli.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.SpecVersionDetector;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具执行前统一检查参数：先确认是合法的 JSON 对象，再按注册时的 JSON Schema 校验，
 * 不合格时给模型返回逐个字段的错误说明，让它改了参数再调。
 *
 * <p>几个有意的宽松处理：
 * <ul>
 *   <li>开启 typeLoose，{@code "top_k": "5"} 这种字符串数字照样算整数，避免弱模型因为引号反复重试</li>
 *   <li>值为 null 的顶层参数校验时当作没传；交给工具的参数保持原样</li>
 *   <li>内置工具的参数本来就以字符串交给工具、由工具自己解析并在解析失败时用默认值（{@code " +1 "}、
 *       阿拉伯-印度数字都能被 parseInt 接受），所以单值参数之间不卡类型，只拦缺参数、传了对象 / 数组、
 *       不在可选值里这类结构问题。评测的独立复算按这套原有语义还原 read_file 看到的内容，不能改</li>
 *   <li>Schema 加载不了（引用了外部 $ref、写法不规范）就跳过校验并记日志，不能让工具因此整个用不了</li>
 * </ul>
 */
final class ToolArgumentValidator {

    private static final Logger log = LoggerFactory.getLogger(ToolArgumentValidator.class);
    private static final int MAX_ECHO_CHARS = 200;
    private static final int MAX_REPORTED_ERRORS = 5;
    private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder()
            .typeLoose(true)
            .formatAssertionsEnabled(false)
            .locale(Locale.SIMPLIFIED_CHINESE)
            .build();

    private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "number", "boolean");

    private final ObjectMapper mapper;
    private final Map<String, Compiled> cache = new ConcurrentHashMap<>();

    /** schema 为 null 表示这份 Schema 没法用来校验，直接放行。 */
    private record Compiled(JsonNode source, JsonSchema schema) {
    }

    /** 检查结果：通过时 {@code error} 为 null，{@code arguments} 是解析出的原始参数对象。 */
    record Outcome(ObjectNode arguments, String error) {
        boolean passed() {
            return error == null;
        }
    }

    ToolArgumentValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param builtIn true 表示 PaiCLI 内置工具：单值参数之间不卡类型，见类注释
     */
    Outcome check(String toolName, JsonNode schemaNode, String argumentsJson, boolean builtIn) {
        ObjectNode arguments;
        if (argumentsJson == null || argumentsJson.isBlank()) {
            arguments = mapper.createObjectNode();
        } else {
            JsonNode parsed;
            try {
                parsed = mapper.readTree(argumentsJson);
            } catch (JsonProcessingException e) {
                return new Outcome(null, "❌ 工具 " + toolName + " 的参数不是合法的 JSON：" + e.getOriginalMessage()
                        + "。收到的参数开头：" + abbreviate(argumentsJson) + "。请重新生成完整的 JSON 对象参数");
            }
            if (parsed == null || parsed.isMissingNode()) {
                arguments = mapper.createObjectNode();
            } else if (parsed instanceof ObjectNode object) {
                arguments = object;
            } else {
                return new Outcome(null, "❌ 工具 " + toolName + " 的参数必须是 JSON 对象（{...}），收到的是 "
                        + typeName(parsed) + "：" + abbreviate(argumentsJson));
            }
        }
        JsonSchema schema = compile(toolName, schemaNode, builtIn);
        if (schema == null) {
            return new Outcome(arguments, null);
        }
        Set<ValidationMessage> messages = schema.validate(withoutNullFields(arguments));
        if (messages.isEmpty()) {
            return new Outcome(arguments, null);
        }
        List<String> problems = new ArrayList<>();
        for (ValidationMessage message : messages) {
            if (problems.size() == MAX_REPORTED_ERRORS) {
                problems.add("……另有 " + (messages.size() - MAX_REPORTED_ERRORS) + " 处问题");
                break;
            }
            problems.add(describe(message));
        }
        return new Outcome(null, "❌ 工具 " + toolName + " 的参数不符合要求：\n- "
                + String.join("\n- ", problems) + "\n请按工具定义修正参数后重试");
    }

    private ObjectNode withoutNullFields(ObjectNode arguments) {
        boolean hasNull = false;
        for (JsonNode value : arguments) {
            if (value.isNull()) {
                hasNull = true;
                break;
            }
        }
        if (!hasNull) {
            return arguments;
        }
        ObjectNode copy = arguments.deepCopy();
        Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
        while (fields.hasNext()) {
            if (fields.next().getValue().isNull()) {
                fields.remove();
            }
        }
        return copy;
    }

    private JsonSchema compile(String toolName, JsonNode schemaNode, boolean builtIn) {
        if (schemaNode == null || !schemaNode.isObject()) {
            return null;
        }
        Compiled cached = cache.get(toolName);
        // 同名工具重新注册（MCP 的 tools/list_changed、server 重启）时 Schema 对象会换，按引用判断要不要重新编译
        if (cached != null && cached.source() == schemaNode) {
            return cached.schema();
        }
        JsonSchema schema = null;
        if (hasExternalRef(schemaNode)) {
            log.info("工具 {} 的参数 Schema 引用了外部 $ref，跳过校验", toolName);
        } else {
            try {
                ObjectNode copy = builtIn ? relaxScalarTypes(schemaNode) : schemaNode.deepCopy();
                Optional<SpecVersion.VersionFlag> version = SpecVersionDetector.detectOptionalVersion(copy, false);
                if (version.isEmpty()) {
                    // 不认识的 $schema 地址会被当成要联网加载的元 Schema，去掉后按 2020-12 处理
                    copy.remove("$schema");
                }
                schema = JsonSchemaFactory.getInstance(version.orElse(SpecVersion.VersionFlag.V202012))
                        .getSchema(copy, CONFIG);
            } catch (RuntimeException e) {
                log.warn("工具 {} 的参数 Schema 无法加载，跳过校验: {}", toolName, e.getMessage());
            }
        }
        cache.put(toolName, new Compiled(schemaNode, schema));
        return schema;
    }

    /** 内置工具的顶层单值参数（字符串 / 数字 / 布尔）互相之间都接受，enum 照常检查。 */
    private static ObjectNode relaxScalarTypes(JsonNode schemaNode) {
        ObjectNode copy = schemaNode.deepCopy();
        if (copy.path("properties") instanceof ObjectNode properties) {
            for (JsonNode property : properties) {
                if (property instanceof ObjectNode prop && SCALAR_TYPES.contains(prop.path("type").asText())) {
                    prop.putArray("type").add("string").add("number").add("boolean");
                }
            }
        }
        return copy;
    }

    private static boolean hasExternalRef(JsonNode node) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && !ref.asText().startsWith("#")) {
                return true;
            }
        }
        for (JsonNode child : node) {
            if (hasExternalRef(child)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(ValidationMessage message) {
        String location = paramPath(message.getInstanceLocation().toString());
        Object[] args = message.getArguments();
        switch (message.getType()) {
            case "required" -> {
                String property = message.getProperty();
                return "缺少必填参数 " + (location.isEmpty() ? property : location + "." + property);
            }
            case "type" -> {
                if (args != null && args.length >= 2) {
                    return "参数 " + location + " 应该是" + expectedTypes(String.valueOf(args[1]))
                            + "，收到的是" + typeLabel(String.valueOf(args[0]));
                }
            }
            case "enum" -> {
                if (args != null && args.length >= 1) {
                    return "参数 " + location + " 只能取 " + args[0] + " 其中之一，收到的是 "
                            + abbreviate(String.valueOf(message.getInstanceNode()));
                }
            }
            default -> {
            }
        }
        return location.isEmpty() ? message.getError() : "参数 " + location + "：" + message.getError();
    }

    /** JSON Pointer（/opts/depth）转成模型好读的 opts.depth。 */
    private static String paramPath(String pointer) {
        if (pointer == null || pointer.isEmpty() || "$".equals(pointer)) {
            return "";
        }
        String path = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        return path.replace('/', '.');
    }

    /** 期望类型可能是单个（integer）也可能是一组（[string, number, boolean]）。 */
    private static String expectedTypes(String expected) {
        String[] types = expected.replaceAll("[\\[\\]\"\\s]", "").split(",");
        List<String> labels = new ArrayList<>();
        for (String type : types) {
            labels.add(typeLabel(type));
        }
        return String.join("或", labels);
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "string" -> "字符串";
            case "integer" -> "整数";
            case "number" -> "数字";
            case "boolean" -> "布尔值（true / false）";
            case "object" -> "对象";
            case "array" -> "数组";
            case "null" -> " null";
            default -> type;
        };
    }

    private static String typeName(JsonNode node) {
        return switch (node.getNodeType()) {
            case ARRAY -> "数组";
            case STRING -> "字符串";
            case NUMBER -> "数字";
            case BOOLEAN -> "布尔值";
            case NULL -> "null";
            default -> node.getNodeType().name().toLowerCase(Locale.ROOT);
        };
    }

    private static String abbreviate(String text) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= MAX_ECHO_CHARS ? oneLine : oneLine.substring(0, MAX_ECHO_CHARS) + "...";
    }
}
