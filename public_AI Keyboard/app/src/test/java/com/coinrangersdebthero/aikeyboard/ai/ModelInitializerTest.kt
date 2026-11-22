package com.coinrangersdebthero.aikeyboard.ai

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelInitializerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun copiesBundledGgufModelIntoPrivateModelLocation() {
        val modelBytes = byteArrayOf(0x47, 0x47, 0x55, 0x46, 1, 2, 3)
        val destination = temporaryFolder.newFolder("models")
            .resolve(ModelInitializer.MODEL_FILE_NAME)

        ModelInitializer.copyBundledModel(ByteArrayInputStream(modelBytes), destination)

        assertArrayEquals(modelBytes, destination.readBytes())
    }

    @Test
    fun rejectsInvalidModelWithoutLeavingPartialDestination() {
        val destination = temporaryFolder.newFolder("models")
            .resolve(ModelInitializer.MODEL_FILE_NAME)

        try {
            ModelInitializer.copyBundledModel(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                destination
            )
        } catch (_: IOException) {
            assertFalse(destination.exists())
            return
        }
        throw AssertionError("Invalid GGUF model was accepted.")
    }
}
