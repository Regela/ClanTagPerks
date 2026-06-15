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
 * Resolves Discord user id -> Minecraft name (and back).
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

    private volatile Map<String, String> cache = Collections.emptyMap();       // discordId -> name
    private volatile Map<String, String> reverse = Collections.emptyMap();     // name -> discordId
    private volatile long lastRefreshEpoch = 0;

    public LinkResolver(Config cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
        if (cfg.link.source == LinkSource.MANUAL) {
            this.cache = cfg.link.manual;
            this.reverse = buildReverse(cfg.link.manual);
        }
    }

    /** discordId -> mc name. Refreshes the limboauth cache lazily when stale. */
    public Map<String, String> get() {
        if (cfg.link.source == LinkSource.MANUAL) {
            return cfg.link.manual;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (now - lastRefreshEpoch >= cfg.link.limboauth.cacheRefreshSeconds) {
            refresh();
        }
        return cache;
    }

    /** Reverse lookup: Minecraft name -> Discord id (or null). Ensures the cache is fresh first. */
    public String discordIdForName(String name) {
        get(); // trigger lazy refresh in limboauth mode
        return reverse.get(name);
    }

    /** Forces a reload of the limboauth cache (used by /clantag reload and lazily by {@link #get}). */
    public synchronized void refresh() {
        if (cfg.link.source != LinkSource.LIMBOAUTH) return;
        Map<String, String> fresh = new HashMap<>();
        Properties props = new Properties();
        props.put("user", cfg.link.limboauth.username);
        props.put("password", cfg.link.limboauth.password);
        try {
            // Instantiate the (shaded, relocated) driver directly to bypass the DriverManager
            // service-loader classloader limitation inside plugin classloaders.
            org.mariadb.jdbc.Driver driver = new org.mariadb.jdbc.Driver();
            try (Connection conn = driver.connect(cfg.link.limboauth.jdbcUrl, props);
                 PreparedStatement ps = conn.prepareStatement(cfg.link.limboauth.query);
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
            this.reverse = buildReverse(fresh);
            this.lastRefreshEpoch = System.currentTimeMillis() / 1000L;
            log.info("[ClanTagPerks] limboauth link cache refreshed: {} links", fresh.size());
        } catch (Exception e) {
            log.warn("[ClanTagPerks] limboauth cache refresh failed ({}: {}); keeping previous cache",
                    e.getClass().getSimpleName(), e.getMessage());
            if (cfg.logging.debug) log.warn("[ClanTagPerks] limboauth stacktrace", e);
            // Keep the stale cache rather than wiping links on a transient DB hiccup.
        }
    }

    public int size() {
        return get().size();
    }

    private static Map<String, String> buildReverse(Map<String, String> forward) {
        Map<String, String> rev = new HashMap<>(forward.size());
        for (Map.Entry<String, String> e : forward.entrySet()) {
            rev.put(e.getValue(), e.getKey()); // name -> discordId (last wins on duplicate names)
        }
        return Collections.unmodifiableMap(rev);
    }
}
