package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun testThemeToggleAndTabNavigation() {
    // Advance the clock past the 1.5s splash screen delay and the 5s Telegram dialog
    composeTestRule.mainClock.advanceTimeBy(8000L)

    // 1. Verify bottom navigation bar exists
    composeTestRule.onNodeWithTag("vibrant_bottom_nav_bar").assertExists()

    // 2. Navigate to "Main Menu" tab
    composeTestRule.onNodeWithText("Main Menu").assertExists().performClick()

    // 3. Check that My Account View is displayed
    composeTestRule.onNodeWithTag("my_account_view").assertExists()

    // 4. Click the theme toggle button to change theme
    composeTestRule.onNodeWithTag("theme_toggle_button").assertExists().performClick()
    
    // 5. Click again to toggle back
    composeTestRule.onNodeWithTag("theme_toggle_button").performClick()
  }

  @Test
  fun testOpenHistoryDialog() {
    // Advance the clock past the 1.5s splash screen delay and the 5s Telegram dialog
    composeTestRule.mainClock.advanceTimeBy(8000L)

    // Navigate to "Main Menu" tab
    composeTestRule.onNodeWithText("Main Menu").performClick()

    // Verify My Account View is displayed
    composeTestRule.onNodeWithTag("my_account_view").assertExists()

    // Scroll to the local history trigger and click to open the dialog
    composeTestRule.onNodeWithTag("my_account_view")
      .performScrollToNode(hasTestTag("local_history_trigger"))

    composeTestRule.onNodeWithTag("local_history_trigger")
      .assertExists()
      .performClick()
  }
}
