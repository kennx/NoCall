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
}
