package com.cryptoaipro.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtLiveTradingEngineDirectionTest {
    @Test fun mapsSignalAndNeuralBiasLabelsToExchangeSides() {
        assertEquals("LONG", XtLiveTradingEngine.directionSide("LONG_BIAS"))
        assertEquals("LONG", XtLiveTradingEngine.directionSide("long"))
        assertEquals("SHORT", XtLiveTradingEngine.directionSide("SHORT_BIAS"))
        assertEquals("SHORT", XtLiveTradingEngine.directionSide("short"))
    }

    @Test fun neutralAndUnknownLabelsNeverBecomeTradeSides() {
        assertNull(XtLiveTradingEngine.directionSide("NEUTRAL"))
        assertNull(XtLiveTradingEngine.directionSide("UNKNOWN"))
        assertNull(XtLiveTradingEngine.directionSide(""))
    }
}
