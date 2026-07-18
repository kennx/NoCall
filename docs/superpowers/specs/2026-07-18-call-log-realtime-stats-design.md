# Design Spec: Call Log Real-time Stats & Allowed Reason Differentiation

This specification details the technical changes required to implement real-time statistics updates on the homepage of LowCall and distinguish the reasons for allowing calls in the call history page.

---

## 1. Database Schema & Migration (v3 to v4)

We will increment the database version from 3 to 4. We will add a new nullable column `allow_reason` of type `TEXT` to the `call_logs` table.

### 1.1 Model Change
Modify [CallLog.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/data/model/CallLog.kt) to include the new field:
```kotlin
@ColumnInfo(name = "allow_reason") val allowReason: String? = null
```

### 1.2 Migration Logic
Modify [AppDatabase.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/data/db/AppDatabase.kt):
* Increment `@Database` version to `4`.
* Implement `MIGRATION_3_4`:
  ```kotlin
  private val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE `call_logs` ADD COLUMN `allow_reason` TEXT")
      }
  }
  ```
* Register the new migration in `databaseBuilder`:
  ```kotlin
  .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
  ```

---

## 2. DAO Layer Updates

Add Flow-returning counterparts to the existing statistics queries in [CallLogDao.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/data/db/CallLogDao.kt):
```kotlin
@Query("SELECT COUNT(*) FROM call_logs WHERE action = 'BLOCKED'")
fun getTotalBlockedCountFlow(): Flow<Int>

@Query("SELECT COUNT(*) FROM call_logs WHERE action = 'BLOCKED' AND timestamp >= :startOfDay")
fun getBlockedCountSinceFlow(startOfDay: Long): Flow<Int>

@Query("SELECT * FROM call_logs WHERE action = 'BLOCKED' ORDER BY timestamp DESC LIMIT :limit")
fun getRecentBlockedFlow(limit: Int): Flow<List<CallLog>>
```

---

## 3. Screening Service Logic Updates

Update [BlockingCallScreeningService.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/service/BlockingCallScreeningService.kt) to differentiate whitelist matches, contact matches, and no-rule matches:
* **Check whitelist and contacts separately**:
  ```kotlin
  val isWhitelisted = container.whitelistDao.exists(normalized)
  val isInContactsList = if (!isWhitelisted) {
      withTimeoutOrNull(CONTACT_LOOKUP_TIMEOUT_MS) {
          isInContacts(this@BlockingCallScreeningService, phoneNumber)
      } ?: false
  } else {
      false
  }
  ```
* **Store correct allowReason on insert**:
  * For whitelist: `allowReason = "whitelist"`
  * For contacts: `allowReason = "contacts"`
  * For no matching rules: `allowReason = "no_match"`

---

## 4. UI & ViewModel Updates

### 4.1 Home Screen Statistics
Update [HomeViewModel.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/ui/home/HomeViewModel.kt) to combine flows reactively.
We will expose a `_midnightStart: MutableStateFlow<Long>` and use `flatMapLatest` to recreate DB flows when `_midnightStart` changes (e.g. on screen resume).
```kotlin
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
```
Update [HomeScreen.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/ui/home/HomeScreen.kt) to trigger `viewModel.refreshMidnight()` on entering screen:
```kotlin
LaunchedEffect(Unit) {
    viewModel.refreshMidnight()
}
```

### 4.2 Call History Labels
Add resource strings in [strings.xml](file:///Users/kenn/PROJECTS/LowCall/app/src/main/res/values/strings.xml):
```xml
<string name="allowed_whitelist">已放行 · 白名单匹配</string>
<string name="allowed_contacts">已放行 · 联系人匹配</string>
<string name="allowed_no_match">已放行 · 无匹配规则</string>
```
Update [CallHistoryScreen.kt](file:///Users/kenn/PROJECTS/LowCall/app/src/main/java/cc/niaoer/lowcall/ui/history/CallHistoryScreen.kt):
```kotlin
Text(
    text = if (isBlocked) {
        "${log.matchedRulePattern ?: "未知规则"} · 响铃 0 秒"
    } else {
        when (log.allowReason) {
            "whitelist" -> stringResource(R.string.allowed_whitelist)
            "contacts" -> stringResource(R.string.allowed_contacts)
            else -> stringResource(R.string.allowed_no_match)
        }
    },
    ...
)
```

---

## 5. Verification & Testing

* **Unit Tests**: Add tests verifying database migration and model additions.
* **Service Verification**: Screen a call matching a whitelist entry, contacts list, and no rules, verify corresponding `allowReason` values in DB.
* **Stats Verification**: Verify that the homepage count updates immediately after a mock call screening event.
