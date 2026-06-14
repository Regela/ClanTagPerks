# ClanTagPerks

Velocity plugin that grants a LuckPerms group to a player while they wear your Discord
**Server Tag**, and lets it lapse when the tag is removed. Polls the Discord REST API
(`GET /guilds/{id}/members` → `primary_guild`), maps Discord → Minecraft, and applies a
short-lived (temp) LuckPerms node with the minimum number of DB writes.

## Build

JDK 21 required. From the project root:

```bash
JAVA_HOME=$HOME/.jdks/azul-21.0.8 ./gradlew shadowJar
# -> build/libs/clantag-perks-1.0.0.jar
```

The shadow jar bundles only the MariaDB JDBC driver (relocated to
`me.regela.clantagperks.libs.mariadb`); `gson` and `snakeyaml` are provided by Velocity at
runtime and are NOT shaded. Drop the jar into `velocity/plugins/` and restart the proxy.

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

- If `discord.log-channel-id` is set, the plugin posts a line to that channel on startup and on each
  perk transition (`✅ earned` / `➖ lost`) — renewals are silent.
- On startup/reload the plugin warns if the configured `luckperms.group` doesn't exist (otherwise the
  granted node would resolve to nothing on the backend).

## Verified end-to-end (test rig on this host)

Velocity `:25577` + Paper 1.21.4 `:25566`, LuckPerms 5.5.55 on shared MariaDB, `pluginmsg`
sync. Test config: `member-guild-id=1515767191948103892`, `tag-guild-id=523059903812599811`
(HYTL), `link.source=manual` (`356101524486619148 -> Regela`).

1. Plugin enables: `ClanTagPerks enabled, poll=…s, mode=expiry, source=manual`.
2. Poll detects wearer: `cycle ok: wearing=1, linked=1`.
3. Player `Regela` joins → next poll grants temp `group.clantag`; node appears on **both**
   proxy and Paper backend (shared storage + sync); backend resolves prefix `[HYTL] `.
4. Tag removed (simulated) → plugin stops renewing → node expires → perk gone on both sides.
5. Tag restored → node re-granted on the next cycle.

## Production switch-over

1. Own Server Tag for the live server → `tag-guild-id` = live server id; `member-guild-id` =
   the guild your bot is in.
2. Reset the bot token (Developer Portal → Bot → Reset Token); put it in config, not git.
3. `link.source: limboauth` and verify the SocialAddon schema/query.
4. Ensure LuckPerms storage is shared between the proxy and all backends.
