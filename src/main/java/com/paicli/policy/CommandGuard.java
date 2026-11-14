package com.paicli.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 命令快速拒绝：在 execute_command 进入 HITL 审批 / 真正调用 ProcessBuilder 之前的黑名单 fast-fail。
 *
 * 定位：辅助 HITL 而非主防线。黑名单是出名的反模式（永远列不全），但能拦住 LLM 容易踩的明显破坏性命令，
 * 减少 HITL 弹窗骚扰。真正的安全责任在 HITL 审批和用户判断。
 *
 * 设计取舍：
 * - 不做完整 shell 解析，只做正则/轻量分词匹配，够覆盖明显破坏性命令即可
 * - 命令替换段 $(...) 和反引号内的内容仍以原文存在，正则会一并扫描，不需要单独展开
 * - curl / git / 网络命令默认放行，只拦真正破坏性的（rm -rf 全盘、sudo、mkfs 等）
 * - PowerShell 规则只处理“递归/强制 + 危险目标”，项目内普通相对路径放行
 */
public final class CommandGuard {

    private static final List<DenyRule> RULES = List.of(
            new DenyRule("禁止 sudo 提权",
                    Pattern.compile("(?i)\\bsudo\\b")),
            // rm 路径黑名单：匹配开头即可拦截，不强求路径结束边界。
            // /、~、/*、$HOME 是常见的"灾难性删除起点"，包括其作为前缀的所有子路径都拦掉，避免 LLM 误删根目录或用户目录。
            new DenyRule("禁止 rm -rf 删除全盘或用户目录",
                    Pattern.compile("(?i)\\brm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+(/|~|\\$home)|" +
                            "\\brm\\s+-[a-z]*f[a-z]*r[a-z]*\\s+(/|~|\\$home)")),
            new DenyRule("禁止 mkfs 格式化磁盘",
                    Pattern.compile("(?i)\\bmkfs(\\.|\\b)")),
            new DenyRule("禁止 dd 写入裸设备",
                    Pattern.compile("(?i)\\bdd\\b[^\\n]*\\bof=/dev/")),
            new DenyRule("识别为 fork bomb",
                    Pattern.compile(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:")),
            new DenyRule("禁止 curl / wget 管道直接执行远端脚本",
                    Pattern.compile("(?i)\\b(curl|wget)\\b[^|\\n]*\\|\\s*(sh|bash|zsh|fish|ksh)\\b")),
            new DenyRule("不允许扫描 /、~ 或整个文件系统",
                    Pattern.compile("(?i)\\bfind\\s+(/|~|\\$home)")),
            new DenyRule("禁止 chmod 777 全盘",
                    Pattern.compile("(?i)\\bchmod\\s+-R\\s+777\\s+(/|~)")),
            new DenyRule("禁止 shutdown / reboot / halt",
                    Pattern.compile("(?i)\\b(shutdown|reboot|halt|poweroff)\\b"))
    );

    private static final Pattern ENCODED_COMMAND =
            Pattern.compile("(?i)(?:^|\\s)-encodedcommand(?:\\s|$)");
    private static final Pattern INVOKE_EXPRESSION =
            Pattern.compile("(?i)(?:^|[;&|]\\s*)(?:iex|invoke-expression)\\b");
    private static final Pattern NETWORK =
            Pattern.compile("(?i)\\b(iwr|invoke-webrequest|invoke-restmethod|downloadstring|downloadfile|curl|wget|start-bitstransfer)\\b|https?://");
    private static final Pattern DOWNLOAD =
            Pattern.compile("(?i)\\b(curl|wget|iwr|invoke-webrequest|invoke-restmethod|start-bitstransfer|downloadstring|downloadfile)\\b");
    private static final Pattern PIPE_EXECUTION =
            Pattern.compile("(?i)\\|\\s*(sh|bash|zsh|fish|ksh|powershell|pwsh|iex)\\b");
    private static final Pattern SEQUENCE_EXECUTION =
            Pattern.compile("(?i)(?:;|&&|\\|\\|)\\s*(sh|bash|zsh|fish|ksh|powershell|pwsh|iex)\\b");
    private static final Pattern OUTFILE_EXECUTION =
            Pattern.compile("(?i)(?:-outfile|-o)\\s+\\S+.*(?:;|&&|&)\\s*(?:start-process|invoke-item|iex|powershell|pwsh|sh|bash|\\.)");
    private static final Pattern DOWNLOAD_STRING_EXECUTION =
            Pattern.compile("(?i)(?:downloadstring|downloadfile).*(?:iex|invoke-expression|start-process)");

    private CommandGuard() {
    }

    /**
     * 校验命令是否安全。
     *
     * @return null 表示放行；非 null 字符串是拒绝原因，调用方包装成用户/LLM 可见的提示
     */
    public static String check(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = command.replaceAll("\\s+", " ").trim();

        for (DenyRule rule : RULES) {
            if (rule.pattern().matcher(normalized).find()) {
                return rule.reason();
            }
        }

        String reason = checkEncodedCommand(normalized);
        if (reason == null) {
            reason = checkInvokeExpressionWithNetwork(normalized);
        }
        if (reason == null) {
            reason = checkDownloadThenExecute(normalized);
        }
        if (reason == null) {
            reason = checkPowerShellAndCmdTargets(normalized, 0);
        }
        if (reason == null) {
            reason = checkRmTargets(normalized);
        }
        return reason;
    }

    /** R3：模型不需要 EncodedCommand；一律拒绝，避免编码内容绕过规则。 */
    private static String checkEncodedCommand(String command) {
        if (ENCODED_COMMAND.matcher(command).find()) {
            return "禁止 powershell -EncodedCommand（编码内容不可审计）";
        }
        return null;
    }

    /** R4：Invoke-Expression 只在与网络下载/EncodedCommand 同一条命令内组合时拒绝。 */
    private static String checkInvokeExpressionWithNetwork(String command) {
        if (INVOKE_EXPRESSION.matcher(command).find() && NETWORK.matcher(command).find()) {
            return "禁止 Invoke-Expression 与网络下载组合";
        }
        return null;
    }

    /** R5：单条命令内的“下载 + 执行”。单纯下载不拦。 */
    private static String checkDownloadThenExecute(String command) {
        if (!DOWNLOAD.matcher(command).find()) {
            return null;
        }
        if (PIPE_EXECUTION.matcher(command).find()
                || SEQUENCE_EXECUTION.matcher(command).find()
                || OUTFILE_EXECUTION.matcher(command).find()
                || DOWNLOAD_STRING_EXECUTION.matcher(command).find()) {
            return "禁止下载后直接执行远端内容";
        }
        return null;
    }

    /**
     * R1/R2：PowerShell Remove-Item/del/rd/ri/erase，以及 cmd /c rd|del。
     * 只有“递归/强制参数 + 危险目标”才拒绝；项目内普通相对路径放行。
     */
    private static String checkPowerShellAndCmdTargets(String command, int depth) {
        if (depth > 3) {
            return null;
        }
        for (String segment : splitSegments(command)) {
            List<String> tokens = splitArgs(segment);
            if (tokens.isEmpty()) {
                continue;
            }
            String first = tokens.get(0).toLowerCase(Locale.ROOT);
            if ("cmd".equals(first)) {
                int cIndex = indexOfToken(tokens, "/c", "/k");
                if (cIndex >= 0 && cIndex + 1 < tokens.size()) {
                    String reason = checkPowerShellAndCmdTargets(
                            joinTokens(tokens, cIndex + 1), depth + 1);
                    if (reason != null) {
                        return reason;
                    }
                }
                continue;
            }
            if ("powershell".equals(first) || "pwsh".equals(first)) {
                int commandIndex = indexOfToken(tokens, "-command", "-c");
                if (commandIndex >= 0 && commandIndex + 1 < tokens.size()) {
                    String reason = checkPowerShellAndCmdTargets(
                            joinTokens(tokens, commandIndex + 1), depth + 1);
                    if (reason != null) {
                        return reason;
                    }
                }
                continue;
            }
            if (!isPowerShellDelete(first)) {
                continue;
            }
            if (!hasRecursiveFlag(tokens) && !hasForceFlag(tokens)) {
                continue;
            }
            for (String target : targets(tokens)) {
                if (isDangerousPowerShellTarget(target)) {
                    return "禁止递归/强制删除盘符根、用户目录或变量目标: " + target;
                }
            }
        }
        return null;
    }

    private static boolean isPowerShellDelete(String token) {
        return "remove-item".equals(token)
                || "del".equals(token)
                || "rd".equals(token)
                || "ri".equals(token)
                || "erase".equals(token)
                || "rmdir".equals(token);
    }

    private static boolean hasRecursiveFlag(List<String> tokens) {
        for (String token : tokens) {
            String lower = token.toLowerCase(Locale.ROOT);
            if ("-recurse".equals(lower) || "-r".equals(lower)
                    || "--recursive".equals(lower) || "/s".equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasForceFlag(List<String> tokens) {
        for (String token : tokens) {
            String lower = token.toLowerCase(Locale.ROOT);
            if ("-force".equals(lower) || "-fo".equals(lower) || "-for".equals(lower)
                    || "-f".equals(lower) || "/f".equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> targets(List<String> tokens) {
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String lower = token.toLowerCase(Locale.ROOT);
            if ("-path".equals(lower) || "-literalpath".equals(lower) || "-filter".equals(lower)) {
                if (i + 1 < tokens.size()) {
                    targets.add(tokens.get(++i));
                }
                continue;
            }
            if (token.startsWith("-") || isCmdFlag(token)) {
                continue;
            }
            targets.add(token);
        }
        return targets;
    }

    private static boolean isCmdFlag(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return "/s".equals(lower) || "/q".equals(lower) || "/f".equals(lower)
                || "/p".equals(lower) || "/a".equals(lower);
    }

    private static boolean isDangerousPowerShellTarget(String target) {
        String raw = stripQuotes(target).toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return false;
        }
        if (raw.contains("..") || raw.contains("$") || raw.contains("%")) {
            return true;
        }
        if (raw.equals("~") || raw.startsWith("~\\") || raw.startsWith("~/")) {
            return true;
        }
        if (raw.equals("/") || raw.startsWith("/")) {
            return true;
        }
        String normalized = raw.replace('/', '\\');
        if (normalized.matches("[a-z]:\\\\?")) {
            return true;
        }
        if (normalized.matches("[a-z]:\\\\[^\\\\]+")) {
            return true;
        }
        return normalized.matches("[a-z]:\\\\users(\\\\.*)?");
    }

    /**
     * R6：rm 的分离参数、长参数、引号、${HOME}、变量目标。
     * 保留现有 /、~、$HOME 前缀拦截（包括 /tmp）。
     */
    private static String checkRmTargets(String command) {
        for (String segment : splitSegments(command)) {
            List<String> tokens = splitArgs(segment);
            if (tokens.isEmpty() || !"rm".equals(tokens.get(0).toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean recursive = false;
            boolean force = false;
            List<String> targets = new ArrayList<>();
            for (int i = 1; i < tokens.size(); i++) {
                String token = tokens.get(i);
                String lower = token.toLowerCase(Locale.ROOT);
                if ("--".equals(lower)) {
                    continue;
                }
                if (lower.startsWith("-")) {
                    if ("--recursive".equals(lower) || "-r".equals(lower)
                            || "-R".equals(lower) || lower.matches("-[a-z]*r[a-z]*")) {
                        recursive = true;
                    }
                    if ("--force".equals(lower) || "-f".equals(lower)
                            || lower.matches("-[a-z]*f[a-z]*")) {
                        force = true;
                    }
                    continue;
                }
                targets.add(token);
            }
            if (!recursive || !force) {
                continue;
            }
            for (String target : targets) {
                if (isDangerousRmTarget(target)) {
                    return "禁止 rm -r/-f 删除盘符根、用户目录或变量目标: " + target;
                }
            }
        }
        return null;
    }

    private static boolean isDangerousRmTarget(String target) {
        String raw = stripQuotes(target).toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return false;
        }
        if (raw.contains("..") || raw.contains("$") || raw.contains("%")) {
            return true;
        }
        if (raw.startsWith("/") || raw.equals("~") || raw.startsWith("~\\") || raw.startsWith("~/")) {
            return true;
        }
        String normalized = raw.replace('/', '\\');
        return normalized.matches("[a-z]:\\\\?");
    }

    private static List<String> splitSegments(String command) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == ';' || c == '|') {
                if (c == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') {
                    i++;
                }
                segments.add(current.toString());
                current.setLength(0);
            } else if (c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') {
                i++;
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        segments.add(current.toString());
        return segments;
    }

    private static List<String> splitArgs(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static int indexOfToken(List<String> tokens, String... candidates) {
        for (int i = 0; i < tokens.size(); i++) {
            String lower = tokens.get(i).toLowerCase(Locale.ROOT);
            for (String candidate : candidates) {
                if (candidate.equals(lower)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String joinTokens(List<String> tokens, int from) {
        return String.join(" ", tokens.subList(from, tokens.size()));
    }

    private static String stripQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private record DenyRule(String reason, Pattern pattern) {
    }
}
