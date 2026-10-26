package com.paicli.cli;

import com.paicli.cli.CliCommandParser.CommandType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainRunModeTest {

    @Test
    void explicitCommandInThisInputWins() {
        assertEquals("team", Main.resolveRunMode(CommandType.SWITCH_TEAM, true, false, true));
        assertEquals("plan", Main.resolveRunMode(CommandType.SWITCH_PLAN, false, true, false));
    }

    @Test
    void pendingOneShotFlagBeatsPlanSessionMode() {
        assertEquals("team", Main.resolveRunMode(CommandType.NONE, false, true, true));
        assertEquals("plan", Main.resolveRunMode(CommandType.NONE, true, false, false));
    }

    @Test
    void planSessionModeOnlyTakesOverOrdinaryInput() {
        assertEquals("plan", Main.resolveRunMode(CommandType.NONE, false, false, true));
        assertEquals("react", Main.resolveRunMode(CommandType.NONE, false, false, false));
    }
}
