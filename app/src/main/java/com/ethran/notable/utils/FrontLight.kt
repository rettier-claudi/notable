package com.ethran.notable.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

/*
 * Fork: switching the Boox front light on and off from inside the app (claudi.21).
 *
 * The firmware exposes the light through static methods on `android.onyx.hardware.DeviceController`
 * — the same class the Onyx SDK and KOReader's Onyx light controllers call by reflection. Each
 * light has a switch (open/close, keeps the level) and a level. Three layouts exist:
 *  - CTM (newer devices, `checkCTM()`): one brightness level (type 7) and a colour temperature,
 *    both behind the master switch 4;
 *  - FL: a single front light, switch and level type 1;
 *  - warm and cold: two lights, each its own switch and level (types 2 and 3).
 * Off closes the switch, so the firmware keeps the level and the Boox control centre stays in
 * step. If the firmware ignores the switch (the light still reads as on), off dims the level to
 * zero instead and remembers it, and on puts it back.
 */

private val log = ShipBook.getLogger("FrontLight")

enum class FrontLightState { UNKNOWN, UNSUPPORTED, ON, OFF }

/** Raw firmware calls; null = the call is missing or failed. An interface so the logic is testable. */
interface FrontLightDriver {
    fun checkCTM(): Boolean?
    fun hasFLBrightness(): Boolean?
    fun hasCTMBrightness(): Boolean?
    fun isLightOn(switch: Int): Boolean?
    fun openFrontLight(switch: Int): Boolean?
    fun closeFrontLight(switch: Int): Boolean?
    fun getLightValue(level: Int): Int?
    fun setLightValue(level: Int, value: Int): Boolean?
}

/** What survives a process restart between off and on: the switches that were lit, dimmed levels. */
interface FrontLightMemory {
    var litSwitches: Set<Int>
    var dimmedLevels: Map<Int, Int>
}

/** One light: its open/close switch and its level type, as the firmware numbers them. */
data class LightChannel(val switch: Int, val level: Int)

class FrontLightSwitch(
    private val driver: FrontLightDriver,
    private val memory: FrontLightMemory,
) {
    /** Detection order of the Onyx SDK's BrightnessController: CTM, then FL, then warm and cold. */
    fun channels(): List<LightChannel> = when {
        driver.checkCTM() == true -> listOf(LightChannel(switch = 4, level = 7))
        driver.hasFLBrightness() == true -> listOf(LightChannel(switch = 1, level = 1))
        driver.hasCTMBrightness() == true ->
            listOf(LightChannel(switch = 2, level = 2), LightChannel(switch = 3, level = 3))

        else -> emptyList()
    }

    fun state(): FrontLightState = state(channels())

    private fun state(channels: List<LightChannel>): FrontLightState {
        // Neither the switch nor the level answers: nothing this app can drive.
        val readable = channels.filter {
            driver.isLightOn(it.switch) != null || driver.getLightValue(it.level) != null
        }
        if (readable.isEmpty()) return FrontLightState.UNSUPPORTED
        return if (readable.any(::isLit)) FrontLightState.ON else FrontLightState.OFF
    }

    /** Lit = switch not reported off, level not reported zero. */
    private fun isLit(channel: LightChannel): Boolean =
        driver.isLightOn(channel.switch) != false && (driver.getLightValue(channel.level) ?: 1) > 0

    /** Switches the light over and returns the state read back afterwards. */
    fun toggle(): FrontLightState {
        val channels = channels()
        when (state(channels)) {
            FrontLightState.ON -> turnOff(channels)
            FrontLightState.OFF -> turnOn(channels)
            else -> return FrontLightState.UNSUPPORTED
        }
        return state(channels)
    }

    private fun turnOff(channels: List<LightChannel>) {
        val lit = channels.filter(::isLit)
        memory.litSwitches = lit.map { it.switch }.toSet()
        memory.dimmedLevels = emptyMap()
        lit.forEach { driver.closeFrontLight(it.switch) }

        val stillLit = lit.filter(::isLit)
        if (stillLit.isEmpty()) return
        log.w("Switch ignored for ${stillLit.map { it.switch }}, dimming to zero instead")
        memory.dimmedLevels = stillLit.mapNotNull { channel ->
            val value = driver.getLightValue(channel.level)?.takeIf { it > 0 } ?: return@mapNotNull null
            driver.setLightValue(channel.level, 0)
            channel.level to value
        }.toMap()
    }

    private fun turnOn(channels: List<LightChannel>) {
        // Switched off outside the app (control centre): open every light, the firmware keeps levels.
        val switches = memory.litSwitches.ifEmpty { channels.map { it.switch }.toSet() }
        val dimmed = memory.dimmedLevels
        channels.filter { it.switch in switches }.forEach { channel ->
            driver.openFrontLight(channel.switch)
            val saved = dimmed[channel.level] ?: return@forEach
            if ((driver.getLightValue(channel.level) ?: 0) == 0) driver.setLightValue(channel.level, saved)
        }
        memory.litSwitches = emptySet()
        memory.dimmedLevels = emptyMap()
    }
}

