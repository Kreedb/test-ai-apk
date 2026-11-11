package com.example.minimalapk.litert.text

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

class TextDecisionSmokeTest {
    /**
     * adb shell am instrument -w -e class com.example.minimalapk.litert.text.TextDecisionSmokeTest
     *   -e textDecisionModelDir /path/readable/by/test -e textDecisionBackend cpu
     *   com.example.minimalapk.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test fun pinnedGliclassTextToDecisionAndFreshEngine() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("textDecisionModelDir")
        assumeTrue("Set textDecisionModelDir to the hash-verified GLiClass-Edge task directory.", !path.isNullOrBlank())
        val backend = args.getString("textDecisionBackend") ?: "cpu"
        println(TextDecisionModelSmoke.run(File(checkNotNull(path)), backend))
    }
}
