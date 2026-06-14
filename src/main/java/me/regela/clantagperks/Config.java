package me.regela.clantagperks;

import java.util.Map;

/** Immutable snapshot of config.yml. Rebuilt on reload. */
public final class Config {
    // discord
    public final String botToken;
    public final String memberGuildId; // guild the BOT is in; we list ITS members
    public final String tagGuildId;    // the Server Tag (primary_guild.identity_guild_id) we reward
    public final int pollIntervalSeconds;
    public final String apiBase;
    public final String logChannelId;       // "" = disabled
    public final int requestTimeoutSeconds;

    // luckperms
    public final String group;
    public final String mode;               // "expiry" | "explicit"
    public final int tempTtlSeconds;
    public final int renewBeforeSeconds;

    // link
    public final String linkSource;         // "manual" | "limboauth"
    public final Map<String, String> manualMap; // discordId -> mc name
    public final String jdbcUrl;
    public final String dbUsername;
    public final String dbPassword;
    public final String linkQuery;
    public final int cacheRefreshSeconds;

    // notifications (Discord log-channel posts)
    public final boolean notifyEnabled;
    public final boolean notifyOnStartup;
    public final boolean notifyOnEarn;
    public final boolean notifyOnLost;
    public final String msgStartup;
    public final String msgEarned;
    public final String msgLost;

    // logging
    public final boolean debug;

    Config(String botToken, String memberGuildId, String tagGuildId, int pollIntervalSeconds, String apiBase,
           String logChannelId, int requestTimeoutSeconds, String group, String mode,
           int tempTtlSeconds, int renewBeforeSeconds, String linkSource,
           Map<String, String> manualMap, String jdbcUrl, String dbUsername, String dbPassword,
           String linkQuery, int cacheRefreshSeconds,
           boolean notifyEnabled, boolean notifyOnStartup, boolean notifyOnEarn, boolean notifyOnLost,
           String msgStartup, String msgEarned, String msgLost, boolean debug) {
        this.botToken = botToken;
        this.memberGuildId = memberGuildId;
        this.tagGuildId = tagGuildId;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.apiBase = apiBase;
        this.logChannelId = logChannelId;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.group = group;
        this.mode = mode;
        this.tempTtlSeconds = tempTtlSeconds;
        this.renewBeforeSeconds = renewBeforeSeconds;
        this.linkSource = linkSource;
        this.manualMap = manualMap;
        this.jdbcUrl = jdbcUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
        this.linkQuery = linkQuery;
        this.cacheRefreshSeconds = cacheRefreshSeconds;
        this.notifyEnabled = notifyEnabled;
        this.notifyOnStartup = notifyOnStartup;
        this.notifyOnEarn = notifyOnEarn;
        this.notifyOnLost = notifyOnLost;
        this.msgStartup = msgStartup;
        this.msgEarned = msgEarned;
        this.msgLost = msgLost;
        this.debug = debug;
    }
}
