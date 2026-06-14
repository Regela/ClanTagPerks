package me.regela.clantagperks;

import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Resolves Discord user id -> Minecraft name.
 *
 * <ul>
 *   <li>{@code manual}: in-memory map from config, zero DB access.</li>
 *   <li>{@code limboauth}: a single bulk SELECT into an in-memory cache, refreshed at most once per
 *       {@code cache-refresh-seconds} — never per poll, never per user.</li>
 * </ul>
 */
public final class LinkResolver {

    private final Config cfg;
    private final Logger log;

    private volatile Map<String, String> cache = Collections.emptyMap();
    private volatile long lastRefreshEpoch = 0;

    public LinkResolver(Config cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
        if (cfg.linkSource.equals("manual")) {
            this.cache = cfg.manualMap;
        }
    }

    /** discordId -> mc name (or null). Refreshes the limboauth cache lazily when stale. */
    public Map<String, String> get() {
        if (cfg.linkSource.equals("manual")) {
            return cfg.manualMap;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (now - lastRefreshEpoch >= cfg.cacheRefreshSeconds) {
            refresh();
        }
        return cache;
    }

    /** Forces a reload of the limboauth cache (used by /clantag reload and lazily by {@link #get}). */
    public synchronized void refresh() {
        if (!cfg.linkSource.equals("limboauth")) return;
        Map<String, String> fresh = new HashMap<>();
        Properties props = new Properties();
        props.put("user", cfg.dbUsername);
        props.put("password", cfg.dbPassword);
        try {
            // Instantiate the (shaded, relocated) driver directly to bypass the DriverManager
            // service-loader classloader limitation inside plugin classloaders.
            org.mariadb.jdbc.Driver driver = new org.mariadb.jdbc.Driver();
            try (Connection conn = driver.connect(cfg.jdbcUrl, props);
                 PreparedStatement ps = conn.prepareStatement(cfg.linkQuery);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);     // NICKNAME
                    String discordId = rs.getString(2); // DISCORD_ID
                    if (name != null && discordId != null && !discordId.isBlank()) {
                        fresh.put(discordId.trim(), name); // keep name exactly as stored (case matters)
                    }
                }
            }
            this.cache = Collections.unmodifiableMap(fresh);
            this.lastRefreshEpoch = System.currentTimeMillis() / 1000L;
            log.info("[ClanTagPerks] limboauth link cache refreshed: {} links", fresh.size());
        } catch (Exception e) {
            log.warn("[ClanTagPerks] limboauth cache refresh failed ({}: {}); keeping previous cache",
                    e.getClass().getSimpleName(), e.getMessage());
            if (cfg.debug) log.warn("[ClanTagPerks] limboauth stacktrace", e);
            // Keep the stale cache rather than wiping links on a transient DB hiccup.
        }
    }

    public int size() {
        return get().size();
    }
}
