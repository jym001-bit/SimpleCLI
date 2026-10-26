package com.paicli.cli;

import com.paicli.config.PaiCliConfig;
import com.paicli.llm.*;
import com.paicli.util.AnsiStyle;
import com.paicli.util.TerminalTable;
import java.io.IOException;
import java.util.*;

final class ModelCommands {
    private ModelCommands() {}

    @FunctionalInterface
    interface ModelLookup {
        List<String> discover(LlmClient client) throws IOException;
    }

    static String handle(String payload, PaiCliConfig config, LlmClient current) throws IOException {
        return handle(payload, config, current, new ModelDiscovery()::discover);
    }

    static String handle(String payload, PaiCliConfig config, LlmClient current, ModelLookup discovery) throws IOException {
        String[] args = payload.trim().split("\\s+");
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (args.length > 2) throw new IllegalArgumentException("用法: /model list [provider]");
                yield list(config, current, args.length == 2 ? args[1] : null);
            }
            case "info" -> info(config, current, args);
            case "refresh" -> {
                if (args.length != 2) throw new IllegalArgumentException("用法: /model refresh <provider>");
                String provider = args[1].toLowerCase(Locale.ROOT);
                ModelCatalog.requireProvider(provider);
                LlmClient client = LlmClientFactory.create(provider, config);
                if (client == null) throw new IllegalArgumentException("未配置 " + provider + " 的 API Key");
                List<String> discovered = discovery.discover(client);
                int count = ModelCatalog.mergeDiscovered(config, provider, discovered);
                long aliases = discovered.stream().filter(id -> ModelCatalog.aliasTarget(provider, id) != null).count();
                String message = AnsiStyle.section("✓ " + providerName(provider) + " 模型列表已更新")
                        + "\n  返回 " + discovered.size() + " 个 · 新增 " + count + " 个 · 当前选择未变";
                if (aliases > 0) message += "\n  兼容旧名 " + aliases + " 个，不计入新增模型";
                if (count > 0) message += "\n  新模型按 128k 文本登记，能力待配置：/model add";
                String currentModel = current != null && provider.equals(current.getProviderName())
                        ? current.getModelName() : config.getModel(provider);
                yield TerminalTable.wrap(message + aliasHint(provider, currentModel), TerminalTable.columns());
            }
            case "add" -> add(config, args);
            default -> throw new IllegalArgumentException(usage());
        };
    }

    private static String list(PaiCliConfig config, LlmClient current, String onlyProvider) {
        if (onlyProvider != null) ModelCatalog.requireProvider(onlyProvider);
        int columns = TerminalTable.columns();
        StringBuilder out = new StringBuilder();
        for (String provider : onlyProvider == null ? ModelCatalog.BUILTINS.keySet() : List.of(onlyProvider)) {
            var entries = ModelCatalog.entries(config, provider);
            if (current != null && provider.equals(current.getProviderName()))
                entries.putIfAbsent(current.getModelName(), null);
            long aliases = entries.keySet().stream().filter(id -> ModelCatalog.aliasTarget(provider, id) != null).count();
            String title = providerName(provider) + " · " + (entries.size() - aliases) + " 个模型"
                    + (aliases == 0 ? "" : "，" + aliases + " 个兼容旧名");
            out.append(TerminalTable.wrap(AnsiStyle.emphasis(title), columns)).append("\n\n");
            List<List<String>> rows = new ArrayList<>();
            for (var entry : entries.entrySet()) {
                String model = entry.getKey();
                ModelProfile profile = entry.getValue();
                LlmClient preview = ModelCatalog.preview(config, provider, model);
                boolean selected = current != null && current.getProviderName().equals(provider) && current.getModelName().equals(model);
                String status = ModelCatalog.aliasTarget(provider, model) != null ? "兼容旧名"
                        : profile != null && "discovered".equals(profile.source()) ? "待配置"
                        : profile == null ? "可用" : "自定义";
                String name = (selected ? "● " : "  ") + model;
                if (selected) name = AnsiStyle.section(name);
                if (status.equals("兼容旧名") || status.equals("待配置")) status = AnsiStyle.codeLabel(status);
                rows.add(List.of(name, compactTokens(preview.maxContextWindow()),
                        preview.supportsImageInput() ? "文本·图片" : "文本", status));
            }
            out.append(TerminalTable.render(List.of("  模型", "上下文", "输入", "状态"), rows, columns)).append("\n\n");
        }
        boolean currentVisible = current != null && (onlyProvider == null || onlyProvider.equals(current.getProviderName()));
        if (currentVisible) out.append(AnsiStyle.subtle("● 当前选择")).append('\n');
        String hint = !currentVisible ? "" : aliasHint(current.getProviderName(), current.getModelName());
        if (!hint.isEmpty()) out.append(TerminalTable.wrap(hint.stripLeading(), columns));
        else out.append(TerminalTable.wrap("切换：/model <模型ID>\n详情：/model info <模型ID>", columns));
        return out.toString().stripTrailing();
    }

    static String aliasHint(String provider, String model) {
        String target = ModelCatalog.aliasTarget(provider, model);
        return target == null ? "" : "\n旧名映射：" + model + " → " + target
                + "\n使用推荐名称：/model " + target;
    }

    static String switchSummary(LlmClient client) {
        return TerminalTable.wrap(AnsiStyle.section("✓ 已切换到 " + client.getModelName())
                + "\n  " + providerName(client.getProviderName()) + " · 上下文 " + compactTokens(client.maxContextWindow())
                + " · 对话已保留" + aliasHint(client.getProviderName(), client.getModelName()), TerminalTable.columns());
    }

    static String compactTokens(int tokens) {
        if (tokens >= 1_000_000 && tokens % 100_000 == 0)
            return java.math.BigDecimal.valueOf(tokens, 6).stripTrailingZeros().toPlainString() + "M";
        if (tokens >= 1_000 && tokens % 100 == 0)
            return java.math.BigDecimal.valueOf(tokens, 3).stripTrailingZeros().toPlainString() + "k";
        return Integer.toString(tokens);
    }

    private static String providerName(String provider) {
        return "deepseek".equals(provider) ? "DeepSeek" : provider;
    }

    private static String info(PaiCliConfig config, LlmClient current, String[] args) {
        if (args.length > 2 || (args.length == 1 && current == null))
            throw new IllegalArgumentException("用法: /model info <模型ID>");
        Main.ModelSelection selection = args.length == 1
                ? new Main.ModelSelection(current.getProviderName(), current.getModelName(), true)
                : Main.resolveModelSelection(args[1], config);
        String provider = selection.provider();
        ModelCatalog.requireProvider(provider);
        String requested = selection.model() == null ? config.getModel(provider) : selection.model();
        LlmClient preview = ModelCatalog.preview(config, provider, requested);
        String model = preview.getModelName();
        var settings = config.getProviders().get(provider);
        ModelProfile profile = settings == null ? null : settings.getModels().get(model);
        StringBuilder out = new StringBuilder(AnsiStyle.emphasis(model)).append("\n\n")
                .append("供应商：").append(providerName(provider))
                .append("\n上下文：").append(compactTokens(preview.maxContextWindow()))
                .append("（").append(preview.maxContextWindow()).append(" tokens）")
                .append("\n输入：").append(preview.supportsImageInput() ? "文本·图片" : "文本");
        if (profile != null) {
            out.append("\n配置来源：").append("discovered".equals(profile.source()) ? "发现，能力待配置" : "手动配置");
            if (profile.template() != null) out.append("\n能力模板：").append(profile.template());
            if (profile.thinking() != null) out.append("\n思考模式：").append(profile.thinking());
            if (profile.reasoningEffort() != null) out.append("\n推理强度：").append(profile.reasoningEffort());
            if (profile.maxOutputTokens() != null) out.append("\n输出上限：").append(profile.maxOutputTokens());
            if (profile.reasoningHistory() != null) out.append("\n保留思考历史：").append(profile.reasoningHistory());
            if (profile.dsml() != null) out.append("\nDSML 兼容：").append(profile.dsml());
        }
        out.append(aliasHint(provider, model));
        return TerminalTable.wrap(out.toString(), TerminalTable.columns());
    }

    private static String add(PaiCliConfig config, String[] args) throws IOException {
        if (args.length < 3) throw new IllegalArgumentException(usage());
        String provider = args[1].toLowerCase(Locale.ROOT);
        String model = args[2];
        ModelCatalog.requireProvider(provider);
        if (!ModelCatalog.validModelId(model)) throw new IllegalArgumentException("模型 ID 无效");
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> allowed = Set.of("like", "context", "vision", "thinking", "effort", "reasoning-history", "dsml", "max-output");
        for (int i = 3; i < args.length; i++) {
            String key = args[i];
            if (!key.startsWith("--")) throw new IllegalArgumentException("选项必须以 -- 开头");
            key = key.substring(2);
            int eq = key.indexOf('=');
            String value;
            if (eq >= 0) { value = key.substring(eq + 1); key = key.substring(0, eq); }
            else {
                if (++i >= args.length) throw new IllegalArgumentException("缺少 --" + key + " 的值");
                value = args[i];
            }
            if (!allowed.contains(key)) throw new IllegalArgumentException("未知选项: --" + key);
            if (options.putIfAbsent(key, value) != null) throw new IllegalArgumentException("重复选项: --" + key);
        }
        Map<String, ModelProfile> entries = ModelCatalog.entries(config, provider);
        ModelProfile base = entries.get(model);
        String template = base == null ? null : base.template();
        if (options.containsKey("like")) {
            String like = options.get("like");
            if (!entries.containsKey(like) && ModelCatalog.aliasTarget(provider, like) == null)
                throw new IllegalArgumentException("找不到同供应商的模板模型: " + like);
            base = entries.get(like);
            template = base != null && base.template() != null ? base.template() : like;
        }
        if (base == null) base = entries.containsKey(model) || template != null || ModelCatalog.aliasTarget(provider, model) != null
                ? new ModelProfile(null, null, null, null, null, null, null, null, "manual") : ModelProfile.discovered();
        Integer context = integer(options, "context", base.contextWindow());
        Boolean vision = bool(options, "vision", base.imageInput());
        String thinking = options.containsKey("thinking") ? auto(options.get("thinking")) : base.thinking();
        String effort = options.containsKey("effort") ? auto(options.get("effort")) : base.reasoningEffort();
        if ("disabled".equals(thinking) && !options.containsKey("effort")) effort = null;
        Boolean history = bool(options, "reasoning-history", base.reasoningHistory());
        Boolean dsml = bool(options, "dsml", base.dsml());
        if (Boolean.TRUE.equals(dsml) && !provider.equals("deepseek"))
            throw new IllegalArgumentException("DSML 兼容目前仅支持 deepseek");
        ModelProfile profile = new ModelProfile(template, context, vision, thinking, effort, history, dsml,
                integer(options, "max-output", base.maxOutputTokens()), "manual");
        ModelCatalog.saveProfile(config, provider, model, profile);
        return "已保存 " + provider + "/" + model + "，当前会话模型未切换。\n"
                + "使用: /model " + provider + "/" + model;
    }

    private static String auto(String value) { return "auto".equals(value) ? null : value; }
    private static Integer integer(Map<String, String> options, String key, Integer fallback) {
        if (!options.containsKey(key)) return fallback;
        try { return Integer.valueOf(options.get(key)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 必须是整数"); }
    }
    private static Boolean bool(Map<String, String> options, String key, Boolean fallback) {
        if (!options.containsKey(key)) return fallback;
        String value = options.get(key);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key + " 必须是 true 或 false");
        return Boolean.valueOf(value);
    }

    static LlmClient switchModel(PaiCliConfig config, Main.ModelSelection target) throws IOException {
        LlmClient client = LlmClientFactory.create(target.provider(), target.model(), config);
        if (client == null) throw new IllegalArgumentException("未配置 " + target.provider() + " 的 API Key 或不支持该供应商");
        PaiCliConfig.ProviderConfig previous = config.getProviders().get(target.provider());
        String previousModel = previous == null ? null : previous.getModel();
        String previousDefault = config.getDefaultProvider();
        if (target.explicitModel()) {
            config.getProviders().computeIfAbsent(target.provider(), ignored -> new PaiCliConfig.ProviderConfig()).setModel(target.model());
        }
        config.setDefaultProvider(target.provider());
        try { config.saveOrThrow(); }
        catch (IOException | RuntimeException e) {
            config.setDefaultProvider(previousDefault);
            if (previous == null) config.getProviders().remove(target.provider());
            else previous.setModel(previousModel);
            throw e;
        }
        return client;
    }

    static String usage() {
        return """
                用法:
                  /model list [provider]
                  /model info [模型ID]
                  /model refresh <provider>
                  /model add <provider> <模型ID> [--like <已有模型ID>]
                    [--context <tokens>] [--vision true|false] [--max-output <tokens>]
                    [--thinking auto|enabled|disabled] [--effort auto|low|medium|high|max]
                    [--reasoning-history true|false] [--dsml true|false]
                  /model <provider>/<模型ID>
                未指定模板的新模型默认按 128k 文本处理；auto 沿用供应商/模板设置。
                添加和刷新不会切换模型，也不会执行推理测试。
                """.stripTrailing();
    }
}
