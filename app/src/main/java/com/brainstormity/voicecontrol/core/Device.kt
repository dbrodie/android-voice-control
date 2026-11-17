package com.brainstormity.voicecontrol.core

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

data class ScreenSize(val width: Int, val height: Int)

/** The eyes. Full-resolution screenshot of the whole display. */
interface Screen {
    suspend fun screenshot(): Bitmap
    fun screenSize(): ScreenSize
    /** Additive (Phase 2): the accessibility node tree at this moment, or null if the service can't read it. */
    suspend fun uiTree(): UiTree? = null
}

/** The hands. Coordinates are real screen pixels. Each call returns when the gesture has been dispatched. */
interface Hands {
    suspend fun tap(x: Int, y: Int)
    suspend fun longPress(x: Int, y: Int)
    suspend fun swipe(direction: SwipeDirection)        // UP = finger moves up (scrolls content down)
    suspend fun allApps()                               // home, then swipe up from bottom edge
    suspend fun back()
    suspend fun home()
    suspend fun enter()
    suspend fun typeText(text: String)                  // into the focused field
    /**
     * Additive (T5): replace the focused field's whole content with [text], for a field the agent has just
     * tapped (a search box still holding an old query). typeText appends at the cursor like a keyboard, which
     * produced "weather in Berlinbest pizza in Rome". Default: plain [typeText].
     */
    suspend fun replaceText(text: String) = typeText(text)
    suspend fun openApp(packageName: String)
    suspend fun openUrl(url: String)
    fun installedApps(): Set<String>                    // launchable package names
}

/** Where control/ registers itself so agent/ can find the live service. */
object Device {
    val screen: MutableStateFlow<Screen?> = MutableStateFlow(null)
    val hands: MutableStateFlow<Hands?> = MutableStateFlow(null)
    val isReady: Boolean get() = screen.value != null && hands.value != null
}
