# Real-time Stats & Allowed Reason Differentiation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement real-time statistics updates on the home screen and differentiate the exact reasons for allowing calls in the call history list.

**Architecture:** Increment the Room database version from 3 to 4, adding an `allow_reason` column to the `call_logs` table. Update the call screening service to populate this field. Rewrite `HomeViewModel` to combine statistics queries reactively using Kotlin Flows and a dynamic midnight timestamp with `flatMapLatest`. Update the Compose UI screens to bind to these reactive flows.

**Tech Stack:** Kotlin, Jetpack Compose, Room Database, Kotlin Coroutines Flow

## Global Constraints
* Target Java version: Java 11 bytecode target.
* Min SDK: 24.
* Room Database Version: incremented to 4.
* All user-visible strings must be in `res/values/strings.xml`.
* No hardcoded colors in Compose UI; resolve via `MaterialTheme.colorScheme`.

---

### Task 1: Room Database Schema & Migration v3 -> v4

**Files:**
* Modify: [CallLog.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/data/model/CallLog.kt)
* Modify: [AppDatabase.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/data/db/AppDatabase.kt)

- [ ] **Step 1: Add allow_reason column to CallLog entity**
  Add the column definition to `CallLog`:
  ```kotlin
  @ColumnInfo(name = "allow_reason") val allowReason: String? = null
  ```

- [ ] **Step 2: Update AppDatabase.kt with version 4 and MIGRATION_3_4**
  Increment database version to 4:
  ```kotlin
  @Database(
      entities = [BlockRule::class, CallLog::class, WhitelistEntry::class],
      version = 4,
      exportSchema = false
  )
  ```
  Add `MIGRATION_3_4` and register it in `create`:
  ```kotlin
  private val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE `call_logs` ADD COLUMN `allow_reason` TEXT")
      }
  }
  // in create():
  .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
  ```

- [ ] **Step 3: Run unit tests to verify no regressions**
  Run: `./gradlew :app:test`
  Expected: PASS

- [ ] **Step 4: Commit**
  ```bash
  git add app/src/main/java/cc/niaoer/lowcall/data/model/CallLog.kt app/src/main/java/cc/niaoer/lowcall/data/db/AppDatabase.kt
  git commit -m "feat(db): update call_logs schema to v4 and add allow_reason column"
  ```

---

### Task 2: DAO Flow Statistics Queries

**Files:**
* Modify: [CallLogDao.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/data/db/CallLogDao.kt)

- [ ] **Step 1: Add flow query methods to CallLogDao**
  Add the following methods under the existing ones in `CallLogDao`:
  ```kotlin
  @Query("SELECT COUNT(*) FROM call_logs WHERE action = 'BLOCKED'")
  fun getTotalBlockedCountFlow(): Flow<Int>

  @Query("SELECT COUNT(*) FROM call_logs WHERE action = 'BLOCKED' AND timestamp >= :startOfDay")
  fun getBlockedCountSinceFlow(startOfDay: Long): Flow<Int>

  @Query("SELECT * FROM call_logs WHERE action = 'BLOCKED' ORDER BY timestamp DESC LIMIT :limit")
  fun getRecentBlockedFlow(limit: Int): Flow<List<CallLog>>
  ```

- [ ] **Step 2: Compile project to verify code correctness**
  Run: `./gradlew compileDebugKotlin`
  Expected: PASS

- [ ] **Step 3: Commit**
  ```bash
  git add app/src/main/java/cc/niaoer/lowcall/data/db/CallLogDao.kt
  git commit -m "feat(db): add flow-returning stats methods to CallLogDao"
  ```

---

### Task 3: Populate allow_reason in BlockingCallScreeningService

**Files:**
* Modify: [BlockingCallScreeningService.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/service/BlockingCallScreeningService.kt)

- [ ] **Step 1: Update Screening Service to differentiate allowed reasons and write column on insert**
  Replace the whitelist check and insertion block (around lines 40-57) and the default allow insert (around lines 94-102):
  ```kotlin
            val isWhitelisted = container.whitelistDao.exists(normalized)
            val isInContactsList = if (!isWhitelisted) {
                withTimeoutOrNull(CONTACT_LOOKUP_TIMEOUT_MS) {
                    isInContacts(this@BlockingCallScreeningService, phoneNumber)
                } ?: false
            } else {
                false
            }

            if (isWhitelisted || isInContactsList) {
                container.callLogDao.insert(
                    CallLog(
                        phoneNumber = phoneNumber,
                        location = location,
                        carrier = carrier,
                        action = CallAction.ALLOWED,
                        allowReason = if (isWhitelisted) "whitelist" else "contacts",
                        timestamp = System.currentTimeMillis()
                    )
                )
                respondToCall(details, CallResponse.Builder().build())
                return@runBlocking
            }
  ```
  And in the `else` (no block rule matched) block:
  ```kotlin
            } else {
                container.callLogDao.insert(
                    CallLog(
                        phoneNumber = phoneNumber,
                        location = location,
                        carrier = carrier,
                        action = CallAction.ALLOWED,
                        allowReason = "no_match",
                        timestamp = System.currentTimeMillis()
                    )
                )
                respondToCall(details, CallResponse.Builder().build())
            }
  ```

- [ ] **Step 2: Compile project to verify code correctness**
  Run: `./gradlew compileDebugKotlin`
  Expected: PASS

- [ ] **Step 3: Commit**
  ```bash
  git add app/src/main/java/cc/niaoer/lowcall/service/BlockingCallScreeningService.kt
  git commit -m "feat(service): populate allow_reason in call log based on whitelist/contacts/no-match"
  ```

