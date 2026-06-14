# ClanTagPerks

Velocity plugin that grants a LuckPerms group to a player while they wear your Discord
**Server Tag**, and lets it lapse when the tag is removed. Polls the Discord REST API
(`GET /guilds/{id}/members` → `primary_guild`), maps Discord → Minecraft, and applies a
short-lived (temp) LuckPerms node with the minimum number of DB writes.

## Requirements

- A **Velocity** proxy with **LuckPerms** installed (it's a hard dependency).
- A Discord bot with the **Server Members Intent** enabled, added to the guild it should read.
- For `link.source: limboauth`: **LimboAuth + SocialAddon** writing to a SQL database the proxy
  can reach.
- **JDK 21** to build.

> On Minecraft 1.21.4 use a Velocity build that LimboAPI supports (3.5.0 line); see the
> compatibility note under [limboauth](#limboauth-production-link-mode).

## Build

```bash
./gradlew shadowJar
# -> build/libs/clantag-perks-1.0.0.jar
```

(Requires JDK 21; the Gradle toolchain targets 21.) The shadow jar bundles only the MariaDB JDBC
driver (relocated to `me.regela.clantagperks.libs.mariadb`); `gson` and `snakeyaml` are provided by
Velocity at runtime and are NOT shaded.

## Install

1. Drop `clantag-perks-1.0.0.jar` into your proxy's `plugins/` folder and start once to generate
   `plugins/clantagperks/config.yml`.
2. Create the reward group in LuckPerms (e.g. `/lpv creategroup clantag` and give it a prefix/perms).
3. Edit the config (bot token, guild ids, group, link source) and run `/clantag reload`.

## Configuration (`plugins/clantagperks/config.yml`)

Key fields (see the generated file for the full set + comments):

| Key | Meaning |
|---|---|
| `discord.bot-token` | Bot token. **Rotate before production**; keep out of git. |
| `discord.member-guild-id` | Guild the **bot is a member of** — we list its members (needs Server Members Intent). |
| `discord.tag-guild-id` | The **Server Tag** to reward — matched against each member's `primary_guild.identity_guild_id`. |
| `discord.poll-interval-seconds` | How often to poll Discord (default 120). |
| `luckperms.group` | Group to grant (e.g. `clantag`). |
| `luckperms.mode` | `expiry` (recommended; node self-removes) or `explicit` (diff-removal on transition). |
| `luckperms.temp-ttl-seconds` | Temp node lifetime — **must be > poll interval**. |
| `luckperms.renew-before-seconds` | Only renew when remaining < this (minimizes writes). |
| `link.source` | `manual` (config map) or `limboauth` (JDBC bulk SELECT, cached). |

> **`member-guild-id` vs `tag-guild-id`:** the bot can only list members of guilds it has
> joined. So it reads members of *its own* guild (`member-guild-id`) and checks whether each
> one wears *your* tag (`tag-guild-id`). These are usually different servers.

### limboauth (production link mode)

Set `link.source: limboauth` and configure `link.limboauth.{jdbc-url,username,password,query}`.
The query must return two columns `(nickname, discord_id)` and is run **once per
`cache-refresh-seconds`** into an in-memory cache (never per-poll, never per-user).

**Real schema (verified against LimboAuth 1.1.14 + SocialAddon 1.0.11):**

- `AUTH` (LimboAuth): `NICKNAME`, `LOWERCASENICKNAME`, `UUID`, `HASH`, …
- `SOCIAL` (SocialAddon): `LOWERCASENICKNAME`, `DISCORD_ID` (bigint), `VK_ID`, `TELEGRAM_ID`, …

`SOCIAL` stores only the *lowercase* nick, so the query JOINs `AUTH` to recover the proper-case
`NICKNAME` (offline UUIDs are case-sensitive). Both tables live in LimboAuth's database:

```sql
SELECT a.NICKNAME, s.DISCORD_ID FROM SOCIAL s
JOIN AUTH a ON s.LOWERCASENICKNAME = a.LOWERCASENICKNAME
WHERE s.DISCORD_ID IS NOT NULL
```

This was validated live: registering a player through LimboAuth + linking Discord via SocialAddon
populates these tables, and ClanTagPerks (`link.source: limboauth`) reads the link and grants the
group. Removing the `SOCIAL` row drops the link (verified: `1 links` ↔ `0 links`).

> Compatibility note: LimboAPI/LimboAuth require a matching Velocity build (e.g. Velocity 3.5.0 for
> the 1.21.4 line); the 1.1.27 Modrinth build does **not** load on Velocity 3.4.0. ClanTagPerks
> itself is unaffected — it only reads the database.

## Commands (`clantagperks.admin`)

- `/clantag reload` — re-read config and rebuild the polling pipeline.
- `/clantag status` — members wearing tag / linked / currently granted / last cycle age.
- `/clantag check <player>` — on-demand poll + report for one player.

## Notifications & safety

Optional Discord channel posts, configured under `notifications:` (posts go to
`discord.log-channel-id`). **Disabled by default** — usually not needed in production.

```yaml
notifications:
  enabled: false        # master switch
  on-startup: true      # post once on enable/reload
  on-earn: true         # post when a player gains the perk (renewals are silent)
  on-lost: true         # post when a player loses the perk
  messages:             # templates; placeholders below
    startup: "ClanTagPerks enabled — poll={poll}s, mode={mode}, source={source}"
    earned: "✅ earned `{group}`: {players}"
    lost: "➖ lost `{group}`: {players}"
```

Placeholders: `{group}`, `{players}` (earned/lost); `{poll}`, `{mode}`, `{source}` (startup).
Transitions are always written to the server log regardless of these settings.

On startup/reload the plugin also warns if the configured `luckperms.group` doesn't exist (otherwise
the granted node would resolve to nothing on the backend).

## How it works

Each cycle (`poll-interval-seconds`):

1. Fetch members of `member-guild-id` from Discord, keeping those whose `primary_guild` matches
   `tag-guild-id` with `identity_enabled == true` — the tag wearers.
2. Resolve each wearer's Discord id to a Minecraft name (manual map or limboauth cache) and to a
   UUID via LuckPerms.
3. Grant a temporary `group.<group>` node, re-writing it only when it's missing or within
   `renew-before-seconds` of expiry (so most cycles write nothing).
4. When a wearer drops the tag: `expiry` mode lets the temp node lapse on its own; `explicit` mode
   removes it on the next cycle.

If Discord is unreachable or rate-limited, the cycle is skipped and permissions are left untouched
(in `expiry` mode they self-heal). With shared LuckPerms storage + a messaging service
(`pluginmsg`/`sql`/redis), the group propagates to backend servers automatically.

## Going to production

1. Use your live server's Server Tag id as `tag-guild-id`; `member-guild-id` is the guild your bot
   is in.
2. Keep the bot token out of version control (rotate it if it ever leaks).
3. Switch to `link.source: limboauth` and confirm your SocialAddon/LimboAuth schema matches the query.
4. Ensure LuckPerms storage is shared between the proxy and all backends.

## License

[MIT](LICENSE)
