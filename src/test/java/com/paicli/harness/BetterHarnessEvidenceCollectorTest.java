package com.paicli.harness;

import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetterHarnessEvidenceCollectorTest {

    @Test
    void collectsBoundedEvidenceWithoutRawConversationContent(@TempDir Path tempDir)
            throws Exception {
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Rules\nRun tests.");
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.createDirectories(tempDir.resolve("src/test/java/example"));
        Files.writeString(tempDir.resolve("src/test/java/example/AppTest.java"), "class AppTest {}");

        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "session-test");
        ledger.appendMessage("react", "agent", "user_input",
                LlmClient.Message.user("SECRET_USER_PROMPT"));
        ledger.appendMessage("react", "agent", "llm_response",
                LlmClient.Message.assistant(
                        null,
                        "",
                        List.of(new LlmClient.ToolCall(
                                "call-1",
                                new LlmClient.ToolCall.Function(
                                        "read_file",
                                        "{\"path\":\"SECRET_PATH\"}")))));

        BetterHarnessEvidenceCollector.EvidenceBundle bundle =
                new BetterHarnessEvidenceCollector(tempDir, ledger, null)
                        .collect(BetterHarnessOptions.Depth.NORMAL);

        assertTrue(bundle.sessionEvidence().contains("\"read_file\""));
        assertTrue(bundle.sessionEvidence().contains("\"entryCount\" : 2"));
        assertFalse(bundle.sessionEvidence().contains("SECRET_USER_PROMPT"));
        assertFalse(bundle.sessionEvidence().contains("SECRET_PATH"));
        assertTrue(bundle.projectHarness().contains("\"testFiles\" : 1"));
        assertTrue(bundle.projectHarness().contains("Run tests."));
        assertTrue(bundle.agentCustomize().contains("\"secretValuesIncluded\" : false"));
    }
}
