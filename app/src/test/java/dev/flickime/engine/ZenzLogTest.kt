package dev.flickime.engine
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
/** zenz rerank with real context on the user's log (dev+test targets of events.tsv). Needs ZENZ_LIB, ZENZ_MODEL, EVAL_DIR. */
class ZenzLogTest {
    @Test fun run() {
        val lib = System.getenv("ZENZ_LIB"); val model = System.getenv("ZENZ_MODEL"); val dir = System.getenv("EVAL_DIR")
        assumeTrue(lib != null && model != null && dir != null)
        System.load(lib)
        if (System.getenv("ZENZ_V2") == "1") Zenz.v2Prompt = true
        val f = ConverterTest::class.java.getDeclaredMethod("getConv").apply { isAccessible = true }
        val conv = f.invoke(ConverterTest()) as Converter
        conv.zenz = Zenz.load(model!!, 2)
        conv.zenzGenerate = System.getenv("ZENZ_GEN") == "1"
        fun unesc(s: String) = s.replace("\\n", "\n").replace("\\t", "\t")
        var n = 0; var ok = 0; var okNoCtx = 0; var base = 0
        for (line in File(dir, "events.tsv").readLines()) {
            val p = line.split('\t')
            if (p[2] != "1" || p[1] == "warm") continue
            val r = unesc(p[3]); val s = unesc(p[4]); val ctx = unesc(p.getOrElse(6) { "" })
            n++
            if (conv.sentences(r, "").first().surface == s) base++   // n-gram (zenz not run yet for this key)
            conv.zenzRerank(r, ctx); if (conv.sentences(r, ctx).first().surface == s) ok++
            conv.zenzRerank(r, ""); if (conv.sentences(r, "").first().surface == s) okNoCtx++
        }
        println("ZLOG n=$n ngram=${100.0 * base / n} zenz+ctx=${100.0 * ok / n} zenz-noctx=${100.0 * okNoCtx / n}")
    }
}
