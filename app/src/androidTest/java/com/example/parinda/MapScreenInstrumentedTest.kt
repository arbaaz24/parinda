package com.example.parinda

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapScreenInstrumentedTest {

    @Test
    fun mapView_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("map")).check(matches(isDisplayed()))
        }
    }
}
