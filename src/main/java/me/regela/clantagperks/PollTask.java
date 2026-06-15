package me.regela.clantagperks;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The periodic cycle: poll Discord, resolve links, grant/renew, expire/remove. */
public final class PollTask implements Runnable {

    private final Config cfg;
    private final Logger log;
    private final DiscordPoller poller;
    private final LinkResolver links;
    private final PermissionApplier applier;

    // Last-cycle stats for /clantag status (volatile: read from command thread).
    private volatile int lastWearing = 0;
    private volatile int lastLinked = 0;
    private volatile long lastRunEpoch = 0;
    private volatile boolean lastCycleSkipped = false;

    public PollTask(Config cfg, Logger log, DiscordPoller poller, LinkResolver links, PermissionApplier applier) {
        this.cfg = cfg;
        this.log = log;
        this.poller = poller;
        this.links = links;
        this.applier = applier;
    }

    @Override
    public void run() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("[ClanTagPerks] unexpected error in poll cycle: {}", e.getMessage());
            if (cfg.logging.debug) log.warn("[ClanTagPerks] cycle stacktrace", e);
        }
    }

    private void tick() {
        Set<String> wearers = poller.fetchWearers();
        long now = System.currentTimeMillis() / 1000L;
        lastRunEpoch = now;

        if (wearers == null) {                 // Discord unreachable / rate-limited — do not touch perms
            lastCycleSkipped = true;
            if (cfg.logging.debug) log.info("[ClanTagPerks] poll skipped; permissions left as-is (expiry will self-heal)");
            return;
        }
        lastCycleSkipped = false;

        Map<String, String> linkMap = links.get();  // discordId -> mc name (cached, no DB per-poll)
        lastWearing = wearers.size();
        lastLinked = linkMap.size();

        Set<UUID> eligibleUuids = new HashSet<>();
        List<String> earned = new ArrayList<>();   // new perks this cycle
        List<String> lost = new ArrayList<>();      // perks removed/lapsed this cycle
        for (String discordId : wearers) {
            String name = linkMap.get(discordId);
            if (name == null) {
                if (cfg.logging.debug) log.info("[ClanTagPerks] wearer {} not linked to any MC name", discordId);
                continue;
            }
            UUID uuid = applier.resolveUuid(name);
            if (uuid == null) continue;
            eligibleUuids.add(uuid);
            if (applier.needsGrant(uuid, now)) {
                boolean wasTracked = applier.isTracked(uuid);
                applier.grant(uuid, name, discordId, now);
                if (!wasTracked) earned.add(name);   // brand-new perk, not a renewal
            }
        }

        if (cfg.luckperms.mode == Mode.EXPLICIT) {
            // Remove only when the player's Discord id is no longer wearing the tag — NOT merely when
            // this cycle failed to resolve them (a transient UUID lookup error must not strip a perk).
            for (UUID uuid : new HashSet<>(applier.grantedUuids())) {
                String discordId = applier.grantedDiscordId(uuid);
                boolean stillWearing = discordId != null
                        ? wearers.contains(discordId)
                        : eligibleUuids.contains(uuid); // fallback for grants without a tracked id
                if (!stillWearing) lost.add(applier.remove(uuid));
            }
        } else { // expiry
            lost.addAll(applier.pruneExpired(now));
        }

        if (!earned.isEmpty() || !lost.isEmpty()) {
            notifyTransitions(earned, lost);
        }

        if (cfg.logging.debug) {
            log.info("[ClanTagPerks] cycle ok: wearing={}, linked={}, granted={}",
                    lastWearing, lastLinked, applier.grantedCount());
        }
    }

    /** Logs perk changes and (if enabled) posts them to the Discord log channel using templates. */
    private void notifyTransitions(List<String> earned, List<String> lost) {
        if (!earned.isEmpty()) {
            String msg = cfg.notifications.messages.earned
                    .replace("{group}", cfg.luckperms.group)
                    .replace("{players}", String.join(", ", earned));
            log.info("[ClanTagPerks] {}", msg);
            if (cfg.notifications.onEarn) poller.postStatus(msg);
        }
        if (!lost.isEmpty()) {
            String msg = cfg.notifications.messages.lost
                    .replace("{group}", cfg.luckperms.group)
                    .replace("{players}", String.join(", ", lost));
            log.info("[ClanTagPerks] {}", msg);
            if (cfg.notifications.onLost) poller.postStatus(msg);
        }
    }

    /** On-demand check for /clantag check <player>. Does a fresh poll. */
    public String checkPlayer(String name) {
        Set<String> wearers = poller.fetchWearers();
        if (wearers == null) return "Discord unreachable right now — try again.";

        String discordId = links.discordIdForName(name);
        if (discordId == null) return "'" + name + "' is not linked to any Discord id.";

        boolean wearing = wearers.contains(discordId);
        UUID uuid = applier.resolveUuid(name);
        return "'" + name + "' (discord " + discordId + "): wearing tag = " + wearing
                + ", uuid = " + uuid + ", granted = " + (uuid != null && applier.grantedUuids().contains(uuid));
    }

    /** Sends a one-off line to the Discord log channel (used for startup announcement). */
    public void announce(String message) {
        poller.postStatus(message);
    }

    public int lastWearing() { return lastWearing; }
    public int lastLinked() { return lastLinked; }
    public long lastRunEpoch() { return lastRunEpoch; }
    public boolean lastCycleSkipped() { return lastCycleSkipped; }
    public int grantedCount() { return applier.grantedCount(); }
}
