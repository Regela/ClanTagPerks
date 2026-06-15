package me.regela.clantagperks;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the Gson-mapped Discord member parsing (no HTTP involved). */
class DiscordPollerTest {

    private static final String TAG = "111111111111111111";

    @Test
    void wearerWithMatchingTagIsCollected() {
        String body = """
            [
              {"user":{"id":"1","primary_guild":{"identity_enabled":true,"identity_guild_id":"111111111111111111"}}},
              {"user":{"id":"2","primary_guild":{"identity_enabled":true,"identity_guild_id":"999999999999999999"}}}
            ]""";
        Set<String> out = new HashSet<>();
        DiscordPoller.Page page = DiscordPoller.parseMembersPage(body, TAG, out);

        assertEquals(2, page.count());
        assertEquals("2", page.lastId());
        assertEquals(Set.of("1"), out, "only the member wearing OUR tag should be collected");
    }

    @Test
    void tagDisabledOrNullPrimaryGuildIsNotAWearer() {
        String body = """
            [
              {"user":{"id":"1","primary_guild":{"identity_enabled":false,"identity_guild_id":"111111111111111111"}}},
              {"user":{"id":"2","primary_guild":null}},
              {"user":{"id":"3"}}
            ]""";
        Set<String> out = new HashSet<>();
        DiscordPoller.Page page = DiscordPoller.parseMembersPage(body, TAG, out);

        assertEquals(3, page.count());
        assertEquals("3", page.lastId());
        assertTrue(out.isEmpty(), "disabled / null / missing primary_guild must not count as wearing");
    }

    @Test
    void emptyPageReportsZeroAndNullCursor() {
        Set<String> out = new HashSet<>();
        DiscordPoller.Page page = DiscordPoller.parseMembersPage("[]", TAG, out);

        assertEquals(0, page.count());
        assertNull(page.lastId());
        assertTrue(out.isEmpty());
    }

    @Test
    void lastIdTracksPaginationCursor() {
        String body = """
            [
              {"user":{"id":"100"}},
              {"user":{"id":"205"}},
              {"user":{"id":"3000"}}
            ]""";
        Set<String> out = new HashSet<>();
        DiscordPoller.Page page = DiscordPoller.parseMembersPage(body, TAG, out);

        assertEquals("3000", page.lastId(), "cursor must be the last member's id for the next page");
    }
}
