package me.regela.clantagperks;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "clantagperks",
        name = "ClanTagPerks",
        version = "1.0.0",
        description = "Grants a LuckPerms group while a player wears the Discord Server Tag.",
        authors = {"regela"},
        dependencies = {@com.velocitypowered.api.plugin.Dependency(id = "luckperms")}
)
public final class ClanTagPerks {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDir;

    private LuckPerms luckPerms;
    private volatile Config config;
    private volatile PollTask pollTask;
    private ScheduledTask scheduledTask;

    @Inject
    public ClanTagPerks(ProxyServer server, Logger logger, @DataDirectory Path dataDir) {
        this.server = server;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            this.luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            logger.error("[ClanTagPerks] LuckPerms API not available — is LuckPerms installed? Disabling.", e);
            return;
        }
        try {
            reload();
        } catch (Exception e) {
            logger.error("[ClanTagPerks] failed to start: {}", e.getMessage(), e);
            return;
        }

        CommandManager cm = server.getCommandManager();
        CommandMeta meta = cm.metaBuilder("clantag").plugin(this).build();
        cm.register(meta, new CommandHandler(this));

        logger.info("[ClanTagPerks] enabled, poll={}s, mode={}, source={}",
                config.pollIntervalSeconds, config.mode, config.linkSource);
        if (config.notifyOnStartup) {
            pollTask.announce(config.msgStartup
                    .replace("{poll}", String.valueOf(config.pollIntervalSeconds))
                    .replace("{mode}", config.mode)
                    .replace("{source}", config.linkSource));
        }
    }

    /** (Re)loads config and (re)builds the polling pipeline. Safe to call at runtime. */
    public synchronized void reload() throws Exception {
        Config cfg = ConfigLoader.load(dataDir);

        DiscordPoller poller = new DiscordPoller(cfg, logger);
        LinkResolver links = new LinkResolver(cfg, logger);
        if (cfg.linkSource.equals("limboauth")) links.refresh();
        PermissionApplier applier = new PermissionApplier(luckPerms, cfg, logger);
        PollTask task = new PollTask(cfg, logger, poller, links, applier);

        // Warn loudly if the configured group doesn't exist — otherwise we'd silently grant a
        // group node that resolves to nothing on the backend.
        try {
            if (luckPerms.getGroupManager().loadGroup(cfg.group).join().isEmpty()) {
                logger.warn("[ClanTagPerks] LuckPerms group '{}' does not exist — create it with "
                        + "'/lp creategroup {}' or the perk will have no effect.", cfg.group, cfg.group);
            }
        } catch (Exception e) {
            logger.warn("[ClanTagPerks] could not verify group '{}': {}", cfg.group, e.getMessage());
        }

        if (scheduledTask != null) scheduledTask.cancel();
        this.config = cfg;
        this.pollTask = task;
        this.scheduledTask = server.getScheduler()
                .buildTask(this, task)
                .delay(5, TimeUnit.SECONDS)                       // let LuckPerms settle first
                .repeat(cfg.pollIntervalSeconds, TimeUnit.SECONDS)
                .schedule();
    }

    public PollTask pollTask() {
        return pollTask;
    }

    /** Runs a blocking job (e.g. on-demand poll) off the caller's thread, in Velocity's async pool. */
    public void runAsync(Runnable r) {
        server.getScheduler().buildTask(this, r).schedule();
    }
}
