package me.regela.clantagperks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Config binding (Configurate) + business-rule validation. */
class ConfigLoaderTest {

    private static final String VALID = """
        discord:
          bot-token: "token"
          member-guild-id: "100"
          tag-guild-id: "200"
          poll-interval-seconds: 120
          request-timeout-seconds: 15
        luckperms:
          group: "clantag"
          mode: "explicit"
          temp-ttl-seconds: 600
          renew-before-seconds: 180
        link:
          source: "manual"
          manual:
            "123": "Steve"
        """;

    private Config write(Path dir, String yaml) throws IOException {
        Files.writeString(dir.resolve("config.yml"), yaml);
        return ConfigLoader.load(dir);
    }

    @Test
    void bindsNestedSectionsAndEnums(@TempDir Path dir) throws IOException {
        Config cfg = write(dir, VALID);

        assertEquals("token", cfg.discord.botToken);
        assertEquals(120, cfg.discord.pollIntervalSeconds);
        assertEquals(Mode.EXPLICIT, cfg.luckperms.mode);
        assertEquals(LinkSource.MANUAL, cfg.link.source);
        assertEquals("Steve", cfg.link.manual.get("123"));
    }

    @Test
    void appliesDefaultsForOmittedKeys(@TempDir Path dir) throws IOException {
        Config cfg = write(dir, VALID); // api-base / notifications / logging omitted

        assertEquals("https://discord.com/api/v10", cfg.discord.apiBase);
        assertEquals(false, cfg.notifications.enabled);
        assertEquals(false, cfg.logging.debug);
    }

    @Test
    void missingBotTokenIsRejected(@TempDir Path dir) {
        String yaml = VALID.replace("bot-token: \"token\"", "bot-token: \"\"");
        var ex = assertThrows(IllegalStateException.class, () -> write(dir, yaml));
        assertTrue(ex.getMessage().contains("bot-token"));
    }

    @Test
    void ttlMustExceedPollInterval(@TempDir Path dir) {
        String yaml = VALID.replace("temp-ttl-seconds: 600", "temp-ttl-seconds: 60");
        var ex = assertThrows(IllegalStateException.class, () -> write(dir, yaml));
        assertTrue(ex.getMessage().contains("temp-ttl-seconds"));
    }

    @Test
    void nonPositivePollIntervalIsRejected(@TempDir Path dir) {
        String yaml = VALID.replace("poll-interval-seconds: 120", "poll-interval-seconds: 0");
        assertThrows(IllegalStateException.class, () -> write(dir, yaml));
    }

    @Test
    void limboauthSourceRequiresJdbcUrlAndQuery(@TempDir Path dir) {
        String yaml = """
            discord:
              bot-token: "t"
              member-guild-id: "1"
              tag-guild-id: "2"
              poll-interval-seconds: 120
              request-timeout-seconds: 15
            luckperms:
              temp-ttl-seconds: 600
              renew-before-seconds: 180
            link:
              source: "limboauth"
            """;
        var ex = assertThrows(IllegalStateException.class, () -> write(dir, yaml));
        assertTrue(ex.getMessage().contains("limboauth"));
    }
}
