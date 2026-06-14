package me.regela.clantagperks;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

/** {@code /clantag reload|status|check <player>} — requires {@code clantagperks.admin}. */
public final class CommandHandler implements SimpleCommand {

    private final ClanTagPerks plugin;

    public CommandHandler(ClanTagPerks plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("clantagperks.admin");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) return List.of("reload", "status", "check");
        return List.of();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();

        switch (sub) {
            case "reload" -> {
                try {
                    plugin.reload();
                    reply(src, NamedTextColor.GREEN, "ClanTagPerks config reloaded.");
                } catch (Exception e) {
                    reply(src, NamedTextColor.RED, "Reload failed: " + e.getMessage());
                }
            }
            case "status" -> {
                PollTask t = plugin.pollTask();
                if (t == null) { reply(src, NamedTextColor.RED, "Not initialized yet."); return; }
                long age = t.lastRunEpoch() == 0 ? -1 : (System.currentTimeMillis() / 1000L - t.lastRunEpoch());
                reply(src, NamedTextColor.AQUA, "ClanTagPerks status:");
                reply(src, NamedTextColor.GRAY, "  wearing tag: " + t.lastWearing());
                reply(src, NamedTextColor.GRAY, "  linked accounts: " + t.lastLinked());
                reply(src, NamedTextColor.GRAY, "  currently granted: " + t.grantedCount());
                reply(src, NamedTextColor.GRAY, "  last cycle: " + (age < 0 ? "never" : age + "s ago")
                        + (t.lastCycleSkipped() ? " (SKIPPED — Discord unreachable)" : ""));
            }
            case "check" -> {
                if (args.length < 2) { reply(src, NamedTextColor.RED, "Usage: /clantag check <player>"); return; }
                final String name = args[1];
                reply(src, NamedTextColor.GRAY, "Checking '" + name + "'…");
                // Off the calling thread: checkPlayer() performs a blocking Discord poll.
                plugin.runAsync(() -> {
                    String result = plugin.pollTask().checkPlayer(name);
                    reply(src, NamedTextColor.YELLOW, result);
                });
            }
            default -> reply(src, NamedTextColor.RED, "Unknown subcommand. Use: reload | status | check <player>");
        }
    }

    private void reply(CommandSource src, NamedTextColor color, String msg) {
        src.sendMessage(Component.text(msg, color));
    }
}
