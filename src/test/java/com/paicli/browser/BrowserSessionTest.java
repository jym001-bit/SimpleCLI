package com.paicli.browser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionTest {

    @Test
    void startsInIsolatedMode() {
        BrowserSession session = new BrowserSession();

        assertEquals(BrowserMode.ISOLATED, session.mode());
        assertNull(session.browserUrl());
        assertTrue(session.agentOpenedTabs().isEmpty());
    }

    @Test
    void switchToSharedResetsNavigationAndTabs() {
        BrowserSession session = new BrowserSession();
        session.rememberNavigation("https://example.com");
        session.recordOpenedTab("page-1");

        session.switchToShared("http://127.0.0.1:9222");

        assertEquals(BrowserMode.SHARED, session.mode());
        assertEquals("http://127.0.0.1:9222", session.browserUrl());
        assertNull(session.lastNavigatedUrl());
        assertTrue(session.agentOpenedTabs().isEmpty());
    }

    @Test
    void switchToIsolatedClearsSharedState() {
        BrowserSession session = new BrowserSession();
        session.switchToShared("http://127.0.0.1:9222");
        session.rememberNavigation("https://example.com");
        session.recordOpenedTab("page-1");

        session.switchToIsolated();

        assertEquals(BrowserMode.ISOLATED, session.mode());
        assertNull(session.browserUrl());
        assertNull(session.lastNavigatedUrl());
        assertTrue(session.agentOpenedTabs().isEmpty());
    }

    @Test
    void recordsAgentOpenedTabs() {
        BrowserSession session = new BrowserSession();

        session.recordOpenedTab("page-1");

        assertTrue(session.isAgentOpenedTab("page-1"));
        assertEquals("page-1", session.currentPageId());
        assertTrue(session.hasAgentOwnedCurrentPage());
        assertFalse(session.isAgentOpenedTab("page-2"));
    }

    @Test
    void selectingOrForgettingTabsKeepsCurrentOwnershipAccurate() {
        BrowserSession session = new BrowserSession();
        session.recordOpenedTab("page-1");
        session.recordOpenedTab("page-2");

        session.selectTab("page-1");
        assertEquals("page-1", session.currentPageId());

        session.forgetTab("page-1");
        assertNull(session.currentPageId());
        assertFalse(session.hasAgentOwnedCurrentPage());
        assertTrue(session.isAgentOpenedTab("page-2"));
    }

    @Test
    void returnedTabSetCannotMutateSession() {
        BrowserSession session = new BrowserSession();
        session.recordOpenedTab("page-1");

        assertThrows(UnsupportedOperationException.class, () -> session.agentOpenedTabs().add("page-2"));
        assertFalse(session.isAgentOpenedTab("page-2"));
    }
}
