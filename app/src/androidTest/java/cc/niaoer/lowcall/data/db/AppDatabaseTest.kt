package cc.niaoer.lowcall.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.niaoer.lowcall.data.model.CallAction
import cc.niaoer.lowcall.data.model.CallLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var callLogDao: CallLogDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        callLogDao = db.callLogDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndReadCallLog_withAllowReason() = runBlocking {
        val log = CallLog(
            phoneNumber = "13800138000",
            action = CallAction.ALLOWED,
            allowReason = "Whitelist"
        )
        val id = callLogDao.insert(log)
        val logs = callLogDao.getAllOrdered().first()
        
        assertEquals(1, logs.size)
        val retrieved = logs[0]
        assertEquals(id, retrieved.id)
        assertEquals("13800138000", retrieved.phoneNumber)
        assertEquals(CallAction.ALLOWED, retrieved.action)
        assertEquals("Whitelist", retrieved.allowReason)
    }

    @Test
    fun insertAndReadCallLog_withoutAllowReason() = runBlocking {
        val log = CallLog(
            phoneNumber = "13800138001",
            action = CallAction.BLOCKED
        )
        val id = callLogDao.insert(log)
        val logs = callLogDao.getAllOrdered().first()
        
        assertEquals(1, logs.size)
        val retrieved = logs[0]
        assertEquals(id, retrieved.id)
        assertEquals("13800138001", retrieved.phoneNumber)
        assertEquals(CallAction.BLOCKED, retrieved.action)
        assertNull(retrieved.allowReason)
    }

    @Test
    fun testCallLogStatsFlows() = runBlocking {
        assertEquals(0, callLogDao.getTotalBlockedCountFlow().first())
        assertEquals(0, callLogDao.getBlockedCountSinceFlow(1000L).first())
        assertEquals(0, callLogDao.getRecentBlockedFlow(5).first().size)

        val logAllowed = CallLog(
            phoneNumber = "13800138001",
            action = CallAction.ALLOWED,
            timestamp = 500L
        )
        callLogDao.insert(logAllowed)

        assertEquals(0, callLogDao.getTotalBlockedCountFlow().first())

        val logBlockedBefore = CallLog(
            phoneNumber = "13800138002",
            action = CallAction.BLOCKED,
            timestamp = 500L
        )
        callLogDao.insert(logBlockedBefore)

        assertEquals(1, callLogDao.getTotalBlockedCountFlow().first())
        assertEquals(0, callLogDao.getBlockedCountSinceFlow(1000L).first())

        val logBlockedAfter1 = CallLog(
            phoneNumber = "13800138003",
            action = CallAction.BLOCKED,
            timestamp = 1500L
        )
        callLogDao.insert(logBlockedAfter1)

        assertEquals(2, callLogDao.getTotalBlockedCountFlow().first())
        assertEquals(1, callLogDao.getBlockedCountSinceFlow(1000L).first())

        val logBlockedAfter2 = CallLog(
            phoneNumber = "13800138004",
            action = CallAction.BLOCKED,
            timestamp = 2000L
        )
        callLogDao.insert(logBlockedAfter2)

        assertEquals(3, callLogDao.getTotalBlockedCountFlow().first())
        assertEquals(2, callLogDao.getBlockedCountSinceFlow(1000L).first())

        val recent = callLogDao.getRecentBlockedFlow(2).first()
        assertEquals(2, recent.size)
        assertEquals("13800138004", recent[0].phoneNumber)
        assertEquals("13800138003", recent[1].phoneNumber)
    }
}
