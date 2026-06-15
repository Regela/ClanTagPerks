package me.regela.clantagperks;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Polls Discord for guild members and returns the set of Discord user ids currently wearing the
 * target Server Tag. The response is mapped to typed DTOs by Gson (no hand-rolled JSON walking),
 * paginated, and rate-limit aware.
 *
 * <p>Runs inside Velocity's async scheduler pool (never a netty thread), so the blocking
 * {@link HttpClient#send} calls here do not affect the proxy or any backend tick.
 */
public final class DiscordPoller {

    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_429_RETRIES = 5;
    private static final String USER_AGENT = "ClanTagPerks (https://github.com/regela, 1.0.0)";
    private static final Gson GSON = new Gson();

    private final Config cfg;
    private final Logger log;
    private final HttpClient http;
    private final Duration timeout;

    public DiscordPoller(Config cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
        this.timeout = Duration.ofSeconds(cfg.discord.requestTimeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * @return set of Discord user ids wearing the tag, or {@code null} if Discord was unreachable
     *         / rate-limited so the caller must NOT touch permissions this cycle.
     */
    public Set<String> fetchWearers() {
        Set<String> wearers = new HashSet<>();
        String after = "0";
        try {
            while (true) {
                String url = cfg.discord.apiBase + "/guilds/" + cfg.discord.memberGuildId
                        + "/members?limit=" + PAGE_LIMIT + "&after=" + after;
                HttpResponse<String> resp = requestWithRetry(url);
                if (resp == null) return null;
                if (resp.statusCode() / 100 != 2) {
                    log.warn("[ClanTagPerks] Discord returned {} for {} — skipping cycle",
                            resp.statusCode(), url);
                    return null;
                }
                Page page = parseMembersPage(resp.body(), cfg.discord.tagGuildId, wearers);
                if (page.count() < PAGE_LIMIT || page.lastId() == null) break; // last page
                after = page.lastId();
            }
            return wearers;
        } catch (Exception e) {
            log.warn("[ClanTagPerks] poll failed ({}: {}) — skipping cycle, permissions untouched",
                    e.getClass().getSimpleName(), e.getMessage());
            if (cfg.logging.debug) log.warn("[ClanTagPerks] poll stacktrace", e);
            return null;
        }
    }

    private HttpResponse<String> requestWithRetry(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bot " + cfg.discord.botToken)
                .header("User-Agent", USER_AGENT)
                .timeout(timeout)
                .GET()
                .build();
        long backoffMs = 1000;
        for (int attempt = 0; attempt <= MAX_429_RETRIES; attempt++) {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 429 || resp.statusCode() / 100 == 5) {
                double retryAfter = resp.headers().firstValue("Retry-After")
                        .map(Double::parseDouble).orElse(backoffMs / 1000.0);
                long sleep = Math.max((long) (retryAfter * 1000), backoffMs);
                log.warn("[ClanTagPerks] Discord {} on attempt {} — backing off {} ms",
                        resp.statusCode(), attempt + 1, sleep);
                Thread.sleep(sleep);
                backoffMs = Math.min(backoffMs * 2, 30_000);
                continue;
            }
            if (cfg.logging.debug) {
                resp.headers().firstValue("X-RateLimit-Remaining").ifPresent(r ->
                        log.info("[ClanTagPerks] rate-limit remaining: {}", r));
            }
            return resp;
        }
        log.warn("[ClanTagPerks] exhausted {} retries against Discord — skipping cycle", MAX_429_RETRIES);
        return null;
    }

    /** One page of guild members. */
    record Page(int count, String lastId) {}

    /**
     * Maps a page of {@code GET /guilds/{id}/members} via Gson, adding tag-wearer ids to {@code out}.
     * Package-private + static so it can be unit-tested without any HTTP.
     */
    static Page parseMembersPage(String body, String tagGuildId, Set<String> out) {
        Member[] members = GSON.fromJson(body, Member[].class);
        if (members == null || members.length == 0) return new Page(0, null);
        String last = null;
        for (Member m : members) {
            if (m == null || m.user == null || m.user.id == null) continue;
            last = m.user.id; // members are sorted ascending by id; last non-null wins
            if (isWearingTag(m.user, tagGuildId)) out.add(m.user.id);
        }
        return new Page(members.length, last);
    }

    private static boolean isWearingTag(User user, String tagGuildId) {
        PrimaryGuild pg = user.primaryGuild;
        return pg != null && Boolean.TRUE.equals(pg.identityEnabled)
                && tagGuildId.equals(pg.identityGuildId);
    }

    /** Optional: post a status line to the configured log channel. Best-effort, never throws. */
    public void postStatus(String message) {
        if (!cfg.notifications.enabled) return;
        if (cfg.discord.logChannelId == null || cfg.discord.logChannelId.isBlank()) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("content", message);
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(cfg.discord.apiBase + "/channels/" + cfg.discord.logChannelId + "/messages"))
                    .header("Authorization", "Bot " + cfg.discord.botToken)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            if (cfg.logging.debug) log.warn("[ClanTagPerks] status post failed: {}", e.getMessage());
        }
    }

    // --- Discord REST DTOs (only the fields we read; Gson leaves the rest out) ---

    private static final class Member {
        User user;
    }

    private static final class User {
        String id;
        @SerializedName("primary_guild") PrimaryGuild primaryGuild;
    }

    private static final class PrimaryGuild {
        @SerializedName("identity_enabled") Boolean identityEnabled;
        @SerializedName("identity_guild_id") String identityGuildId;
    }
}
