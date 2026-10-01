package com.whispercppdemo

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercppdemo.privacy.CloudDisclosure
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.transcribe.EngineSelector
import com.whispercppdemo.ui.privacy.CloudDisclosureScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cloud disclosure on a real device: the global location wording is shown,
 * nothing names India, and consent stored for version 1 no longer counts.
 * The app's own acknowledgement is saved first and restored afterwards.
 */
@RunWith(AndroidJUnit4::class)
class CloudDisclosureDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("cloud_disclosure", Context.MODE_PRIVATE)
    private var saved: Map<String, *> = emptyMap<String, Any>()

    @Before fun save() { saved = HashMap(prefs.all) }

    @After fun restore() {
        prefs.edit().clear().apply {
            saved.forEach { (k, v) -> when (v) { is Int -> putInt(k, v); is Long -> putLong(k, v) } }
        }.commit()
    }

    private fun screen(fontScale: Float = 1f, width: Int = 412, height: Int = 900, onAccept: () -> Unit = {}) {
        compose.setContent {
            WhisperCppDemoTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    Box(Modifier.size(width.dp, height.dp)) { CloudDisclosureScreen(onAccept = onAccept, onDecline = {}) }
                }
            }
        }
    }

    @Test
    fun showsTheGlobalWording_everyPoint_andNoIndia() {
        screen()
        compose.onNodeWithText("Processing may take place in another country.").performScrollTo().assertIsDisplayed()
        (CloudDisclosure.POINTS + CloudDisclosure.RETENTION_NOTE).forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        assertEquals(0, compose.onAllNodes(hasText("India", substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun consentToVersion1IsAskedAgain_andAcceptingStoresVersion2() {
        prefs.edit().clear().putInt("acknowledged_version", 1).putLong("acknowledged_at", 1L).commit()
        assertEquals(1, CloudDisclosure.acknowledgedVersion(context))
        assertFalse("a version-1 user must see the new disclosure", CloudDisclosure.isAcknowledged(context))

        var accepted = false
        screen(onAccept = { accepted = true; CloudDisclosure.acknowledge(context) })
        // The buttons sit in the fixed bar below the scrolling facts.
        compose.onNodeWithText("I understand — transcribe").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(accepted) }
        assertEquals(2, CloudDisclosure.acknowledgedVersion(context))
        assertTrue(CloudDisclosure.isAcknowledged(context))
    }

    @Test
    fun onceAcceptedItIsNotRequiredForLaterCloudJobs_andNeverForLocal() {
        val saved = EngineSelector.devOverride
        try {
            EngineSelector.devOverride = Engine.CLOUD
            prefs.edit().clear().commit()
            assertTrue("first cloud transcription needs it", CloudDisclosure.isRequired(context))
            CloudDisclosure.acknowledge(context)
            repeat(5) { assertFalse("later cloud job ${it + 2} must not show it", CloudDisclosure.isRequired(context)) }

            // A stored older version: the current text is shown once more.
            prefs.edit().putInt("acknowledged_version", CloudDisclosure.CURRENT_VERSION - 1).commit()
            assertTrue(CloudDisclosure.isRequired(context))
            CloudDisclosure.acknowledge(context)
            assertFalse(CloudDisclosure.isRequired(context))

            EngineSelector.devOverride = Engine.LOCAL
            prefs.edit().clear().commit()
            assertFalse("LOCAL never shows the disclosure", CloudDisclosure.isRequired(context))
        } finally {
            EngineSelector.devOverride = saved
        }
    }

    @Test
    fun readableInLandscapeAt200Percent() {
        screen(fontScale = 2f, width = 900, height = 360)
        compose.onNodeWithText("Processing may take place in another country.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
    }
}
