package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRedactorTest {

    @Test
    @Timeout(5)
    void largeBatchMetadataDoesNotRescanEverySuffixAndLongCredentialNamesStillRedact() {
        String metadata = "{\"candidateJarSha256\":\"" + "f".repeat(64)
                + "\",\"maxObservedTotalTokens\":16384,\"workerDispatchFinished\":true}";
        String input = metadata.repeat(4096);
        assertEquals(input, SecretRedactor.redact(input));
        String longName = "PROJECT_".repeat(1000) + "API_KEY";
        assertEquals(longName + "=" + SecretRedactor.REDACTED,
                SecretRedactor.redact(longName + "=private-secret-value"));
    }

    @Test
    void redactsBearerKeysJwtAndBase64Payloads() {
        String bearer = "bearer-token-value-123456";
        String apiKey = "deepseek-secret-value-123456";
        String jsonKey = "json-secret-value-123456";
        String openAiStyle = "sk-abcdefghijklmnopqrstuvwxyz123456";
        String jwt = "eyJabcdefghijk.abcdefghijklmnop.abcdefghijklmnop";
        String image = "A".repeat(160);
        String input = """
                Authorization: Bearer %s
                DEEPSEEK_API_KEY=%s
                {"apiKey":"%s","imageBase64":"%s"}
                data:image/png;base64,%s
                standalone=%s
                jwt=%s
                inputTokens=42
                """.formatted(bearer, apiKey, jsonKey, image, image, openAiStyle, jwt);

        String redacted = SecretRedactor.redact(input);

        assertFalse(redacted.contains(bearer));
        assertFalse(redacted.contains(apiKey));
        assertFalse(redacted.contains(jsonKey));
        assertFalse(redacted.contains(image));
        assertFalse(redacted.contains(openAiStyle));
        assertFalse(redacted.contains(jwt));
        assertTrue(redacted.contains("Authorization:" + SecretRedactor.REDACTED), redacted);
        assertTrue(redacted.contains("DEEPSEEK_API_KEY=" + SecretRedactor.REDACTED));
        assertTrue(redacted.contains("standalone=" + SecretRedactor.REDACTED));
        assertTrue(redacted.contains("inputTokens=42"));
    }

    @Test
    void keepsOrdinaryTextAndNullStable() {
        assertEquals("model=deepseek-v4-flash tokens=128",
                SecretRedactor.redact("model=deepseek-v4-flash tokens=128"));
        assertEquals("", SecretRedactor.redact(""));
        assertNull(SecretRedactor.redact(null));
    }

    @Test
    void redactsGenericCredentialsHeadersPemCloudKeysAndUrlPasswords() {
        String password = "correct horse battery staple";
        String cookie = "session=private-cookie-value; HttpOnly";
        String basic = "Basic dXNlcjpwYXNzd29yZA==";
        String github = "ghp_abcdefghijklmnopqrstuvwxyz123456";
        String aws = "AKIAABCDEFGHIJKLMNOP";
        String pem = """
                -----BEGIN PRIVATE KEY-----
                cHJpdmF0ZS1rZXktY2FuYXJ5
                -----END PRIVATE KEY-----
                """;
        String input = """
                PASSWORD="%s"
                refresh_token=refresh-token-canary
                Cookie: %s
                Authorization: %s
                github=%s
                aws=%s
                database=https://alice:database-password@example.test/db
                pem=%s
                inputTokens=42
                """.formatted(password, cookie, basic, github, aws, pem);

        String redacted = SecretRedactor.redact(input);

        for (String secret : new String[]{password, cookie, basic, github, aws,
                "alice:database-password", "cHJpdmF0ZS1rZXktY2FuYXJ5", "refresh-token-canary"}) {
            assertFalse(redacted.contains(secret), secret);
        }
        assertTrue(redacted.contains("PASSWORD=\"" + SecretRedactor.REDACTED + "\""));
        assertTrue(redacted.contains("Cookie: " + SecretRedactor.REDACTED));
        assertTrue(redacted.contains("Authorization: " + SecretRedactor.REDACTED));
        assertTrue(redacted.contains("inputTokens=42"));
    }
}
