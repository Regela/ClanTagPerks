package me.regela.clantagperks;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads config.yml via Configurate's typed {@code ObjectMapper}, then applies business-rule validation. */
public final class ConfigLoader {

    private ConfigLoader() {}

    /** Copies the default config on first run, binds it to {@link Config}, then validates it. */
    public static Config load(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("config.yml");
        if (Files.notExists(file)) {
            try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) throw new IOException("bundled config.yml missing from jar");
                Files.copy(in, file);
            }
        }

        Config cfg;
        try {
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(file).build();
            CommentedConfigurationNode root = loader.load();
            cfg = root.get(Config.class);
        } catch (ConfigurateException e) {
            throw new IOException("config.yml is malformed: " + e.getMessage(), e);
        }
        if (cfg == null) throw new IOException("config.yml is empty");

        validate(cfg);
        return cfg;
    }

    private static void validate(Config c) {
        if (c.discord.botToken.isBlank()) throw new IllegalStateException("discord.bot-token is required");
        if (c.discord.memberGuildId.isBlank())
            throw new IllegalStateException("discord.member-guild-id is required (the guild the bot is in)");
        if (c.discord.tagGuildId.isBlank())
            throw new IllegalStateException("discord.tag-guild-id is required (the Server Tag to reward)");
        if (c.discord.pollIntervalSeconds <= 0)
            throw new IllegalStateException("discord.poll-interval-seconds must be > 0");
        if (c.discord.requestTimeoutSeconds <= 0)
            throw new IllegalStateException("discord.request-timeout-seconds must be > 0");
        if (c.luckperms.tempTtlSeconds <= c.discord.pollIntervalSeconds)
            throw new IllegalStateException("luckperms.temp-ttl-seconds (" + c.luckperms.tempTtlSeconds
                    + ") must be greater than discord.poll-interval-seconds (" + c.discord.pollIntervalSeconds + ")");
        if (c.luckperms.renewBeforeSeconds >= c.luckperms.tempTtlSeconds)
            throw new IllegalStateException("luckperms.renew-before-seconds must be less than temp-ttl-seconds");
        if (c.link.source == LinkSource.LIMBOAUTH) {
            if (c.link.limboauth.jdbcUrl.isBlank())
                throw new IllegalStateException("link.limboauth.jdbc-url is required when source is limboauth");
            if (c.link.limboauth.query.isBlank())
                throw new IllegalStateException("link.limboauth.query is required when source is limboauth");
            if (c.link.limboauth.cacheRefreshSeconds <= 0)
                throw new IllegalStateException("link.limboauth.cache-refresh-seconds must be > 0");
        }
    }
}
