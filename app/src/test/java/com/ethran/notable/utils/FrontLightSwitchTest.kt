package com.ethran.notable.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontLightSwitchTest {

    /** Firmware stand-in: switches and levels per type, optionally ignoring the switch. */
    private class FakeFirmware(
        val ctm: Boolean = false,
        val fl: Boolean = false,
        val warmCold: Boolean = false,
        val ignoresSwitch: Boolean = false,
        val readable: Boolean = true,
    ) : FrontLightDriver {
        val switches = mutableMapOf<Int, Boolean>()
        val levels = mutableMapOf<Int, Int>()

        override fun checkCTM() = ctm
        override fun hasFLBrightness() = fl
        override fun hasCTMBrightness() = warmCold
        override fun isLightOn(switch: Int) = if (readable) switches[switch] ?: false else null
        override fun openFrontLight(switch: Int): Boolean {
            if (!ignoresSwitch) switches[switch] = true
            return true
        }

        override fun closeFrontLight(switch: Int): Boolean {
            if (!ignoresSwitch) switches[switch] = false
            return true
        }

        override fun getLightValue(level: Int) = if (readable) levels[level] ?: 0 else null
        override fun setLightValue(level: Int, value: Int): Boolean {
            levels[level] = value
            return true
        }
    }

    private class InMemory : FrontLightMemory {
        override var litSwitches: Set<Int> = emptySet()
        override var dimmedLevels: Map<Int, Int> = emptyMap()
    }

    @Test
    fun `ctm light closes the master switch and keeps its level`() {
        val fw = FakeFirmware(ctm = true).apply { switches[4] = true; levels[7] = 80 }
        val light = FrontLightSwitch(fw, InMemory())

        assertEquals(FrontLightState.ON, light.state())
        assertEquals(FrontLightState.OFF, light.toggle())
        assertEquals(false, fw.switches[4])
        assertEquals(80, fw.levels[7])

        assertEquals(FrontLightState.ON, light.toggle())
        assertEquals(true, fw.switches[4])
        assertEquals(80, fw.levels[7])
    }

    @Test
    fun `ignored switch dims to zero and restores the level`() {
        val fw = FakeFirmware(ctm = true, ignoresSwitch = true).apply { switches[4] = true; levels[7] = 55 }
        val memory = InMemory()
        val light = FrontLightSwitch(fw, memory)

        assertEquals(FrontLightState.OFF, light.toggle())
        assertEquals(0, fw.levels[7])
        assertEquals(mapOf(7 to 55), memory.dimmedLevels)

        // A new process: the memory is what carries the level over.
        assertEquals(FrontLightState.ON, FrontLightSwitch(fw, memory).toggle())
        assertEquals(55, fw.levels[7])
        assertEquals(emptyMap<Int, Int>(), memory.dimmedLevels)
    }

    @Test
    fun `warm and cold reopen only the lights that were on`() {
        val fw = FakeFirmware(warmCold = true).apply {
            switches[2] = false; levels[2] = 30
            switches[3] = true; levels[3] = 70
        }
        val light = FrontLightSwitch(fw, InMemory())

        assertEquals(FrontLightState.OFF, light.toggle())
        assertEquals(false, fw.switches[3])

        assertEquals(FrontLightState.ON, light.toggle())
        assertEquals(true, fw.switches[3])
        assertEquals(false, fw.switches[2])
    }

    @Test
    fun `switched off in the control centre, on opens every light`() {
        val fw = FakeFirmware(warmCold = true).apply {
            switches[2] = false; levels[2] = 30
            switches[3] = false; levels[3] = 70
        }
        assertEquals(FrontLightState.ON, FrontLightSwitch(fw, InMemory()).toggle())
        assertEquals(true, fw.switches[2])
        assertEquals(true, fw.switches[3])
    }

    @Test
    fun `level zero reads as off and stays off`() {
        val fw = FakeFirmware(fl = true).apply { switches[1] = true; levels[1] = 0 }
        val light = FrontLightSwitch(fw, InMemory())
        assertEquals(FrontLightState.OFF, light.state())
        assertEquals(FrontLightState.OFF, light.toggle())
    }

    @Test
    fun `no light, or nothing readable, is unsupported`() {
        assertEquals(FrontLightState.UNSUPPORTED, FrontLightSwitch(FakeFirmware(), InMemory()).state())
        val silent = FakeFirmware(ctm = true, readable = false)
        assertEquals(FrontLightState.UNSUPPORTED, FrontLightSwitch(silent, InMemory()).toggle())
    }
}
