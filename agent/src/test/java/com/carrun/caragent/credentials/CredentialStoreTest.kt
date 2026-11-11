package com.carrun.caragent.credentials

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStoreTest {
    private val store = CredentialStore()

    @Test
    fun doesNotMatchIsPasswordJsonField() {
        val json = """{"isPassword":false,"text":"Car GUI Agent","packageName":"com.carrun.caragent"}"""
        assertFalse(store.needsUserForCredentialScreen(json))
    }

    @Test
    fun matchesVisiblePasswordLabel() {
        assertTrue(store.needsUserForCredentialScreen("""{"text":"Enter password"}"""))
    }

    @Test
    fun matchesSignInLabel() {
        assertTrue(store.needsUserForCredentialScreen("""{"text":"Sign In"}"""))
    }
}
