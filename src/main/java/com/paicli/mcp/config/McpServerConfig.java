package com.paicli.mcp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerConfig {
    private String command;
    private List<String> args = new ArrayList<>();
    private Map<String, String> env = new LinkedHashMap<>();
    private String url;
    private Map<String, String> headers = new LinkedHashMap<>();
    private boolean disabled;
    // 来源只能由 McpConfigLoader 标记；不用 getter/setter 命名，避免配置文件里写同名字段把自己伪装成用户级
    private Path projectConfigFile;
    private String projectFingerprint;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args == null ? new ArrayList<>() : args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env == null ? new LinkedHashMap<>() : env;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? new LinkedHashMap<>() : headers;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isStdio() {
        return command != null && !command.isBlank();
    }

    public boolean isHttp() {
        return url != null && !url.isBlank();
    }

    public void markProjectScoped(Path configFile, String fingerprint) {
        this.projectConfigFile = configFile;
        this.projectFingerprint = fingerprint;
    }

    /** 来自项目目录 .paicli/mcp.json 的 server：随仓库分发，启动前要用户确认。 */
    public boolean projectScoped() {
        return projectConfigFile != null;
    }

    public Path projectConfigFile() {
        return projectConfigFile;
    }

    public String projectFingerprint() {
        return projectFingerprint;
    }

    public String transportName() {
        return isHttp() ? "http" : "stdio";
    }
}
