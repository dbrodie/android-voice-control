package com.brainstormity.voicecontrol.control

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.SystemClock
import android.view.Display
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Full-resolution screenshots through [AccessibilityService.takeScreenshot].
 *
 * The OS allows one request per ~333 ms per service (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT otherwise),
 * so requests are serialized and spaced at least [MIN_INTERVAL_MS] apart; if the OS still rejects one,
 * it is retried after a short delay. Back-to-back calls therefore take >= ~350 ms each.
 */
internal class ScreenCapturer(private val service: AccessibilityService) {

    private val mutex = Mutex()
    private var lastRequestAt = 0L // SystemClock.elapsedRealtime() of the last request sent to the OS
    private val executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vc-screenshot") }
    private var loggedColorSpace = false

    private sealed class Outcome {
        class Ok(val buffer: HardwareBuffer, val colorSpace: ColorSpace) : Outcome()
        class Failed(val code: Int) : Outcome()
    }

    suspend fun capture(): Bitmap = mutex.withLock {
        val t0 = SystemClock.elapsedRealtime()
        var rateLimited = 0
        var internalErrors = 0
        while (true) {
            val wait = lastRequestAt + MIN_INTERVAL_MS - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            lastRequestAt = SystemClock.elapsedRealtime()
            val tReq = lastRequestAt
            val outcome = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { requestOnce() } ?: Outcome.Failed(-2)
            when (outcome) {
                is Outcome.Ok -> {
                    val tGot = SystemClock.elapsedRealtime()
                    val bitmap = toSoftwareBitmap(outcome)
                    val tEnd = SystemClock.elapsedRealtime()
                    VcLog.d(
                        TAG, "screenshot ${bitmap.width}x${bitmap.height} total=${tEnd - t0}ms " +
                            "(waited=${tReq - t0}ms os=${tGot - tReq}ms copy=${tEnd - tGot}ms" +
                            (if (rateLimited > 0) " rateLimitedRetries=$rateLimited" else "") + ")"
                    )
                    return@withLock bitmap
                }
                is Outcome.Failed -> {
                    if (outcome.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT &&
                        rateLimited < MAX_RATE_LIMIT_RETRIES
                    ) {
                        rateLimited++
                        VcLog.d(TAG, "screenshot rate-limited by the OS, retry #$rateLimited in ${RETRY_DELAY_MS}ms")
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    // INTERNAL_ERROR can be a passing glitch (display transition): try a couple more times.
                    if (outcome.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR &&
                        internalErrors < MAX_INTERNAL_ERROR_RETRIES
                    ) {
                        internalErrors++
                        VcLog.w(TAG, "screenshot INTERNAL_ERROR, retry #$internalErrors in ${INTERNAL_ERROR_RETRY_MS}ms")
                        delay(INTERNAL_ERROR_RETRY_MS)
                        continue
                    }
                    val hint = when (outcome.code) {
                        // Seen on the emulator after reinstalling the app while the service was enabled: every
                        // request fails until the service is re-bound.
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR, -2 ->
                            ". Turn Voice Control's accessibility service off and on again (Settings > Accessibility)."
                        else -> ""
                    }
                    val msg = "Can't take a screenshot: ${errorName(outcome.code)} (code ${outcome.code})$hint"
                    VcLog.e(TAG, msg)
                    throw IllegalStateException(msg)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun requestOnce(): Outcome = suspendCancellableCoroutine { cont ->
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY, executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val cs = result.colorSpace
                        if (cont.isActive) cont.resume(Outcome.Ok(buffer, cs)) { _, _, _ -> buffer.close() }
                        else buffer.close()
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(Outcome.Failed(errorCode))
                    }
                },
            )
        } catch (e: Exception) {
            // e.g. SecurityException if canTakeScreenshot is missing from the service config.
            VcLog.e(TAG, "takeScreenshot threw", e)
            if (cont.isActive) cont.resume(Outcome.Failed(-1))
        }
    }

    private fun toSoftwareBitmap(ok: Outcome.Ok): Bitmap {
        try {
            if (!loggedColorSpace) {
                loggedColorSpace = true
                VcLog.i(TAG, "screenshot colorSpace=${ok.colorSpace.name} buffer=${ok.buffer.width}x${ok.buffer.height}")
            }
            val hw = Bitmap.wrapHardwareBuffer(ok.buffer, ok.colorSpace)
                ?: throw IllegalStateException("takeScreenshot: Bitmap.wrapHardwareBuffer returned null")
            try {
                return hw.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("takeScreenshot: copying the hardware bitmap failed")
            } finally {
                hw.recycle()
            }
        } finally {
            ok.buffer.close()
        }
    }

    private fun errorName(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS (canTakeScreenshot missing?)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT (rate limited; gave up after retries)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "INVALID_WINDOW"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SECURE_WINDOW (FLAG_SECURE content on screen)"
        -1 -> "EXCEPTION"
        -2 -> "TIMEOUT (no callback within ${REQUEST_TIMEOUT_MS}ms; service disconnected?)"
        else -> "UNKNOWN"
    }

    companion object {
        private const val TAG = "Hands"
        /** OS limit is 333 ms between requests (AccessibilityService rate limit); keep a little margin. */
        const val MIN_INTERVAL_MS = 345L
        private const val RETRY_DELAY_MS = 120L
        private const val MAX_RATE_LIMIT_RETRIES = 10
        private const val MAX_INTERNAL_ERROR_RETRIES = 2
        private const val INTERNAL_ERROR_RETRY_MS = 400L
        private const val REQUEST_TIMEOUT_MS = 5000L
    }
}
