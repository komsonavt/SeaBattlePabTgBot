package com.company.seabattle.state

import kotlin.test.*

class CommunityStoreTest {
    @Test fun `profile display name handles missing last name`() {
        assertEquals("Анна", PlayerProfile(1,"Анна").displayName)
        assertEquals("Игрок 2", PlayerProfile(2,"",null).displayName)
    }
    @Test fun `CSV escapes formula and quotation injection`() {
        assertEquals("\"'=2+2\"", CommunityStore.csvCell("=2+2"))
        assertEquals("\"O\"\"Connor\"", CommunityStore.csvCell("O\"Connor"))
    }
}