---

### Task 4: Home Stats Reactive Flow & Rollover

**Files:**
* Modify: [HomeViewModel.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/ui/home/HomeViewModel.kt)
* Modify: [HomeScreen.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/ui/home/HomeScreen.kt)

- [ ] **Step 1: Update HomeViewModel to observe flows reactively**
  Rewrite `HomeViewModel` to utilize `flatMapLatest` and `combine` with dynamic start of day:
  ```kotlin
  package cc.niaoer.lowcall.ui.home

  import android.app.Application
  import androidx.lifecycle.AndroidViewModel
  import androidx.lifecycle.viewModelScope
  import cc.niaoer.lowcall.LowCallApplication
  import cc.niaoer.lowcall.data.model.CallLog
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.SharingStarted
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.combine
  import kotlinx.coroutines.flow.flatMapLatest
  import kotlinx.coroutines.flow.stateIn
  import java.util.Calendar

  class HomeViewModel(application: Application) : AndroidViewModel(application) {
      private val callLogDao = (application as LowCallApplication).appContainer.callLogDao

      data class UiState(
          val totalBlocked: Int = 0,
          val todayBlocked: Int = 0,
          val weekBlocked: Int = 0,
          val recentBlocked: List<CallLog> = emptyList()
      )

      private val _midnightStart = MutableStateFlow(getMidnightStart())

      @OptIn(ExperimentalCoroutinesApi::class)
      val uiState: StateFlow<UiState> = _midnightStart.flatMapLatest { midnight ->
          val weekStart = Calendar.getInstance().apply {
              timeInMillis = midnight
              add(Calendar.DAY_OF_YEAR, -7)
          }.timeInMillis

          combine(
              callLogDao.getTotalBlockedCountFlow(),
              callLogDao.getBlockedCountSinceFlow(midnight),
              callLogDao.getBlockedCountSinceFlow(weekStart),
              callLogDao.getRecentBlockedFlow(3)
          ) { total, today, week, recent ->
              UiState(
                  totalBlocked = total,
                  todayBlocked = today,
                  weekBlocked = week,
                  recentBlocked = recent
              )
          }
      }.stateIn(
          scope = viewModelScope,
          started = SharingStarted.WhileSubscribed(5000),
          initialValue = UiState()
      )

      fun refreshMidnight() {
          _midnightStart.value = getMidnightStart()
      }

      private fun getMidnightStart(): Long {
          return Calendar.getInstance().apply {
              set(Calendar.HOUR_OF_DAY, 0)
              set(Calendar.MINUTE, 0)
              set(Calendar.SECOND, 0)
              set(Calendar.MILLISECOND, 0)
          }.timeInMillis
      }
  }
  ```

- [ ] **Step 2: Update HomeScreen.kt to refresh stats on resume**
  Insert a `LaunchedEffect` in `HomeScreen`:
  ```kotlin
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun HomeScreen(
      onNavigateToTest: () -> Unit,
      onNavigateToAddRule: () -> Unit,
      viewModel: HomeViewModel = viewModel()
  ) {
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()

      LaunchedEffect(Unit) {
          viewModel.refreshMidnight()
      }
  ```

- [ ] **Step 3: Compile project to verify code correctness**
  Run: `./gradlew compileDebugKotlin`
  Expected: PASS

- [ ] **Step 4: Commit**
  ```bash
  git add app/src/main/java/cc/niaoer/lowcall/ui/home/HomeViewModel.kt app/src/main/java/cc/niaoer/lowcall/ui/home/HomeScreen.kt
  git commit -m "feat(ui): implement reactive stats updates on Home screen with date rollover"
  ```

---

### Task 5: History Screen Label Customization

**Files:**
* Modify: [strings.xml](file:///Users/kenn/PROJECTS/LowCall/app/src/main/res/values/strings.xml)
* Modify: [CallHistoryScreen.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/ui/history/CallHistoryScreen.kt)

- [ ] **Step 1: Add new string resources in strings.xml**
  Add the string resources before the closing `</resources>` tag:
  ```xml
      <string name="allowed_whitelist">已放行 · 白名单匹配</string>
      <string name="allowed_contacts">已放行 · 联系人匹配</string>
      <string name="allowed_no_match">已放行 · 无匹配规则</string>
  ```

- [ ] **Step 2: Update CallHistoryScreen.kt to render correct reason text**
  Modify `CallLogItem` text rendering logic for the ALLOWED action (around lines 210-218):
  ```kotlin
                      Text(
                          text = if (isBlocked) {
                              "${log.matchedRulePattern ?: "未知规则"} · 响铃 0 秒"
                          } else {
                              when (log.allowReason) {
                                  "whitelist" -> stringResource(R.string.allowed_whitelist)
                                  "contacts" -> stringResource(R.string.allowed_contacts)
                                  "no_match" -> stringResource(R.string.allowed_no_match)
                                  else -> stringResource(R.string.allowed) // Legacy fallback: "已放行"
                              }
                          },
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
  ```

- [ ] **Step 3: Run comprehensive verification**
  Run: `./gradlew :app:test`
  Expected: PASS
  Run: `./gradlew :app:lintDebug`
  Expected: PASS

- [ ] **Step 4: Commit**
  ```bash
  git add app/src/main/res/values/strings.xml app/src/main/java/cc/niaoer/lowcall/ui/history/CallHistoryScreen.kt
  git commit -m "feat(ui): display distinct allow reasons in Call History log"
  ```
