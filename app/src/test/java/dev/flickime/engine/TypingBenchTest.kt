package dev.flickime.engine
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
/** Types AJIMEE readings one character at a time with zenz on every keystroke (as the IME does); total time. */
class TypingBenchTest {
    @Test fun run() {
        val lib = System.getenv("ZENZ_LIB"); val model = System.getenv("ZENZ_MODEL"); val path = System.getenv("AJIMEE")
        assumeTrue(lib != null && model != null && path != null)
        System.load(lib)
        val f = ConverterTest::class.java.getDeclaredMethod("getConv").apply { isAccessible = true }
        val conv = f.invoke(ConverterTest()) as Converter
        conv.zenz = Zenz.load(model!!, 2); conv.zenzGenerate = true
        val items = File(path!!).readLines().take(20).map { it.split('\t') }
        var keys = 0; val t0 = System.nanoTime()
        for (p in items) { val r = p[1]; val ctx = p[2].replace("\\n", "\n"); for (k in 1..r.length) { conv.zenzRerank(r.substring(0, k), ctx); keys++ } }
        println("TYPING keys=$keys ${(System.nanoTime() - t0) / 1_000_000 / keys} ms/key reuse=${System.getenv("ZENZ_NOREUSE") == null}")
    }
}
