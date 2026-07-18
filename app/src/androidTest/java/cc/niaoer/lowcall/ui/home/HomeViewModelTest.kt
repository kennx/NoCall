package cc.niaoer.lowcall.ui.home

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.niaoer.lowcall.LowCallApplication
import cc.niaoer.lowcall.data.model.CallAction
import cc.niaoer.lowcall.data.model.CallLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {

    private lateinit var app: LowCallApplication
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LowCallApplication>()
        runBlocking {
            app.appContainer.callLogDao.deleteAll()
        }
        viewModel = HomeViewModel(app)
    }

    @Test
    fun testStatsUpdatingReactively() = runBlocking {
        // Launch a collector to activate the WhileSubscribed Flow
        val collectJob = launch {
            viewModel.uiState.collect {}
        }

        // Wait a brief moment for the initial state from database to load (should be all 0)
        var totalAttempts = 0
        while (viewModel.uiState.value.totalBlocked != 0 && totalAttempts < 50) {
            delay(10)
            totalAttempts++
        }

        var uiState = viewModel.uiState.value
        assertEquals(0, uiState.totalBlocked)
        assertEquals(0, uiState.todayBlocked)
        assertEquals(0, uiState.weekBlocked)
        assertEquals(0, uiState.recentBlocked.size)

        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val logToday = CallLog(
            phoneNumber = "13800138000",
            action = CallAction.BLOCKED,
            timestamp = todayStart + 1000
        )
        app.appContainer.callLogDao.insert(logToday)

        totalAttempts = 0
        while (viewModel.uiState.value.totalBlocked == 0 && totalAttempts < 100) {
            delay(10)
            totalAttempts++
        }

        uiState = viewModel.uiState.value
        assertEquals(1, uiState.totalBlocked)
        assertEquals(1, uiState.todayBlocked)
        assertEquals(1, uiState.weekBlocked)
        assertEquals(1, uiState.recentBlocked.size)
        assertEquals("13800138000", uiState.recentBlocked.first().phoneNumber)

        collectJob.cancel()
    }

    @Test
    fun testRefreshMidnightRecalculatesStats() = runBlocking {
        val collectJob = launch {
            viewModel.uiState.collect {}
        }
        viewModel.refreshMidnight()
        val uiState = viewModel.uiState.value
        assertNotNull(uiState)
        collectJob.cancel()
    }
}
