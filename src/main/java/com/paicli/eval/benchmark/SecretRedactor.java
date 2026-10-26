package com.paicli.eval.benchmark;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative text redaction for benchmark traces and public artifacts. */
public final class SecretRedactor {
    public static final String REDACTED = "[REDACTED]";

    // Match a field once from its lexical boundary, not each of its overlapping suffixes.
    // The unbounded prefix also keeps long environment-variable names protected.
    private static final String SECRET_FIELD_NAME =
            "(?<![A-Z0-9_.-])[A-Z0-9_.-]*(?:API[_-]?KEY|ACCESS[_-]?KEY|TOKEN|AUTH|CLIENT[_-]?SECRET|"
                    + "SECRET(?:[_-]?ACCESS)?[_-]?KEY|PRIVATE[_-]?KEY|PASSWORD|PASSWD|COOKIE|"
                    + "CREDENTIALS?|PRIVATE[_-]?ENDPOINT)";

    private static final Pattern DATA_IMAGE = Pattern.compile(
            "(?i)(data:image/[a-z0-9.+-]+;base64,)([a-z0-9+/=]{32,})");
    private static final Pattern BASE64_FIELD = Pattern.compile(
            "(?i)(\\\"(?:imageBase64|image_base64|base64)\\\"\\s*:\\s*\\\")"
                    + "([a-z0-9+/=]{32,})(\\\")");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(\\bbearer\\s+)([a-z0-9._~+/=-]{8,})");
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
            "(?im)(^\\s*(?:proxy-)?authorization\\s*:\\s*)(?!bearer\\s+)([^\\r\\n]+)");
    private static final Pattern COOKIE_HEADER = Pattern.compile(
            "(?im)(^\\s*(?:set-)?cookie\\s*:\\s*)([^\\r\\n]+)");
    private static final Pattern SECRET_QUOTED_ASSIGNMENT = Pattern.compile(
            "(?i)((?:\\\"?" + SECRET_FIELD_NAME + "\\\"?)\\s*[:=]\\s*([\\\"']))"
                    + "([^\\\"'\\r\\n]*)([\\\"'])");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)((?:\\\"?" + SECRET_FIELD_NAME + "\\\"?)\\s*[:=]\\s*\\\"?)"
                    + "([^\\\"\\s,;}]+)(\\\"?)");
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile(
            "(?<![A-Za-z0-9_-])(sk-[A-Za-z0-9_-]{12,})(?![A-Za-z0-9_-])");
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])(eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})"
                    + "(?![A-Za-z0-9_-])");
    private static final Pattern KNOWN_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Za-z0-9_-])"
                    + "|(?<![A-Za-z0-9_-])github_pat_[A-Za-z0-9_]{20,}(?![A-Za-z0-9_-])"
                    + "|(?<![A-Za-z0-9_-])gh[pousr]_[A-Za-z0-9]{20,}(?![A-Za-z0-9_-])"
                    + "|(?<![A-Za-z0-9_-])xox[a-z]-[A-Za-z0-9-]{16,}(?![A-Za-z0-9_-])"
                    + "|(?<![A-Za-z0-9_-])AIza[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])");
    private static final Pattern PRIVATE_KEY_PEM = Pattern.compile(
            "(?is)-----BEGIN [^-\\r\\n]*PRIVATE KEY-----.*?-----END [^-\\r\\n]*PRIVATE KEY-----");
    private static final Pattern URL_CREDENTIALS = Pattern.compile(
            "(?i)(?<=://)[^\\s/@:]+:[^\\s/@]+(?=@)");
    private static final Pattern LONG_BASE64 = Pattern.compile(
            "(?<![A-Za-z0-9+/=])([A-Za-z0-9+/]{128,}={0,2})(?![A-Za-z0-9+/=])");

    private SecretRedactor() {
    }

    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String redacted = replaceKeepingEdges(DATA_IMAGE, input);
        redacted = replaceKeepingEdges(BASE64_FIELD, redacted);
        redacted = replaceKeepingPrefix(BEARER, redacted);
        redacted = replaceKeepingPrefix(AUTHORIZATION_HEADER, redacted);
        redacted = replaceKeepingPrefix(COOKIE_HEADER, redacted);
        redacted = replaceKeepingOuterQuotes(SECRET_QUOTED_ASSIGNMENT, redacted);
        redacted = replaceKeepingEdges(SECRET_ASSIGNMENT, redacted);
        redacted = OPENAI_STYLE_KEY.matcher(redacted).replaceAll(REDACTED);
        redacted = JWT.matcher(redacted).replaceAll(REDACTED);
        redacted = KNOWN_TOKEN.matcher(redacted).replaceAll(REDACTED);
        redacted = PRIVATE_KEY_PEM.matcher(redacted).replaceAll(REDACTED);
        redacted = URL_CREDENTIALS.matcher(redacted).replaceAll(REDACTED);
        return LONG_BASE64.matcher(redacted).replaceAll(REDACTED);
    }

    private static String replaceKeepingPrefix(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(matcher.group(1) + REDACTED));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String replaceKeepingEdges(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String suffix = matcher.groupCount() >= 3 ? matcher.group(3) : "";
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(matcher.group(1) + REDACTED + suffix));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String replaceKeepingOuterQuotes(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(matcher.group(1) + REDACTED + matcher.group(4)));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
