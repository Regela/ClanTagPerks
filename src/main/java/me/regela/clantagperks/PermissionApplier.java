package me.regela.clantagperks;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.types.InheritanceNode;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the LuckPerms group with the fewest possible writes:
 * a temp inheritance node is (re)written only when the player is new or the node is about to expire
 * ({@code renew-before-seconds}). Tracks granted expiries in memory so most cycles write nothing.
 */
public final class PermissionApplier {

    private final LuckPerms luckPerms;
    private final UserManager userManager;
    private final Config cfg;
    private final Logger log;

    /** uuid -> epoch-seconds when our temp node expires (best-effort mirror of storage). */
    private final Map<UUID, Long> granted = new ConcurrentHashMap<>();
    /** uuid -> name we granted under, for human-readable transition logging/notifications. */
    private final Map<UUID, String> grantedNames = new ConcurrentHashMap<>();
    /** name (as resolved) -> uuid, to avoid repeated storage lookups. */
    private final Map<String, UUID> uuidCache = new ConcurrentHashMap<>();

    public PermissionApplier(LuckPerms luckPerms, Config cfg, Logger log) {
        this.luckPerms = luckPerms;
        this.userManager = luckPerms.getUserManager();
        this.cfg = cfg;
        this.log = log;
    }

    /** Resolves a Minecraft name to a UUID via LuckPerms (case-sensitive), cached. Null if unknown. */
    public UUID resolveUuid(String name) {
        UUID cached = uuidCache.get(name);
        if (cached != null) return cached;
        try {
            UUID uuid = userManager.lookupUniqueId(name).join();
            if (uuid != null) uuidCache.put(name, uuid);
            else if (cfg.debug) log.info("[ClanTagPerks] no UUID for name '{}' (never seen by LuckPerms?)", name);
            return uuid;
        } catch (Exception e) {
            log.warn("[ClanTagPerks] UUID lookup failed for '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /** True when we must (re)write the temp node for this uuid right now. */
    public boolean needsGrant(UUID uuid, long now) {
        Long exp = granted.get(uuid);
        return exp == null || exp - now < cfg.renewBeforeSeconds;
    }

    /** True if we are already tracking a live grant for this uuid (i.e. not a brand-new perk). */
    public boolean isTracked(UUID uuid) {
        return granted.containsKey(uuid);
    }

    /** Adds/renews the temp group node. Updates the in-memory expiry on success. */
    public void grant(UUID uuid, String name, long now) {
        long ttl = cfg.tempTtlSeconds;
        userManager.modifyUser(uuid, user -> {
            // Drop any prior temp node for this group, then add a fresh one (clean renew).
            user.data().clear(n -> n instanceof InheritanceNode
                    && ((InheritanceNode) n).getGroupName().equalsIgnoreCase(cfg.group));
            user.data().add(InheritanceNode.builder(cfg.group)
                    .expiry(Duration.ofSeconds(ttl))
                    .build());
        }).whenComplete((v, err) -> {
            if (err != null) log.warn("[ClanTagPerks] grant failed for {}: {}", uuid, err.getMessage());
        });
        granted.put(uuid, now + ttl);
        if (name != null) grantedNames.put(uuid, name);
        if (cfg.debug) log.info("[ClanTagPerks] granted/renewed '{}' for {} ({}) -> {}s", cfg.group, name, uuid, ttl);
    }

    /** Explicit-mode removal of the group node. Returns the name we had for it (or uuid string). */
    public String remove(UUID uuid) {
        userManager.modifyUser(uuid, user ->
                user.data().clear(n -> n instanceof InheritanceNode
                        && ((InheritanceNode) n).getGroupName().equalsIgnoreCase(cfg.group)))
                .whenComplete((v, err) -> {
                    if (err != null) log.warn("[ClanTagPerks] remove failed for {}: {}", uuid, err.getMessage());
                });
        granted.remove(uuid);
        String name = grantedNames.remove(uuid);
        if (cfg.debug) log.info("[ClanTagPerks] removed '{}' from {} ({})", cfg.group, name, uuid);
        return name != null ? name : uuid.toString();
    }

    /**
     * Drops expired entries from the in-memory mirror (expiry mode lets the node die on its own).
     * @return the names of perks that just lapsed this cycle (for notification).
     */
    public java.util.List<String> pruneExpired(long now) {
        java.util.List<String> lapsed = new java.util.ArrayList<>();
        granted.entrySet().removeIf(e -> {
            if (e.getValue() <= now) {
                String name = grantedNames.remove(e.getKey());
                lapsed.add(name != null ? name : e.getKey().toString());
                return true;
            }
            return false;
        });
        return lapsed;
    }

    public java.util.Set<UUID> grantedUuids() {
        return granted.keySet();
    }

    public int grantedCount() {
        return granted.size();
    }
}
