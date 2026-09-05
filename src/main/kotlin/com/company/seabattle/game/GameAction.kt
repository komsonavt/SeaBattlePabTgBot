package com.company.seabattle.game

data class GameAction(val gameId: String, val revision: Long, val type: String, val value: Int) {
    fun encode(): String = "game:$gameId:$revision:$type:$value".also {
        require(it.toByteArray(Charsets.UTF_8).size <= 64)
    }
    companion object {
        fun parse(data: String): GameAction? {
            if (data.toByteArray(Charsets.UTF_8).size > 64) return null
            val parts = data.split(':')
            if (parts.size != 5 || parts[0] != "game" || !parts[1].matches(Regex("[a-f0-9]{32}"))) return null
            val revision = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val value = parts[4].toIntOrNull() ?: return null
            if (parts[3] == "fire" && value in 0..99 || parts[3] == "half" && value in 0..1 ||
                parts[3] in listOf("surrender", "confirm", "cancel") && value==0)
                return GameAction(parts[1], revision, parts[3], value)
            return null
        }
    }
}
