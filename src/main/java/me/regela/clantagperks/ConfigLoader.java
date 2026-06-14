package me.regela.clantagperks;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads and validates config.yml using SnakeYAML (bundled with Velocity). */
public final class ConfigLoader {

    private ConfigLoader() {}

    /** Copies the default config on first run, then parses + validates it. */
    public static Config load(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("config.yml");
        if (Files.notExists(file)) {
            try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) throw new IOException("bundled config.yml missing from jar");
                Files.copy(in, file);
            }
        }

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(file)) {
            Object parsed = new Yaml().load(in);
            if (!(parsed instanceof Map)) throw new IOException("config.yml is empty or malformed");
            //noinspection unchecked
            root = (Map<String, Object>) parsed;
        }

        Map<String, Object> discord = section(root, "discord");
        Map<String, Object> lp = section(root, "luckperms");
        Map<String, Object> link = section(root, "link");
        Map<String, Object> manual = section(link, "manual");
        Map<String, Object> limbo = section(link, "limboauth");
        Map<String, Object> logging = section(root, "logging");

        Map<String, String> manualMap = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : manual.entrySet()) {
            manualMap.put(e.getKey(), String.valueOf(e.getValue()));
        }

        Config cfg = new Config(
                str(discord, "bot-token", ""),
                str(discord, "member-guild-id", ""),
                str(discord, "tag-guild-id", ""),
                intv(discord, "poll-interval-seconds", 120),
                str(discord, "api-base", "https://discord.com/api/v10"),
                str(discord, "log-channel-id", ""),
                intv(discord, "request-timeout-seconds", 15),
                str(lp, "group", "clantag"),
                str(lp, "mode", "expiry"),
                intv(lp, "temp-ttl-seconds", 600),
                intv(lp, "renew-before-seconds", 180),
                str(link, "source", "manual"),
                Collections.unmodifiableMap(manualMap),
                str(limbo, "jdbc-url", ""),
                str(limbo, "username", ""),
                str(limbo, "password", ""),
                str(limbo, "query", ""),
                intv(limbo, "cache-refresh-seconds", 1800),
                bool(logging, "debug", false));

        validate(cfg);
        return cfg;
    }

    private static void validate(Config c) {
        if (c.botToken.isBlank()) throw new IllegalStateException("discord.bot-token is required");
        if (c.memberGuildId.isBlank())
            throw new IllegalStateException("discord.member-guild-id is required (the guild the bot is in)");
        if (c.tagGuildId.isBlank())
            throw new IllegalStateException("discord.tag-guild-id is required (the Server Tag to reward)");
        if (c.tempTtlSeconds <= c.pollIntervalSeconds)
            throw new IllegalStateException("luckperms.temp-ttl-seconds (" + c.tempTtlSeconds
                    + ") must be greater than discord.poll-interval-seconds (" + c.pollIntervalSeconds + ")");
        if (c.renewBeforeSeconds >= c.tempTtlSeconds)
            throw new IllegalStateException("luckperms.renew-before-seconds must be less than temp-ttl-seconds");
        if (!c.mode.equals("expiry") && !c.mode.equals("explicit"))
            throw new IllegalStateException("luckperms.mode must be 'expiry' or 'explicit'");
        if (!c.linkSource.equals("manual") && !c.linkSource.equals("limboauth"))
            throw new IllegalStateException("link.source must be 'manual' or 'limboauth'");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        Object v = parent == null ? null : parent.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Collections.emptyMap();
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    private static int intv(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return def;
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }
}
