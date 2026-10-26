package com.paicli.eval.benchmark;

import java.util.List;
import java.util.Objects;

/** Host-validated request attribution, not task success. Version 1 is PLAN, version 2 is TEAM. */
public record ScopedRequestFingerprints(int schemaVersion, String mode, List<Request> requests) {
    public ScopedRequestFingerprints {
        if (!(schemaVersion == 1 && "PLAN".equals(mode)
                || schemaVersion == 2 && "TEAM".equals(mode)))
            throw new IllegalArgumentException("invalid fingerprint mode");
        requests = List.copyOf(requests);
        if (requests.size() > 4096) throw new IllegalArgumentException("too many scoped requests");
    }
    public record Binding(String scope, String originSha256, String inputSha256) {
        public Binding {
            if (scope == null || !scope.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
                throw new IllegalArgumentException("invalid request scope");
            hash(originSha256); hash(inputSha256);
        }
    }
    public record Request(int ordinal, Binding binding, String systemPromptSha256, String toolSchemaSha256) {
        public Request {
            if (ordinal < 1 || ordinal > 4096) throw new IllegalArgumentException("invalid scoped request ordinal");
            Objects.requireNonNull(binding); hash(systemPromptSha256); hash(toolSchemaSha256);
        }
    }
    public boolean completeFor(int calls) {
        if (calls < 1 || requests.size() != calls) return false;
        var scopes = new java.util.HashMap<String, String>();
        var origins = new java.util.HashMap<String, String>();
        for (int i = 0; i < requests.size(); i++) {
            var request = requests.get(i);
            if (request.ordinal() != i + 1) return false;
            if ("TEAM".equals(mode) && !request.binding().scope().matches(
                    "team:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return false;
            if ("PLAN".equals(mode) && request.binding().scope().startsWith("team:")) return false;
            String previous = scopes.putIfAbsent(request.binding().scope(), request.systemPromptSha256());
            String origin = origins.putIfAbsent(request.binding().scope(), request.binding().originSha256());
            if (previous != null && !previous.equals(request.systemPromptSha256())
                    || origin != null && !origin.equals(request.binding().originSha256())) return false;
        }
        return true;
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid fingerprint hash");
    }
}
