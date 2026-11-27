package dev.flickime.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class JaNumbersTest {
    private fun value(r: String) = JaNumbers.at(r, 0).lastOrNull { it.first == r.length }?.second

    @Test fun readsNumbers() {
        assertEquals("5", value("ご"))
        assertEquals("18", value("じゅうはち"))
        assertEquals("300", value("さんびゃく"))
        assertEquals("600", value("ろっぴゃく"))
        assertEquals("8000", value("はっせん"))
        assertEquals("1", value("いっ"))
        assertEquals("10", value("じゅっ"))
        assertEquals("3つ", value("みっつ"))
        assertEquals("20000", value("にまん"))
        assertEquals("25000", value("にまんごせん"))
        assertEquals("1999", value("せんきゅうひゃくきゅうじゅうきゅう"))
    }
}
