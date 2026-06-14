package me.regela.clantagperks;

import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The periodic cycle (spec §5.4): poll Discord, resolve links, grant/renew, expire/remove. */
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
            if (cfg.debug) log.warn("[ClanTagPerks] cycle stacktrace", e);
        }
    }

    private void tick() {
        Set<String> wearers = poller.fetchWearers();
        long now = System.currentTimeMillis() / 1000L;
        lastRunEpoch = now;

        if (wearers == null) {                 // Discord unreachable / rate-limited — do not touch perms
            lastCycleSkipped = true;
            if (cfg.debug) log.info("[ClanTagPerks] poll skipped; permissions left as-is (expiry will self-heal)");
            return;
        }
        lastCycleSkipped = false;

        Map<String, String> linkMap = links.get();  // discordId -> mc name (cached, no DB per-poll)
        lastWearing = wearers.size();
        lastLinked = linkMap.size();

        Set<UUID> shouldHave = new HashSet<>();
        java.util.List<String> earned = new java.util.ArrayList<>();   // new perks this cycle
        java.util.List<String> lost = new java.util.ArrayList<>();     // perks removed/lapsed this cycle
        for (String discordId : wearers) {
            String name = linkMap.get(discordId);
            if (name == null) {
                if (cfg.debug) log.info("[ClanTagPerks] wearer {} not linked to any MC name", discordId);
                continue;
            }
            UUID uuid = applier.resolveUuid(name);
            if (uuid == null) continue;
            shouldHave.add(uuid);
            if (applier.needsGrant(uuid, now)) {
                boolean wasTracked = applier.isTracked(uuid);
                applier.grant(uuid, name, now);
                if (!wasTracked) earned.add(name);   // brand-new perk, not a renewal
            }
        }

        if (cfg.mode.equals("explicit")) {
            for (UUID uuid : new HashSet<>(applier.grantedUuids())) {
                if (!shouldHave.contains(uuid)) lost.add(applier.remove(uuid));
            }
        } else { // expiry
            lost.addAll(applier.pruneExpired(now));
        }

        if (!earned.isEmpty() || !lost.isEmpty()) {
            notifyTransitions(earned, lost);
        }

        if (cfg.debug) {
            log.info("[ClanTagPerks] cycle ok: wearing={}, linked={}, granted={}",
                    lastWearing, lastLinked, applier.grantedCount());
        }
    }

    /** Posts perk changes to the configured Discord log channel (no-op if unset). */
    private void notifyTransitions(java.util.List<String> earned, java.util.List<String> lost) {
        StringBuilder sb = new StringBuilder();
        if (!earned.isEmpty()) sb.append("✅ earned `").append(cfg.group).append("`: ").append(String.join(", ", earned));
        if (!lost.isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("➖ lost `").append(cfg.group).append("`: ").append(String.join(", ", lost));
        }
        log.info("[ClanTagPerks] {}", sb);
        poller.postStatus(sb.toString());
    }

    /** On-demand check for /clantag check <player>. Does a fresh poll. */
    public String checkPlayer(String name) {
        Set<String> wearers = poller.fetchWearers();
        if (wearers == null) return "Discord unreachable right now — try again.";
        Map<String, String> linkMap = links.get();

        String discordId = null;
        for (Map.Entry<String, String> e : linkMap.entrySet()) {
            if (e.getValue().equals(name)) { discordId = e.getKey(); break; }
        }
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
