package dev.flickime.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Gradle entry for the prediction benchmark ([PredEval], tools/predeval/PredEval.kt).
 *   EVAL_DIR   directory with events.tsv (absolute path; required, else skipped)
 *   EVAL_SPLIT dev (default) | test
 *   EVAL_BAR   candidates visible in the bar (default 5)
 *   EVAL_OUT   optional file: one line per target with where it first appeared
 * Without Gradle / Android SDK: tools/predeval/run.sh (needs only a JDK).
 */
class PredictEvalTest {
    @Test fun predictEval() {
        val dir = System.getenv("EVAL_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "events.tsv").exists())
        println(PredEval.run(File("src/main/assets"), dir!!, System.getenv("EVAL_SPLIT") ?: "dev",
            (System.getenv("EVAL_BAR") ?: "0").toInt(), System.getenv("EVAL_OUT")?.let(::File)))
    }
}
