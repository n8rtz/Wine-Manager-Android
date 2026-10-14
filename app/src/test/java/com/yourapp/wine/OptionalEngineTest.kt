package com.yourapp.wine

import com.yourapp.wine.ocr.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class OptionalEngineTest {
    @Test fun absentPaddleModelsNeverPreventMlKitPath() {
        val context = RuntimeEnvironment.getApplication()
        val presence = PaddleEngineFactory.inspect(context)
        assertNull(presence.models)
        val engine = PaddleEngineFactory.create(context, presence, NativeEngineGate())
        assertFalse(engine.available)
        assertTrue(engine is NotAvailable)
    }
}
