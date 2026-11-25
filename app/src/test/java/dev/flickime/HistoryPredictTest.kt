package dev.flickime

import dev.flickime.engine.UserHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPredictTest {
    @Test fun completionsSkipNoise() {
        val h = UserHistory()
        h.learn("ぎんがが", "ぎんがが")          // bare kana commit
        h.learn("ぎっとはぶ", "GitHub")          // picked once
        assertEquals(emptyList<String>(), h.completions("ぎ", 4).map { it.second })
        assertEquals(listOf("GitHub"), h.completions("ぎっ", 4).map { it.second })
        h.learn("ぎっとはぶ", "GitHub")          // picked twice
        assertEquals(listOf("GitHub"), h.completions("ぎ", 4).map { it.second })
        h.forget("ぎっとはぶ", "GitHub")
        assertEquals(emptyList<String>(), h.completions("ぎっ", 4).map { it.second })
    }
}
