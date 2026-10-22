package com.yourapp.wine
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26],manifest=Config.NONE)
class FuzzySpellingTest { @Test fun spellingSafetyAndRecovery()=FuzzySpellingChecks.run() }
