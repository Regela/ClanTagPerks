package me.regela.clantagperks;

/** Where Discord id -> Minecraft name links come from. */
public enum LinkSource {
    /** In-memory map from config (testing). */
    MANUAL,
    /** Bulk SELECT from the LimboAuth/SocialAddon database, cached (production). */
    LIMBOAUTH
}
