package com.carrun.portal.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrunAccessibilityServiceTest {

    @Test
    fun recycleAccessibilityRoots_continuesAfterFailure() {
        val failingRoot = mockk<AccessibilityNodeInfo>()
        val laterRoot = mockk<AccessibilityNodeInfo>()
        every { failingRoot.recycle() } throws RuntimeException("stale root")
        every { laterRoot.recycle() } returns Unit

        CarrunAccessibilityService.recycleAccessibilityRoots(
            listOf(failingRoot, laterRoot),
        )

        verify(exactly = 1) { failingRoot.recycle() }
        verify(exactly = 1) { laterRoot.recycle() }
    }

    @Test
    fun updateScreenBounds_overwritesStalePortraitBoundsAfterLandscapeRotation() {
        val bounds = rect(1080, 2400)

        val changed = CarrunAccessibilityService.updateScreenBounds(bounds, 2400, 1080)

        assertTrue(changed)
        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(2400, bounds.right)
        assertEquals(1080, bounds.bottom)
    }

    @Test
    fun updateScreenBounds_reportsUnchangedWhenDimensionsMatch() {
        val bounds = rect(2400, 1080)

        val changed = CarrunAccessibilityService.updateScreenBounds(bounds, 2400, 1080)

        assertFalse(changed)
        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(2400, bounds.right)
        assertEquals(1080, bounds.bottom)
    }

    private fun rect(width: Int, height: Int): Rect {
        return Rect().apply {
            left = 0
            top = 0
            right = width
            bottom = height
        }
    }

    @Test
    fun shouldReuseVisibleElementsSnapshot_allowsFreshSameScreenCache() {
        val result = CarrunAccessibilityService.shouldReuseVisibleElementsSnapshot(
            cachedElementCount = 1,
            snapshotTimeMs = 1_000L,
            nowMs = 1_000L + CarrunAccessibilityService.VISIBLE_ELEMENTS_STALE_GRACE_MS,
            snapshotPackageName = "com.example.app",
            currentPackageName = "com.example.app",
            snapshotActivityName = "MainActivity",
            currentActivityName = "MainActivity",
            snapshotScreenWidth = 2400,
            currentScreenWidth = 2400,
            snapshotScreenHeight = 1080,
            currentScreenHeight = 1080,
        )

        assertTrue(result)
    }

    @Test
    fun shouldReuseVisibleElementsSnapshot_rejectsExpiredCache() {
        val result = CarrunAccessibilityService.shouldReuseVisibleElementsSnapshot(
            cachedElementCount = 1,
            snapshotTimeMs = 1_000L,
            nowMs = 1_001L + CarrunAccessibilityService.VISIBLE_ELEMENTS_STALE_GRACE_MS,
            snapshotPackageName = "com.example.app",
            currentPackageName = "com.example.app",
            snapshotActivityName = "MainActivity",
            currentActivityName = "MainActivity",
            snapshotScreenWidth = 2400,
            currentScreenWidth = 2400,
            snapshotScreenHeight = 1080,
            currentScreenHeight = 1080,
        )

        assertFalse(result)
    }

    @Test
    fun shouldReuseVisibleElementsSnapshot_rejectsPackageOrActivityMismatch() {
        val packageMismatch = CarrunAccessibilityService.shouldReuseVisibleElementsSnapshot(
            cachedElementCount = 1,
            snapshotTimeMs = 1_000L,
            nowMs = 1_100L,
            snapshotPackageName = "com.example.app",
            currentPackageName = "com.example.other",
            snapshotActivityName = "MainActivity",
            currentActivityName = "MainActivity",
            snapshotScreenWidth = 2400,
            currentScreenWidth = 2400,
            snapshotScreenHeight = 1080,
            currentScreenHeight = 1080,
        )
        val activityMismatch = CarrunAccessibilityService.shouldReuseVisibleElementsSnapshot(
            cachedElementCount = 1,
            snapshotTimeMs = 1_000L,
            nowMs = 1_100L,
            snapshotPackageName = "com.example.app",
            currentPackageName = "com.example.app",
            snapshotActivityName = "MainActivity",
            currentActivityName = "OtherActivity",
            snapshotScreenWidth = 2400,
            currentScreenWidth = 2400,
            snapshotScreenHeight = 1080,
            currentScreenHeight = 1080,
        )

        assertFalse(packageMismatch)
        assertFalse(activityMismatch)
    }

    @Test
    fun shouldReuseVisibleElementsSnapshot_rejectsScreenBoundsMismatch() {
        val result = CarrunAccessibilityService.shouldReuseVisibleElementsSnapshot(
            cachedElementCount = 1,
            snapshotTimeMs = 1_000L,
            nowMs = 1_100L,
            snapshotPackageName = "com.example.app",
            currentPackageName = "com.example.app",
            snapshotActivityName = "MainActivity",
            currentActivityName = "MainActivity",
            snapshotScreenWidth = 1080,
            currentScreenWidth = 2400,
            snapshotScreenHeight = 2400,
            currentScreenHeight = 1080,
        )

        assertFalse(result)
    }

    @Test
    fun shouldReuseVisibleElementsSnapshot_rejectsEmptyCache() {
        val result = CarrunAccessibilityService.shouldReuseVisibleElementsSnapshot(
            cachedElementCount = 0,
            snapshotTimeMs = 1_000L,
            nowMs = 1_100L,
            snapshotPackageName = "com.example.app",
            currentPackageName = "com.example.app",
            snapshotActivityName = "MainActivity",
            currentActivityName = "MainActivity",
            snapshotScreenWidth = 2400,
            currentScreenWidth = 2400,
            snapshotScreenHeight = 1080,
            currentScreenHeight = 1080,
        )

        assertFalse(result)
    }

    @Test
    fun testCalculateInputText_Replace() {
        // clear=true should always return newText
        val result = CarrunAccessibilityService.calculateInputText("old", "hint", "new", true)
        assertEquals("new", result)
    }

    @Test
    fun testCalculateInputText_Append() {
        // clear=false should append
        val result = CarrunAccessibilityService.calculateInputText("old", "hint", "new", false)
        assertEquals("oldnew", result)
    }

    @Test
    fun testCalculateInputText_AppendWithNulls() {
        val result = CarrunAccessibilityService.calculateInputText(null, null, "new", false)
        assertEquals("new", result)
    }

    @Test
    fun testCalculateInputText_SmartHint() {
        // Case: Text equals Hint -> Treat as empty (Prevent "Searchsome-text")
        val result = CarrunAccessibilityService.calculateInputText("Search", "Search", "query", false)
        assertEquals("query", result)
    }

    @Test
    fun testCalculateInputText_SmartHintMismatch() {
        // Case: Text does NOT equal Hint -> Append normally
        val result = CarrunAccessibilityService.calculateInputText("Existing", "Search", "query", false)
        assertEquals("Existingquery", result)
    }

    @Test
    fun testCalculateInputText_InsertAtCursor() {
        val result = CarrunAccessibilityService.calculateInputText(
            currentText = "hello world",
            hintText = null,
            newText = " brave",
            clear = false,
            selectionStart = 5,
            selectionEnd = 5,
        )
        assertEquals("hello brave world", result)
    }

    @Test
    fun testCalculateInputText_ReplaceSelection() {
        val result = CarrunAccessibilityService.calculateInputText(
            currentText = "hello world",
            hintText = null,
            newText = "planet",
            clear = false,
            selectionStart = 6,
            selectionEnd = 11,
        )
        assertEquals("hello planet", result)
    }

    @Test
    fun testCalculateDeleteText_BackspaceAtCursor() {
        val result = CarrunAccessibilityService.calculateDeleteText(
            currentText = "hello",
            hintText = null,
            count = 1,
            forward = false,
            selectionStart = 3,
            selectionEnd = 3,
        )
        assertEquals("helo", result)
    }

    @Test
    fun testCalculateDeleteText_DeleteSelection() {
        val result = CarrunAccessibilityService.calculateDeleteText(
            currentText = "hello world",
            hintText = null,
            count = 1,
            forward = false,
            selectionStart = 5,
            selectionEnd = 11,
        )
        assertEquals("hello", result)
    }

    @Test
    fun testCalculateDeleteText_ForwardDeleteAtCursor() {
        val result = CarrunAccessibilityService.calculateDeleteText(
            currentText = "hello",
            hintText = null,
            count = 1,
            forward = true,
            selectionStart = 2,
            selectionEnd = 2,
        )
        assertEquals("helo", result)
    }

    @Test
    fun testCalculateDeleteText_DeleteReversedSelection() {
        val result = CarrunAccessibilityService.calculateDeleteText(
            currentText = "hello world",
            hintText = null,
            count = 1,
            forward = false,
            selectionStart = 11,
            selectionEnd = 5,
        )
        assertEquals("hello", result)
    }
}
