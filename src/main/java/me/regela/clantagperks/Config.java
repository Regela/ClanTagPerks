package me.regela.clantagperks;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed snapshot of config.yml, bound by Configurate's {@code ObjectMapper} (no hand-rolled YAML
 * navigation). Fields carry their defaults inline, so a partial config still loads. Rebuilt on reload;
 * treated as immutable after {@link ConfigLoader#load} returns.
 */
@ConfigSerializable
public final class Config {

    @Setting("discord") public Discord discord = new Discord();
    @Setting("luckperms") public LuckPerms luckperms = new LuckPerms();
    @Setting("link") public Link link = new Link();
    @Setting("notifications") public Notifications notifications = new Notifications();
    @Setting("logging") public Logging logging = new Logging();

    @ConfigSerializable
    public static final class Discord {
        @Setting("bot-token") public String botToken = "";
        /** Guild the BOT is in; we list ITS members. */
        @Setting("member-guild-id") public String memberGuildId = "";
        /** The Server Tag (primary_guild.identity_guild_id) we reward. */
        @Setting("tag-guild-id") public String tagGuildId = "";
        @Setting("poll-interval-seconds") public int pollIntervalSeconds = 120;
        @Setting("api-base") public String apiBase = "https://discord.com/api/v10";
        /** "" = disabled. */
        @Setting("log-channel-id") public String logChannelId = "";
        @Setting("request-timeout-seconds") public int requestTimeoutSeconds = 15;
    }

    @ConfigSerializable
    public static final class LuckPerms {
        @Setting("group") public String group = "clantag";
        @Setting("mode") public Mode mode = Mode.EXPIRY;
        @Setting("temp-ttl-seconds") public int tempTtlSeconds = 600;
        @Setting("renew-before-seconds") public int renewBeforeSeconds = 180;
    }

    @ConfigSerializable
    public static final class Link {
        @Setting("source") public LinkSource source = LinkSource.MANUAL;
        /** discordId -> minecraft name. */
        @Setting("manual") public Map<String, String> manual = new LinkedHashMap<>();
        @Setting("limboauth") public LimboAuth limboauth = new LimboAuth();
    }

    @ConfigSerializable
    public static final class LimboAuth {
        @Setting("jdbc-url") public String jdbcUrl = "";
        @Setting("username") public String username = "";
        @Setting("password") public String password = "";
        @Setting("query") public String query = "";
        @Setting("cache-refresh-seconds") public int cacheRefreshSeconds = 1800;
    }

    @ConfigSerializable
    public static final class Notifications {
        @Setting("enabled") public boolean enabled = false;
        @Setting("on-startup") public boolean onStartup = true;
        @Setting("on-earn") public boolean onEarn = true;
        @Setting("on-lost") public boolean onLost = true;
        @Setting("messages") public Messages messages = new Messages();
    }

    @ConfigSerializable
    public static final class Messages {
        @Setting("startup") public String startup =
                "ClanTagPerks enabled — poll={poll}s, mode={mode}, source={source}";
        @Setting("earned") public String earned = "✅ earned `{group}`: {players}";
        @Setting("lost") public String lost = "➖ lost `{group}`: {players}";
    }

    @ConfigSerializable
    public static final class Logging {
        @Setting("debug") public boolean debug = false;
    }
}