/** `android.onyx.hardware.DeviceController`, all static, looked up once. */
private class OnyxFrontLightDriver(private val context: Context) : FrontLightDriver {
    private val controller: Class<*>? =
        runCatching { Class.forName("android.onyx.hardware.DeviceController") }.getOrNull()

    private fun method(name: String, vararg params: Class<*>): Method? =
        controller?.let { runCatching { it.getMethod(name, *params) }.getOrNull() }

    private val int = Int::class.javaPrimitiveType!!
    private val mCheckCTM = method("checkCTM")
    private val mHasFL = method("hasFLBrightness", Context::class.java)
    private val mHasCTM = method("hasCTMBrightness", Context::class.java)
    private val mIsLightOn = method("isLightOn", int)
    private val mIsLightOnCtx = method("isLightOn", Context::class.java, int)
    private val mOpen = method("openFrontLight", int)
    private val mClose = method("closeFrontLight", int)
    private val mGetValue = method("getLightValue", int)
    // Newer firmware takes a third argument (the SDK passes 0), older only type and value.
    private val mSetValue3 = method("setLightValue", int, int, int)
    private val mSetValue2 = method("setLightValue", int, int)

    private fun call(method: Method?, vararg args: Any): Any? = method?.let {
        runCatching { it.invoke(null, *args) }
            .onFailure { e -> log.w("${it.name} failed: $e") }
            .getOrNull()
    }

    override fun checkCTM() = call(mCheckCTM) as? Boolean
    override fun hasFLBrightness() = call(mHasFL, context) as? Boolean
    override fun hasCTMBrightness() = call(mHasCTM, context) as? Boolean
    override fun isLightOn(switch: Int) =
        (if (mIsLightOn != null) call(mIsLightOn, switch) else call(mIsLightOnCtx, context, switch)) as? Boolean

    override fun openFrontLight(switch: Int) = call(mOpen, switch) as? Boolean
    override fun closeFrontLight(switch: Int) = call(mClose, switch) as? Boolean
    override fun getLightValue(level: Int) = (call(mGetValue, level) as? Number)?.toInt()
    // Any answer counts as done, like the SDK; the caller reads the level back anyway.
    override fun setLightValue(level: Int, value: Int): Boolean? =
        (call(mSetValue3, level, value, 0) ?: call(mSetValue2, level, value))?.let { true }
}

private class PrefsFrontLightMemory(private val prefs: SharedPreferences) : FrontLightMemory {
    override var litSwitches: Set<Int>
        get() = prefs.getStringSet(KEY_SWITCHES, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = prefs.edit { putStringSet(KEY_SWITCHES, value.map { it.toString() }.toSet()) }

    override var dimmedLevels: Map<Int, Int>
        get() = prefs.getStringSet(KEY_DIMMED, emptySet()).orEmpty().mapNotNull { entry ->
            val (level, value) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            (level.toIntOrNull() ?: return@mapNotNull null) to (value.toIntOrNull() ?: return@mapNotNull null)
        }.toMap()
        set(value) = prefs.edit { putStringSet(KEY_DIMMED, value.map { "${it.key}=${it.value}" }.toSet()) }

    private companion object {
        const val KEY_SWITCHES = "lit_switches"
        const val KEY_DIMMED = "dimmed_levels"
    }
}

/** The app-wide light switch: one state for the home screen and the editor toolbar. */
object FrontLight {
    private val _state = MutableStateFlow(FrontLightState.UNKNOWN)
    val state: StateFlow<FrontLightState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var switch: FrontLightSwitch? = null

    private fun switchFor(context: Context): FrontLightSwitch =
        switch ?: context.applicationContext.let { app ->
            FrontLightSwitch(
                OnyxFrontLightDriver(app),
                PrefsFrontLightMemory(app.getSharedPreferences("front_light", Context.MODE_PRIVATE)),
            )
        }.also { switch = it }

    /** Reads the light again, e.g. after the control centre may have changed it. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock { _state.value = switchFor(context).state() }
    }

    /** Switches the light; returns the state before and after, so callers can tell a no-op. */
    suspend fun toggle(context: Context): Pair<FrontLightState, FrontLightState> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val light = switchFor(context)
                val before = light.state()
                val after = light.toggle()
                log.i("Front light $before -> $after")
                _state.value = after
                before to after
            }
        }
}
