package com.company.seabattle.access

import kotlin.test.Test
import kotlin.test.assertEquals

class AccessGuardTest {
    @Test fun `membership statuses distinguish absence and API uncertainty`() {
        assertEquals(AccessResult.ALLOWED, AccessGuard.membership("member"))
        assertEquals(AccessResult.ALLOWED, AccessGuard.membership("restricted", true))
        assertEquals(AccessResult.NOT_MEMBER, AccessGuard.membership("restricted", false))
        assertEquals(AccessResult.NOT_MEMBER, AccessGuard.membership("left"))
        assertEquals(AccessResult.UNAVAILABLE, AccessGuard.membership("unexpected"))
    }
}
