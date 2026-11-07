package dev.cleanmytv.companion
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
class NavigationTest {
 @get:Rule val compose = createAndroidComposeRule<MainActivity>()
 @Test fun startsDisconnectedInEnglish() {
  compose.onNodeWithText("○  No TV selected").assertExists()
  compose.onNodeWithText("TV IP address").assertExists()
  compose.onNodeWithText("Connect").assertIsNotEnabled()
 }
 @Test fun homeChangesRequireExplicitTvSelection() {
  compose.onNodeWithText("4  Home screen").performScrollTo().performClick()
  compose.onNodeWithText("Select and confirm your TV in Setup to use this stage.").assertExists()
  compose.onNodeWithText("Open Setup").performClick()
  compose.onNodeWithText("TV IP address").assertExists()
 }
}
