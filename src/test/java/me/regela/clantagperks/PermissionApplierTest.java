package me.regela.clantagperks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure renew-rule logic (no LuckPerms required). */
class PermissionApplierTest {

    private static final int RENEW_BEFORE = 180;

    @Test
    void untrackedUuidAlwaysNeedsGrant() {
        assertTrue(PermissionApplier.needsGrant(null, 1_000L, RENEW_BEFORE));
    }

    @Test
    void freshNodeDoesNotNeedRenewal() {
        long now = 1_000L;
        long expiry = now + 600; // far from expiry
        assertFalse(PermissionApplier.needsGrant(expiry, now, RENEW_BEFORE));
    }

    @Test
    void nodeWithinRenewWindowNeedsRenewal() {
        long now = 1_000L;
        long expiry = now + 100; // less than renew-before-seconds remaining
        assertTrue(PermissionApplier.needsGrant(expiry, now, RENEW_BEFORE));
    }

    @Test
    void expiredNodeNeedsRenewal() {
        long now = 1_000L;
        assertTrue(PermissionApplier.needsGrant(now - 5, now, RENEW_BEFORE));
    }
}
