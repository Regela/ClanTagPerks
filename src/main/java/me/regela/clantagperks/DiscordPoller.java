package me.regela.clantagperks;

import com.google.gson.stream.JsonReader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Polls Discord for guild members and returns the set of Discord user ids currently wearing the
 * target Server Tag. Streams the JSON (constant memory), paginates, and respects rate limits.
 *
 * <p>Runs inside Velocity's async scheduler pool (never a netty thread), so the blocking
 * {@link HttpClient#send} calls here do not affect the proxy or any backend tick.
 */
public final class DiscordPoller {

    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_429_RETRIES = 5;

    private final Config cfg;
    private final Logger log;
    private final HttpClient http;

    public DiscordPoller(Config cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(cfg.requestTimeoutSeconds))
                .build();
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
                String url = cfg.apiBase + "/guilds/" + cfg.memberGuildId
                        + "/members?limit=" + PAGE_LIMIT + "&after=" + after;
                HttpResponse<String> resp = requestWithRetry(url);
                if (resp == null) return null;
                if (resp.statusCode() / 100 != 2) {
                    log.warn("[ClanTagPerks] Discord returned {} for {} — skipping cycle",
                            resp.statusCode(), maskUrl(url));
                    return null;
                }
                int count = parsePage(resp.body(), wearers);
                String last = lastUserId(resp.body());
                if (count < PAGE_LIMIT || last == null) break; // last page
                after = last;
            }
            return wearers;
        } catch (Exception e) {
            log.warn("[ClanTagPerks] poll failed ({}: {}) — skipping cycle, permissions untouched",
                    e.getClass().getSimpleName(), e.getMessage());
            if (cfg.debug) log.warn("[ClanTagPerks] poll stacktrace", e);
            return null;
        }
    }

    private HttpResponse<String> requestWithRetry(String url) throws IOException, InterruptedException {
        long backoffMs = 1000;
        for (int attempt = 0; attempt <= MAX_429_RETRIES; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bot " + cfg.botToken)
                    .header("User-Agent", "ClanTagPerks (https://github.com/regela, 1.0.0)")
                    .timeout(Duration.ofSeconds(cfg.requestTimeoutSeconds))
                    .GET()
                    .build();
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
            if (cfg.debug) {
                resp.headers().firstValue("X-RateLimit-Remaining").ifPresent(r ->
                        log.info("[ClanTagPerks] rate-limit remaining: {}", r));
            }
            return resp;
        }
        log.warn("[ClanTagPerks] exhausted {} retries against Discord — skipping cycle", MAX_429_RETRIES);
        return null;
    }

    /** Streams the array, adding wearer ids to {@code out}. Returns number of members in the page. */
    private int parsePage(String body, Set<String> out) throws IOException {
        int members = 0;
        try (JsonReader r = new JsonReader(new StringReader(body))) {
            r.beginArray();
            while (r.hasNext()) {
                members++;
                String id = null;
                boolean wearing = false;
                r.beginObject();                 // member
                while (r.hasNext()) {
                    if (!"user".equals(r.nextName())) { r.skipValue(); continue; }
                    r.beginObject();             // user
                    while (r.hasNext()) {
                        switch (r.nextName()) {
                            case "id" -> id = r.nextString();
                            case "primary_guild" -> wearing = parsePrimaryGuild(r);
                            default -> r.skipValue();
                        }
                    }
                    r.endObject();
                }
                r.endObject();
                if (wearing && id != null) out.add(id);
            }
            r.endArray();
        }
        return members;
    }

    /** Returns true iff the player wears OUR target tag. Consumes the primary_guild value. */
    private boolean parsePrimaryGuild(JsonReader r) throws IOException {
        if (r.peek() == com.google.gson.stream.JsonToken.NULL) { r.nextNull(); return false; }
        boolean enabled = false;
        String identityGuild = null;
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "identity_enabled" -> enabled = readBoolLenient(r);
                case "identity_guild_id" -> identityGuild = readStringLenient(r);
                default -> r.skipValue();
            }
        }
        r.endObject();
        return enabled && cfg.tagGuildId.equals(identityGuild);
    }

    private boolean readBoolLenient(JsonReader r) throws IOException {
        if (r.peek() == com.google.gson.stream.JsonToken.NULL) { r.nextNull(); return false; }
        return r.nextBoolean();
    }

    private String readStringLenient(JsonReader r) throws IOException {
        if (r.peek() == com.google.gson.stream.JsonToken.NULL) { r.nextNull(); return null; }
        return r.nextString();
    }

    /** Highest user id in the page (members are sorted ascending by id) for pagination. */
    private String lastUserId(String body) throws IOException {
        String last = null;
        try (JsonReader r = new JsonReader(new StringReader(body))) {
            r.beginArray();
            while (r.hasNext()) {
                r.beginObject();
                while (r.hasNext()) {
                    if (!"user".equals(r.nextName())) { r.skipValue(); continue; }
                    r.beginObject();
                    while (r.hasNext()) {
                        if ("id".equals(r.nextName())) last = r.nextString();
                        else r.skipValue();
                    }
                    r.endObject();
                }
                r.endObject();
            }
            r.endArray();
        }
        return last;
    }

    /** Optional: post a status line to the configured log channel. Best-effort, never throws. */
    public void postStatus(String message) {
        if (cfg.logChannelId == null || cfg.logChannelId.isBlank()) return;
        try {
            String json = "{\"content\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(cfg.apiBase + "/channels/" + cfg.logChannelId + "/messages"))
                    .header("Authorization", "Bot " + cfg.botToken)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "ClanTagPerks (https://github.com/regela, 1.0.0)")
                    .timeout(Duration.ofSeconds(cfg.requestTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            if (cfg.debug) log.warn("[ClanTagPerks] status post failed: {}", e.getMessage());
        }
    }

    private static String maskUrl(String url) {
        return url; // url carries no secrets; token is in the header
    }
}
