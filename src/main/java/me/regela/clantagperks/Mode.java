package me.regela.clantagperks;

/** How the perk is taken away when a player drops the tag. */
public enum Mode {
    /** Let the temp LuckPerms node lapse on its own (recommended; self-heals). */
    EXPIRY,
    /** Remove the node ourselves on the next cycle (diff-based). */
    EXPLICIT
}
