package me.regela.clantagperks;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Applies the LuckPerms group with the fewest possible writes: a temp inheritance node is (re)written
 * only when the player is new or the node is about to expire ({@code renew-before-seconds}). The
 * in-memory mirror is updated only after the async LuckPerms write succeeds, so a failed write is
 * retried next cycle instead of being silently treated as done.
 */
public final class PermissionApplier {

    private final UserManager userManager;
    private final Config cfg;
    private final Logger log;

    /** uuid -> epoch-seconds when our temp node expires (mirror of storage, updated post-write). */
    private final ConcurrentHashMap<UUID, Long> granted = new ConcurrentHashMap<>();
    /** uuid -> name we granted under, for human-readable transition logging/notifications. */
    private final ConcurrentHashMap<UUID, String> grantedNames = new ConcurrentHashMap<>();
    /** uuid -> Discord id we granted for, so explicit-mode removal keys off membership, not re-resolution. */
    private final ConcurrentHashMap<UUID, String> grantedDiscordIds = new ConcurrentHashMap<>();
    /** name (as resolved) -> uuid, to avoid repeated storage lookups. */
    private final ConcurrentHashMap<String, UUID> uuidCache = new ConcurrentHashMap<>();

    public PermissionApplier(LuckPerms luckPerms, Config cfg, Logger log) {
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
            else if (cfg.logging.debug) log.info("[ClanTagPerks] no UUID for name '{}' (never seen by LuckPerms?)", name);
            return uuid;
        } catch (Exception e) {
            log.warn("[ClanTagPerks] UUID lookup failed for '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /** Pure renew rule (testable): rewrite when untracked or within the renew window of expiry. */
    static boolean needsGrant(Long currentExpiry, long now, int renewBeforeSeconds) {
        return currentExpiry == null || currentExpiry - now < renewBeforeSeconds;
    }

    /** True when we must (re)write the temp node for this uuid right now. */
    public boolean needsGrant(UUID uuid, long now) {
        return needsGrant(granted.get(uuid), now, cfg.luckperms.renewBeforeSeconds);
    }

    /** True if we are already tracking a live grant for this uuid (i.e. not a brand-new perk). */
    public boolean isTracked(UUID uuid) {
        return granted.containsKey(uuid);
    }

    /** Adds/renews the temp group node. Updates the in-memory mirror only once the write succeeds. */
    public void grant(UUID uuid, String name, String discordId, long now) {
        long ttl = cfg.luckperms.tempTtlSeconds;
        userManager.modifyUser(uuid, user -> {
            // Drop any prior temp node for this group, then add a fresh one (clean renew).
            user.data().clear(isOurGroup());
            user.data().add(InheritanceNode.builder(cfg.luckperms.group)
                    .expiry(Duration.ofSeconds(ttl))
                    .build());
        }).whenComplete((v, err) -> {
            if (err != null) {
                log.warn("[ClanTagPerks] grant failed for {} ({}): {} — will retry next cycle",
                        name, uuid, err.getMessage());
                return;
            }
            granted.put(uuid, now + ttl);
            if (name != null) grantedNames.put(uuid, name);
            if (discordId != null) grantedDiscordIds.put(uuid, discordId);
            if (cfg.logging.debug) log.info("[ClanTagPerks] granted/renewed '{}' for {} ({}) -> {}s",
                    cfg.luckperms.group, name, uuid, ttl);
        });
    }

    /** Explicit-mode removal of the group node. Mirror is updated once the write succeeds. */
    public String remove(UUID uuid) {
        String name = grantedNames.get(uuid);
        userManager.modifyUser(uuid, user -> user.data().clear(isOurGroup()))
                .whenComplete((v, err) -> {
                    if (err != null) {
                        log.warn("[ClanTagPerks] remove failed for {} ({}): {}", name, uuid, err.getMessage());
                        return;
                    }
                    granted.remove(uuid);
                    grantedNames.remove(uuid);
                    grantedDiscordIds.remove(uuid);
                    if (cfg.logging.debug) log.info("[ClanTagPerks] removed '{}' from {} ({})",
                            cfg.luckperms.group, name, uuid);
                });
        return name != null ? name : uuid.toString();
    }

    /**
     * Drops expired entries from the in-memory mirror (expiry mode lets the node die on its own).
     * @return the names of perks that just lapsed this cycle (for notification).
     */
    public List<String> pruneExpired(long now) {
        List<String> lapsed = new ArrayList<>();
        granted.entrySet().removeIf(e -> {
            if (e.getValue() <= now) {
                String name = grantedNames.remove(e.getKey());
                grantedDiscordIds.remove(e.getKey());
                lapsed.add(name != null ? name : e.getKey().toString());
                return true;
            }
            return false;
        });
        return lapsed;
    }

    /** Discord id we granted this uuid for, or null if not tracked. */
    public String grantedDiscordId(UUID uuid) {
        return grantedDiscordIds.get(uuid);
    }

    public Set<UUID> grantedUuids() {
        return granted.keySet();
    }

    public int grantedCount() {
        return granted.size();
    }

    private Predicate<Node> isOurGroup() {
        return n -> n instanceof InheritanceNode
                && ((InheritanceNode) n).getGroupName().equalsIgnoreCase(cfg.luckperms.group);
    }
}
