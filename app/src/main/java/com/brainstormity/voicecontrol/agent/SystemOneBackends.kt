package com.brainstormity.voicecontrol.agent

import android.annotation.SuppressLint
import android.content.Context
import com.brainstormity.voicecontrol.BuildConfig
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.VcLog

/**
 * Registry of [SystemOne] backends and the chooser behind the "Next-step model" setting
 * ([AppSettings.systemOneBackend]: `auto` | `jev` | `laya`).
 *
 * Resolution ([resolveId]): an explicit choice wins, even if that backend isn't available (runTask then ends with
 * its availability message, so the user learns why); `auto` (and an unknown / unregistered id) = Laya when its
 * [SystemOne.availability] is [Availability.Ready], else JEV.
 *
 * Extension point for T17 (on-device Laya): this build registers [PlaceholderLayaSystemOne] under id "laya",
 * which is never Ready. T17 replaces it from `App.onCreate` (after `TaskController.init`):
 * ```
 * SystemOneBackends.register(LayaSystemOne(context))   // same id "laya": replaces the placeholder
 * ```
 * Registration is thread-safe and takes effect at the next runTask. `availability` drives `auto`, the settings
 * screen line and the refusal message: return NeedsDownload(bytes) until the model files are there,
 * Unsupported(reason) on phones that can't run it, Ready once loaded (or loadable).
 */
object SystemOneBackends {
    private const val TAG = "SystemOne"
    const val JEV = AppSettings.SYSTEM_ONE_JEV
    const val LAYA = AppSettings.SYSTEM_ONE_LAYA

    @SuppressLint("StaticFieldLeak") // application context only
    @Volatile private var appContext: Context? = null

    private val backends = LinkedHashMap<String, SystemOne>()

    init {
        register(JevSystemOne({ appContext?.let { AppSettings.jevApiKey(it) } ?: BuildConfig.JEV_KEY }))
        register(PlaceholderLayaSystemOne)
    }

    /** Gives the default JEV backend a Context for its key (the application context is kept). */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /** Adds [backend], replacing any registered one with the same [SystemOne.id]. */
    fun register(backend: SystemOne) {
        synchronized(backends) { backends[backend.id] = backend }
        log("registered ${backend.id} (${backend.javaClass.simpleName})")
    }

    /** android.util.Log isn't available in JVM unit tests: logging must never break the registry there. */
    private fun log(msg: String) {
        runCatching { VcLog.i(TAG, msg) }
    }

    fun byId(id: String): SystemOne? = synchronized(backends) { backends[id] }

    /** Registered backends in registration order (JEV first). */
    fun all(): List<SystemOne> = synchronized(backends) { backends.values.toList() }

    /** The JEV backend (always registered). */
    fun jev(): SystemOne = byId(JEV)!!

    /** The backend the current setting resolves to. */
    fun current(ctx: Context): SystemOne {
        init(ctx)
        val app = ctx.applicationContext
        val id = resolveId(AppSettings.systemOneBackend(app), all().map { it.id }.toSet()) { id ->
            val b = byId(id) ?: return@resolveId false
            try { b.availability(app).isReady } catch (e: Exception) {
                log("$id availability failed: ${e.message}"); false
            }
        }
        return byId(id) ?: jev()
    }

    /** Pure decision table behind [current] (unit-tested). [isReady] is asked only for backends that need it. */
    fun resolveId(setting: String, registered: Set<String>, isReady: (String) -> Boolean): String = when {
        setting != AppSettings.SYSTEM_ONE_AUTO && setting in registered -> setting
        LAYA in registered && isReady(LAYA) -> LAYA
        else -> JEV
    }

    /** "JEV: key verified" / "On-device (Laya): not installed in this build", for the settings screen. */
    fun statusLine(ctx: Context, backend: SystemOne): String {
        val a = try { backend.availability(ctx) } catch (e: Exception) {
            Availability.Unsupported(e.message ?: e.javaClass.simpleName)
        }
        return "${backend.displayName}: ${a.message.replaceFirstChar { it.lowercase() }}"
    }
}

/**
 * Stands in for the on-device Laya backend until T17 registers the real `LayaSystemOne` under the same id.
 * Never available, so `auto` always resolves to JEV in this build; choosing "On-device" explicitly makes runTask
 * finish with [MESSAGE].
 */
object PlaceholderLayaSystemOne : SystemOne {
    const val MESSAGE = "On-device model not installed in this build"
    override val id: String = SystemOneBackends.LAYA
    override val displayName: String = "Laya"
    override val pricePerMillionInput: Double = 0.0
    override val wantsUiTree: Boolean = true
    override fun availability(ctx: Context): Availability = Availability.Unsupported(MESSAGE)
    override fun preflight(): String = MESSAGE
    override fun ask(input: StepInput): StepDecision = throw SystemOneException(MESSAGE)
}
