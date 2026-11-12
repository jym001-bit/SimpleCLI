package com.paicli.cli;

import com.paicli.config.PaiCliConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenCodeGoSelectionTest {
    @Test void selectsGoAndPreservesOfficialProShortcut() {
        PaiCliConfig config = new PaiCliConfig();
        assertEquals("opencode-go", Main.resolveModelSelection("opencode-go", config).provider());
        var pro = Main.resolveModelSelection("opencode-go/deepseek-v4-pro", config);
        assertEquals("opencode-go", pro.provider());
        assertEquals("deepseek-v4-pro", pro.model());
        assertEquals("deepseek", Main.resolveModelSelection("deepseek-v4-pro", config).provider());
    }
}
