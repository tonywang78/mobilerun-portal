package com.carrun.portal.input

import org.junit.Assert.assertEquals
import org.junit.Test

class CarrunKeyboardIMETest {
    @Test
    fun restartingInput_advancesGeneration() {
        val ime = CarrunKeyboardIME()
        val before = generationOf(ime)

        invokeStartInput(ime, restarting = true)

        assertEquals(before + 1, generationOf(ime))
    }

    private fun invokeStartInput(ime: CarrunKeyboardIME, restarting: Boolean) {
        try {
            ime.onStartInput(null, restarting)
        } catch (error: RuntimeException) {
            if (!error.message.orEmpty().contains("not mocked")) {
                throw error
            }
        }
    }

    private fun generationOf(ime: CarrunKeyboardIME): Long {
        val field = CarrunKeyboardIME::class.java.getDeclaredField("inputGeneration")
        field.isAccessible = true
        return field.getLong(ime)
    }
}
