package cc.niaoer.lowcall.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.niaoer.lowcall.AppContainer
import cc.niaoer.lowcall.LowCallApplication
import cc.niaoer.lowcall.R
import cc.niaoer.lowcall.data.db.AppDatabase
import cc.niaoer.lowcall.data.model.CallAction
import cc.niaoer.lowcall.data.model.CallLog
import cc.niaoer.lowcall.ui.theme.LowCallTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallHistoryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: LowCallApplication
    private lateinit var db: AppDatabase
    private lateinit var originalContainer: AppContainer

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LowCallApplication>()
        originalContainer = app.appContainer
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        app.appContainer = AppContainer(app, db)
    }

    @After
    fun tearDown() {
        db.close()
        app.appContainer = originalContainer
    }

    @Test
    fun testCallHistoryScreenDisplaysDistinctAllowReasons() {
        runBlocking {
            // Insert logs with different allow reasons
            val log1 = CallLog(
                phoneNumber = "13800138001",
                action = CallAction.ALLOWED,
                allowReason = "whitelist",
                timestamp = System.currentTimeMillis()
            )
            val log2 = CallLog(
                phoneNumber = "13800138002",
                action = CallAction.ALLOWED,
                allowReason = "contacts",
                timestamp = System.currentTimeMillis() - 1000
            )
            val log3 = CallLog(
                phoneNumber = "13800138003",
                action = CallAction.ALLOWED,
                allowReason = "no_match",
                timestamp = System.currentTimeMillis() - 2000
            )
            val log4 = CallLog(
                phoneNumber = "13800138004",
                action = CallAction.ALLOWED,
                allowReason = null,
                timestamp = System.currentTimeMillis() - 3000
            )

            db.callLogDao().insert(log1)
            db.callLogDao().insert(log2)
            db.callLogDao().insert(log3)
            db.callLogDao().insert(log4)

            val viewModel = CallHistoryViewModel(app)

            // Set screen content
            composeTestRule.setContent {
                LowCallTheme {
                    CallHistoryScreen(viewModel = viewModel)
                }
            }

            // Wait for UI to idle and assert presence of strings
            composeTestRule.waitForIdle()

            // Get resource strings
            val whitelistStr = app.getString(R.string.allowed_whitelist)
            val contactsStr = app.getString(R.string.allowed_contacts)
            val noMatchStr = app.getString(R.string.allowed_no_match)
            val legacyStr = app.getString(R.string.allowed)

            composeTestRule.onNodeWithText(whitelistStr).assertExists()
            composeTestRule.onNodeWithText(contactsStr).assertExists()
            composeTestRule.onNodeWithText(noMatchStr).assertExists()
            composeTestRule.onAllNodesWithText(legacyStr).assertCountEquals(5)
        }
    }
}
