package com.bluup.manifestation.server.splinter

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ManifestationSplinterFrameContextTest {
    @Test
    fun requireCurrentFailsOutsideContext() {
        assertFailsWith<IllegalStateException> {
            ManifestationSplinterFrameContext.requireCurrent()
        }
    }

    @Test
    fun contextDoesNotCarryExternalizedInnerStepsPerEvaluate() {
        val hasProperty = ManifestationSplinterFrameContext.Context::class.java.declaredFields
            .any { it.name == "externalizedForEachInnerStepsPerEvaluate" }

        assertFalse(hasProperty)
    }
}
