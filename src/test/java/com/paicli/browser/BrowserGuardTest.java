package com.paicli.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BrowserGuardTest {

    @Test
    void closePageAllowsAgentOpenedTabInSharedMode(@TempDir Path tempDir) {
        BrowserSession session = sharedSession();
        session.recordOpenedTab("page-1");
        BrowserGuard guard = guard(session, tempDir);

        BrowserCheckResult result = guard.check("mcp__chrome-devtools__close_page", "{\"pageIdx\":\"page-1\"}", true);

        assertFalse(result.blocked());
    }

    @Test
    void closePageBlocksNonAgentTabInSharedMode(@TempDir Path tempDir) {
        BrowserGuard guard = guard(sharedSession(), tempDir);

        BrowserCheckResult result = guard.check("mcp__chrome-devtools__close_page", "{\"pageIdx\":\"page-9\"}", true);

        assertTrue(result.blocked());
        assertTrue(result.reason().contains("拒绝关闭"));
    }

    @Test
    void closePageAllowsInIsolatedMode(@TempDir Path tempDir) {
        BrowserGuard guard = guard(new BrowserSession(), tempDir);

        BrowserCheckResult result = guard.check("mcp__chrome-devtools__close_page", "{\"pageIdx\":\"page-9\"}", true);

        assertFalse(result.blocked());
    }

    @Test
    void sensitiveWriteToolRequiresPerCallApproval(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "*://example.com/admin/*\n");
        BrowserSession session = sharedSession();
        session.recordOpenedTab("page-1");
        session.rememberNavigation("https://example.com/admin/users");
        BrowserGuard guard = new BrowserGuard(session, new SensitivePagePolicy(rules));

        BrowserCheckResult result = guard.check("mcp__chrome-devtools__click", "{\"uid\":\"1\"}", true);

        assertTrue(result.requiresPerCallApproval());
        assertTrue(result.sensitiveNotice().contains("example.com"));
    }

    @Test
    void sensitiveReadToolDoesNotRequirePerCallApproval(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "*://example.com/admin/*\n");
        BrowserSession session = sharedSession();
        session.recordOpenedTab("page-1");
        session.rememberNavigation("https://example.com/admin/users");
        BrowserGuard guard = new BrowserGuard(session, new SensitivePagePolicy(rules));

        BrowserCheckResult result = guard.check("mcp__chrome-devtools__take_snapshot", "{}", true);

        assertFalse(result.requiresPerCallApproval());
        assertFalse(result.blocked());
    }

    @Test
    void navigateSensitiveUrlRequiresApprovalOnlyForWriteToolLater(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "*://example.com/admin/*\n");
        BrowserSession session = sharedSession();
        session.recordOpenedTab("page-1");
        BrowserGuard guard = new BrowserGuard(session, new SensitivePagePolicy(rules));

        BrowserCheckResult result = guard.check("mcp__chrome-devtools__navigate_page",
                "{\"url\":\"https://example.com/admin/users\"}", true);

        assertFalse(result.requiresPerCallApproval());
        assertTrue(result.metadata().sensitive());
    }

    @Test
    void applyAfterExecutionRecordsNavigation(@TempDir Path tempDir) {
        BrowserSession session = sharedSession();
        BrowserGuard guard = guard(session, tempDir);

        guard.applyAfterExecution("mcp__chrome-devtools__navigate_page",
                "{\"url\":\"https://example.com\"}", "ok");

        assertEquals("https://example.com", session.lastNavigatedUrl());
    }

    @Test
    void applyAfterExecutionRecordsNewPageIdFromResult(@TempDir Path tempDir) {
        BrowserSession session = sharedSession();
        BrowserGuard guard = guard(session, tempDir);

        guard.applyAfterExecution("mcp__chrome-devtools__new_page",
                "{\"url\":\"https://example.com\"}", "created page-7");

        assertTrue(session.isAgentOpenedTab("page-7"));
    }

    @Test
    void failedNavigationDoesNotEstablishSessionState(@TempDir Path tempDir) {
        BrowserSession session = sharedSession();
        BrowserGuard guard = guard(session, tempDir);

        guard.applyAfterExecution("mcp__chrome-devtools__new_page",
                "{\"url\":\"https://example.com\"}",
                "# Pages\n7: https://private.example [selected]", false);

        assertNull(session.lastNavigatedUrl());
        assertFalse(session.hasAgentOwnedCurrentPage());
    }

    @Test
    void navigationReceiptDoesNotLeakOtherSharedTabs(@TempDir Path tempDir) {
        BrowserGuard guard = guard(sharedSession(), tempDir);

        String safe = guard.sanitizeResult("mcp__chrome-devtools__new_page",
                "{\"url\":\"https://example.com\"}",
                "# Pages\n1: https://mail.example/private\n2: https://example.com [selected]", true);

        assertTrue(safe.contains("https://example.com"));
        assertTrue(safe.contains("page 2"));
        assertFalse(safe.contains("mail.example"));
        assertFalse(safe.contains("# Pages"));
    }

    @Test
    void sharedNonOwnedPageBlocksNavigationAndWritesButAllowsExplicitReadPath(@TempDir Path tempDir) {
        BrowserGuard guard = guard(sharedSession(), tempDir);

        assertTrue(guard.check("mcp__chrome-devtools__navigate_page",
                "{\"url\":\"https://example.com\"}", false).blocked());
        assertTrue(guard.check("mcp__chrome-devtools__click", "{\"uid\":\"1\"}", false).blocked());
        assertFalse(guard.check("mcp__chrome-devtools__take_snapshot", "{}", false).blocked());
    }

    @Test
    void nonChromeToolIsIgnored(@TempDir Path tempDir) {
        BrowserGuard guard = guard(sharedSession(), tempDir);

        BrowserCheckResult result = guard.check("mcp__other__click", "{}", true);

        assertFalse(result.blocked());
        assertNull(result.metadata());
    }

    @Test
    void malformedJsonDoesNotThrow(@TempDir Path tempDir) {
        BrowserSession session = sharedSession();
        session.recordOpenedTab("page-1");
        BrowserGuard guard = guard(session, tempDir);

        BrowserCheckResult result = guard.check("mcp__chrome-devtools__click", "not-json", true);

        assertFalse(result.blocked());
    }

    private static BrowserSession sharedSession() {
        BrowserSession session = new BrowserSession();
        session.switchToShared("http://127.0.0.1:9222");
        return session;
    }

    private static BrowserGuard guard(BrowserSession session, Path tempDir) {
        return new BrowserGuard(session, new SensitivePagePolicy(tempDir.resolve("missing.txt")));
    }
}
