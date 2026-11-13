package com.brainstormity.voicecontrol.core

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One on-screen overlay window. [rect] is in real screen pixels (the same coordinate space as
 * [Screen.screenshot] and [Hands.tap]). [touchable] = taps there are swallowed by the overlay instead of
 * reaching the app underneath.
 */
data class OverlayRegion(val name: String, val rect: Rect, val touchable: Boolean)

/**
 * Published by overlay/ (OverlayService) every time its windows change, so agent/ or control/ can tell the
 * model what the overlay is, mask it, or avoid tapping on it. Empty when the overlay isn't showing.
 *
 * Region names: "bubble" (touchable), "pill" (not touchable), "strip" (not touchable, shown while a task
 * is Running), "stop" (touchable, the ■ button at the right end of the strip).
 */
object OverlayInfo {
    val regions: MutableStateFlow<List<OverlayRegion>> = MutableStateFlow(emptyList())
}
