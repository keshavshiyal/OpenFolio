# OpenFolio Production-Readiness Security, Architecture & Dead-Code Audit Report

- **Repository:** `c:\Users\KESHAV\Desktop\Work\openfolio`
- **Package / Application ID:** `com.openfolio` (`versionCode = 1`, `versionName = "0.1.0"`, `minSdk = 26`, `targetSdk = 35`)
- **Audit Mode:** Strictly read-only inspection (no source, manifest, Gradle, or resource files modified)
- **Automated Verification Snapshot (Phase 6):**
  - `./gradlew.bat lintDebug`: **0 errors, 68 warnings** (`app/build/reports/lint-results-debug.txt`, `lint-results-debug.xml`)
    - Security (`TrustAllX509TrustManager` in `bcpkix-jdk15to18:1.72`): **3 warnings**
    - Backup (`DataExtractionRules` on `AndroidManifest.xml:11`): **1 warning**
    - Compose (`ModifierParameter`): **4 warnings**
    - Resources (`ObsoleteSdkInt` on `mipmap-anydpi-v26`: **1**, `MonochromeLauncherIcon`: **2**)
    - Outdated dependencies (`GradleDependency`: **54**, `AndroidGradlePluginVersion`: **3**)
    - `UnusedResources`: **0** | `UnusedIds`: **0**
  - `./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon`: **BUILD SUCCESSFUL** — **37 test suites, 277 tests executed, 0 failures, 0 errors, 0 JUnit-reported skips** (Note: 2 tests in `DocumentRepositoryImplTest.kt` have `// @Test` commented out with `@Ignore`, and `app/src/androidTest` does not exist).

---

## Executive Summary by Severity

| Severity | Finding Count | Core Themes |
| :--- | :---: | :--- |
| **P0 — Critical / Blocker** | 7 | Destructive Room migration wipe, non-atomic backup file deletion, EOL `android-database-sqlcipher:4.5.4` (no 16 KB ELF alignment for Android 15+), silent plaintext API-key fallback & partial Keystore crash, uncaught AI/Converter parse crashes, unencrypted statement/export files at rest + unredacted cloud PII transmission, unbounded ZIP/XLSX decompression |
| **P1 — High** | 7 | Process-death data loss in SAF launchers & forms (no `SavedStateHandle` / `rememberSaveable`), SQLite `REAL`/`Double` precision drift & flawed `netWorth`/unmatched-sell math, swallowed `CancellationException` & native PDF/Bitmap FD leaks, ViewModel state races & double-tap duplicate sells, unescaped nav route args, missing Android 12+ `dataExtractionRules` & API 26–27 cleartext config, Activity-scoped ViewModels holding secrets |
| **P2 — Medium / Tech Debt** | 5 | Main-thread FIFO/sorting flows & eager dashboard collection, release-signing/CI gate gaps & broad ProGuard rules, transitive CVEs (`commons-beanutils:1.9.4` CVE-2025-48734, `bcpkix` TrustManager warning, deprecated `security-crypto`), Compose draw allocations / clipped sheet / blocked navigation / `AGENTS.md` color & currency violations, unused `ACCESS_NETWORK_STATE` permission & missing worker/UI tests |
| **P3 — Cleanup & Dead Code** | 4 groups | **18 unreferenced/unreachable `.kt` production files** (**111,736 bytes / ~109.1 KiB**), **5 workspace/duplicate files + 1 empty dir** (**1,976,427 bytes / ~1.88 MiB**), dead functions/overloads inside active files, and **3+ unused Gradle dependencies** |

---

## P0 — Critical / Blocker

### P0-1. Destructive Room Migration Policy Silently Wipes User Database
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/core/di/DatabaseModule.kt:45-54`
   - `app/src/main/java/com/openfolio/core/data/local/OpenFolioDatabase.kt:25-40`
2. **Evidence:**
   ```kotlin
   // DatabaseModule.kt:45-54
   return Room.databaseBuilder(
       context,
       OpenFolioDatabase::class.java,
       "openfolio.db",
   )
       .fallbackToDestructiveMigration()
       .fallbackToDestructiveMigrationOnDowngrade()
       .openHelperFactory(SupportFactory(passphraseProvider.getPassphrase()))
       .build()

   // OpenFolioDatabase.kt:38-39
   version = 1,
   exportSchema = false,
   ```
3. **Failure Scenario / Impact:**
   Any future schema bump (`version = 2`+) without an explicit `Migration`, or an app downgrade, triggers Room to drop and recreate every table in `openfolio.db`. Users permanently lose all imported financial history, FIFO cost-basis lots, assets, and document provenance. With `exportSchema = false`, Room does not even record historical schema JSON snapshots needed to author and test safe migrations.
4. **Concrete Fix:**
   - Remove `.fallbackToDestructiveMigration()` and `.fallbackToDestructiveMigrationOnDowngrade()` from `DatabaseModule.provideDatabase()`.
   - Set `exportSchema = true` in `OpenFolioDatabase` and configure KSP `room.schemaLocation` in `app/build.gradle.kts`.
   - Define explicit `Migration(n, n+1)` objects registered via `.addMigrations(...)` for every schema change and cover them with `MigrationTestHelper` tests.

---

### P0-2. Non-Atomic Backup Restore Deletes Local Documents After Committing Database
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/core/data/local/export/AppBackupManager.kt:304-310`
   - `app/src/main/java/com/openfolio/core/data/local/export/AppBackupManager.kt:349-372`
   - `app/src/main/java/com/openfolio/core/data/local/export/AppBackupManager.kt:530-553`
2. **Evidence:**
   ```kotlin
   // AppBackupManager.kt:349-366
   transactionRunner(db) {
       val (tables, rows) = restoreTablesInTransaction(
           db = db,
           tablesObj = tablesObj,
           finalDocPathsById = finalDocPathsById,
           finalTextPathsById = finalTextPathsById,
       )
       ...
   }

   // Transaction committed: replace local inbox files and extracted text sidecars
   syncRestoredFiles(
       targetDir = customDocumentsDir,
       restoredByPath = ...
   )
   ```
   Inside `syncRestoredFiles` (`lines 534-552`):
   ```kotlin
   if (targetDir.exists()) {
       targetDir.listFiles()?.forEach { existing ->
           if (existing.isFile) {
               existing.delete()
           }
       }
   }
   ```
   Additionally, `importEncryptedBackup` verifies `dbMeta?.optString("format") == "OpenFolio Database Export"` (`line 305`) but never checks `databaseVersion` compatibility against `OpenFolioDatabase.SCHEMA_VERSION` (`OpenFolioDatabase.kt:64`).
3. **Failure Scenario / Impact:**
   If `syncRestoredFiles` fails mid-copy (e.g., `ENOSPC` disk full, I/O exception, or OS process termination), the SQLCipher database has already committed rows referencing `finalDocPathsById` and `finalTextPathsById`, while `existing.delete()` has already wiped the user's pre-restore documents and extracted text. Similarly, restoring a backup from a different DB schema version fails mid-transaction or leaves broken column defaults.
4. **Concrete Fix:**
   - Validate `dbMeta.optInt("databaseVersion") == OpenFolioDatabase.SCHEMA_VERSION` (or run explicit version upgrader) before writing anything.
   - Stage restored files into verified sibling staging directories first (`documents.staging`, `extracted_text.staging`), execute the Room DB transaction, atomically rename/swap directories upon commit (keeping `.bak` until the swap succeeds), and delete `.bak` only after full completion.

---

### P0-3. Deprecated `android-database-sqlcipher:4.5.4` Lacks Security Patches & Android 15+ 16 KB Page Alignment
1. **File & Line Reference:**
   - `gradle/libs.versions.toml:10,45`
   - `app/build.gradle.kts:12,17,117`
   - `app/src/main/java/com/openfolio/core/di/DatabaseModule.kt:32-33,44,52`
2. **Evidence:**
   ```toml
   # gradle/libs.versions.toml:10,45
   sqlcipher = "4.5.4"
   sqlcipher = { group = "net.zetetic", name = "android-database-sqlcipher", version.ref = "sqlcipher" }
   ```
   `app/build.gradle.kts:12,17` targets `compileSdk = 35`, `targetSdk = 35`.
3. **Failure Scenario / Impact:**
   Zetetic deprecated and discontinued Maven Central Community Edition releases for `net.zetetic:android-database-sqlcipher` in 2023 after 4.5.4/4.5.5, replacing it with `net.zetetic:sqlcipher-android`. The legacy `4.5.4` AAR receives no SQLite/OpenSSL security fixes and its native `.so` binaries are **not** 16 KB ELF page-aligned (`4.6.1+` in `sqlcipher-android` added 16 KB support). Because `targetSdk = 35` targets Android 15+, Play Store distribution and 16 KB page-size devices risk installation rejection or native `dlopen` / `SQLiteException` crashes on startup.
4. **Concrete Fix:**
   - Replace `net.zetetic:android-database-sqlcipher:4.5.4` with `net.zetetic:sqlcipher-android:4.6.1` (or latest 4.x release in `libs.versions.toml`).
   - Update `DatabaseModule.kt` to load `System.loadLibrary("sqlcipher")` and instantiate `net.zetetic.database.sqlcipher.SupportOpenHelperFactory`, and update `app/proguard-rules.pro` (`-keep class net.zetetic.database.** { *; }`).

---

### P0-4. Silent Plaintext Fallback for Gemini API Key & Irrecoverable Keystore Crash on Partial DB Key Loss
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/core/data/repository/SettingsRepository.kt:19-33,44-48`
   - `app/src/main/java/com/openfolio/core/data/local/DatabasePassphraseProvider.kt:23-38`
2. **Evidence:**
   ```kotlin
   // SettingsRepository.kt:19-33
   private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

   private val prefs by lazy {
       try {
           EncryptedSharedPreferences.create(
               "openfolio_secure_settings",
               masterKeyAlias,
               context,
               EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
               EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
           )
       } catch (_: Exception) {
           context.getSharedPreferences("openfolio_prefs_fallback", Context.MODE_PRIVATE)
       }
   }

   // DatabasePassphraseProvider.kt:35-37
   check(storedIv == null && storedCiphertext == null) {
       "The encrypted database key is incomplete."
   }
   ```
3. **Failure Scenario / Impact:**
   - **Silent Plaintext Downgrade:** On Keystore/OEM keyset glitches (a well-known issue with deprecated `androidx.security:security-crypto:1.1.0-alpha06`), `SettingsRepository` silently falls back to `openfolio_prefs_fallback.xml` and writes the user's Gemini API key in **cleartext** without notifying the user or cleaning up prior encrypted state. Additionally, `MasterKeys.getOrCreate` runs during singleton construction, performing blocking Keystore I/O.
   - **Boot Crash Loop:** If `openfolio_database_key.xml` loses either `iv` or `ciphertext` (or if the Keystore entry becomes invalidated by OEM security resets/restores while prefs remain), `DatabasePassphraseProvider.getPassphrase()` throws `IllegalStateException` inside Hilt's `@Singleton` `DatabaseModule.provideDatabase()`, crashing the app unconditionally before any UI renders.
4. **Concrete Fix:**
   - Delete the plaintext `openfolio_prefs_fallback` fallback; if Keystore decryption fails for settings, emit a structured error state requiring user reset of the API key rather than storing secrets in plaintext.
   - Migrate away from deprecated `security-crypto:1.1.0-alpha06` (`MasterKeys`/`EncryptedSharedPreferences`) to Jetpack DataStore encrypted with Google Tink + Android Keystore.
   - In `DatabasePassphraseProvider`, guard Keystore/AEAD failures and expose an explicit recovery path (e.g., restore from encrypted `.ofbackup` or reset corrupted store with user consent) instead of crashing inside Dagger/Hilt graph creation.

---

### P0-5. Unvalidated AI Enum / Numeric Strings & Unchecked Room `TypeConverter`s Cause Uncaught Crashes
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/core/data/repository/DocumentRepositoryImpl.kt:365-370,388`
   - `app/src/main/java/com/openfolio/core/data/remote/ai/GeminiAiProvider.kt:566-573,616-619`
   - `app/src/main/java/com/openfolio/core/data/local/Converters.kt:18,30,36,42,48,54,60-62`
2. **Evidence:**
   ```kotlin
   // GeminiAiProvider.kt:566-572, 616-619
   val assetClass = if (rawAssetClass in ALLOWED_ASSET_CLASSES) rawAssetClass else "STOCK"
   val transactionType = obj.optString("transactionType", "BUY").trim().uppercase() // UNVALIDATED!
   ...
   return runCatching {
       val clean = stringValue.replace(",", "").trim()
       BigDecimal(clean).toPlainString()
   }.getOrDefault(stringValue) // Falls back to unparseable stringValue!

   // DocumentRepositoryImpl.kt:368-370, 388
   val quantity = BigDecimal(cleanQty).abs()
   var price = BigDecimal(cleanPrice).abs()
   var netAmount = BigDecimal(cleanNetAmount).abs()
   ...
   type = TransactionType.valueOf(draft.transactionType),
   ```
   And in `Converters.kt`: only `toExtractionStatus` (`lines 60-62`) wraps `valueOf` in `runCatching`; `toBigDecimal` (`line 18`) and `toAccountType`, `toAssetClass`, `toAssetStatus`, `toTransactionType`, `toTransactionStatus` (`lines 30, 36, 42, 48, 54`) call raw `BigDecimal(it)` / `valueOf(it)`.
3. **Failure Scenario / Impact:**
   Gemini can return `transactionType` values outside `TransactionType` (e.g., `"SIP"`, `"REDEMPTION"`, `"PURCHASE"`, `"SWITCH_IN"`) or non-numeric strings (`"N/A"`, `"--"`), which `GeminiAiProvider` stores directly into `draft_transactions`. When the user confirms the drafts in `ReviewScreen`, `DocumentRepositoryImpl.saveDraftTransactions()` crashes with an uncaught `IllegalArgumentException` or `NumberFormatException`. Similarly, manual backup JSON or corrupt DB enum strings crash all Room queries through `Converters`.
4. **Concrete Fix:**
   - Normalize/whitelist `transactionType` against `TransactionType.entries` (falling back to `"OTHER"` or common synonyms like `"REDEMPTION" -> "SELL"`) and reject non-numeric strings in `GeminiAiProvider.extractJsonString` (defaulting to `"0"`/`"1"` and lowering `confidence`).
   - Validate `BigDecimal` parsing and `TransactionType` in `saveDraftTransactions` with user-friendly validation feedback.
   - Wrap `Enum.valueOf` in `Converters.kt` with safe fallbacks (`OTHER`/`STOCK`/`CONFIRMED`) or `runCatching` matching `toExtractionStatus`.

---

### P0-6. Sensitive Financial Files, DB Exports & Unredacted PII Exposed in Plaintext on Disk and Sent to Cloud AI
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/core/data/local/ExtractedTextStore.kt:24,30-34`
   - `app/src/main/java/com/openfolio/core/data/repository/DocumentRepositoryImpl.kt:809-842,1383-1389`
   - `app/src/main/java/com/openfolio/feature/settings/SettingsViewModel.kt:180-199,254-264,467-477`
   - `app/src/main/java/com/openfolio/core/data/local/export/AppBackupManager.kt:152,273-274`
   - `app/src/main/java/com/openfolio/core/data/remote/ai/GeminiAiProvider.kt:260-288,688-695`
2. **Evidence:**
   - `ExtractedTextStore`: writes full extracted statement pages to `File(context.filesDir, "extracted_text/<documentId>.txt")` unencrypted.
   - `DocumentRepositoryImpl`: copies raw imported PDFs/CSVs/XLSXs/images to `File(baseFilesDir, "documents/<uuid>.<ext>")` unencrypted.
   - `SettingsViewModel`: `exportAndShareDatabase()`, `exportAndShareDossier()`, `exportAndShareCsvReport()` write plaintext DB JSON, Markdown succession dossiers, and financial CSVs into `File(context.cacheDir, "exports")` (exposed via `FileProvider` in `res/xml/file_paths.xml`), and **never delete** stale files afterward.
   - `AppBackupManager`: writes unencrypted intermediate `backup_tmp_*.zip` and `restore_tmp_*.zip` + `restore_extract_*` (containing `database.json` and, when enabled, the plaintext Gemini API key in `manifest.json`) in `cacheDir`; process termination leaves them on disk.
   - `GeminiAiProvider`: transmits raw extracted text unredacted to `generativelanguage.googleapis.com` and explicitly instructs Gemini to extract `"activeFolio"` (`line 689`) and `"activeAccountId"` (`line 692`), while `line 288` logs `exception.message` via `Log.e` in release builds.
3. **Failure Scenario / Impact:**
   SQLCipher protects `openfolio.db`, yet full copies of the user's raw bank/broker PDFs, extracted page text, and shared JSON/CSV dumps remain unencrypted on app storage or indefinitely in `cacheDir/exports` (contrary to the threat model and planned PII redaction). Meanwhile, unredacted folio/account/PAN numbers are uploaded to external servers and exception payloads risk leaking into Android Logcat.
4. **Concrete Fix:**
   - Encrypt files written by `ExtractedTextStore` and `DocumentRepositoryImpl` (`documents/`) using Android Keystore-backed Tink `StreamingAead` (or AES-GCM chunked streams).
   - Purge `cacheDir/exports/` and leftover `backup_tmp_*` / `restore_*` artifacts on app startup (`OpenFolioApplication`) and via a WorkManager cleanup/TTL policy.
   - Implement a local PII scrubber (masking PAN, folio numbers, account IDs, email/phone) before transmitting statement text to `AiProvider`.

---

### P0-7. Unbounded Archive Decompression (Zip Bomb / OOM) & Missing `disallow-doctype-decl` in Restore & XLSX Import
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/core/data/local/export/AppBackupManager.kt:291,300,555-584`
   - `app/src/main/java/com/openfolio/core/utils/XlsxExtractor.kt:25-44,139-151`
2. **Evidence:**
   ```kotlin
   // AppBackupManager.kt:576-578 (unpackZipSafely)
   FileOutputStream(targetFile).use { out ->
       zipIn.copyTo(out, IO_BUFFER_SIZE)
   }
   // Followed at lines 291 & 300 by unbounded in-memory reads:
   manifestFile.readText(StandardCharsets.UTF_8)
   databaseFile.readText(StandardCharsets.UTF_8)

   // XlsxExtractor.kt:139-150
   private fun createSaxParser(): SAXParser {
       val factory = SAXParserFactory.newInstance()
       ...
       factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
       factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
       factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
       // Missing: http://apache.org/xml/features/disallow-doctype-decl
   ```
3. **Failure Scenario / Impact:**
   While `unpackZipSafely` properly blocks Zip Slip (`..`, `/`, `canonicalPath`), neither backup unpacking nor `XlsxExtractor` bounds total uncompressed bytes, entry count, or compression ratio. A crafted `.ofbackup` (after decryption) or malicious `.xlsx` statement (1 MB compressed expanding to multi-GB XML/JSON) causes disk exhaustion or `OutOfMemoryError` when loading `database.json`, `sharedStrings`, and `allRows` into heap. Omitting `disallow-doctype-decl` also permits DTD entity expansion attempts where supported by XML parsers.
4. **Concrete Fix:**
   - Replace `zipIn.copyTo` in `unpackZipSafely` with a bounded copy loop enforcing max entry count, max per-entry size, and max cumulative uncompressed bytes.
   - Bound uncompressed entry sizes for `xl/sharedStrings.xml` and worksheet XML in `XlsxExtractor`, cap parsed rows/cells in `extract()`, and set `factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`.

---

## P1 — High

### P1-1. Process-Death & Configuration-Change State Loss Breaks File Imports, Backups, and Forms
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/feature/screens/HomeScreen.kt:173-178`
   - `app/src/main/java/com/openfolio/feature/screens/InboxScreen.kt:101,113-118`
   - `app/src/main/java/com/openfolio/feature/screens/SettingsScreen.kt:108-112,130-162`
   - `app/src/main/java/com/openfolio/core/ui/components/ManualTransactionDialog.kt:110-135`
   - `app/src/main/java/com/openfolio/feature/screens/SellAssetDialog.kt:61-70`
   - `app/src/main/java/com/openfolio/feature/screens/ReviewScreen.kt:141-146,898-912`
   - `app/src/main/java/com/openfolio/feature/screens/AssetDetailScreen.kt:961-973,1342-1343,1422-1440`
2. **Evidence:**
   None of the 7 ViewModels inject `SavedStateHandle`, and all Compose screens use `remember { mutableStateOf(...) }` rather than `rememberSaveable`.
   - In `HomeScreen.kt:173` and `InboxScreen.kt:101`: `var pendingAssetClass by remember { mutableStateOf(AssetClass.STOCK) }` is captured inside `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())`.
   - In `SettingsScreen.kt:109-112,133-143,157-161`: `pendingBackupPassword`, `pendingIncludeApiKey`, and `pendingCsvType` use `remember`; `backupCreateLauncher` checks `if (uri != null && password.isNotEmpty())` and `csvCreateLauncher` checks `if (uri != null && reportType != null)`.
3. **Failure Scenario / Impact:**
   When Android backgrounds `MainActivity` to open the system SAF document picker/saver (`OpenDocument` / `CreateDocument`) and reclaims the process under memory pressure:
   - Selecting a Mutual Fund, Crypto, or FD statement silently resets `pendingAssetClass` to `AssetClass.STOCK`, misclassifying all extracted drafts/assets.
   - Creating a backup or CSV report resets `pendingBackupPassword` (`""`) or `pendingCsvType` (`null`); upon return, the callback exits without writing anything, leaving a 0-byte empty file at the user's chosen URI.
   - Rotating the device or backgrounding mid-edit discards manual transaction forms, PDF passwords, search queries, and review column mappings.
4. **Concrete Fix:**
   - Move `pendingAssetClass` and `pendingCsvType` (and filter/period/search/document selection state) into `SavedStateHandle`-backed ViewModel state, and persist UI dialog inputs with `rememberSaveable` (custom `Saver` where needed).
   - For encrypted backups, either choose the destination URI first and prompt for the password *after* `CreateDocument` returns, or delete the empty SAF URI cleanly if transient credentials were lost on recreation.

---

### P1-2. Financial Calculation Precision Drift (`Double` / SQLite `REAL`), `netWorth` Formula Flaws & Unmatched Sell Gain Distortion
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/core/data/local/dao/TransactionDao.kt:13-21,79-83,91-114`
   - `app/src/main/java/com/openfolio/core/domain/usecase/GetDashboardDataUseCase.kt:21-26,40-53`
   - `app/src/main/java/com/openfolio/feature/home/TrajectoryCalculator.kt:83,90-128`
   - `app/src/main/java/com/openfolio/core/domain/calculator/FifoCostBasisCalculator.kt:134-154`
   - `app/src/main/java/com/openfolio/core/data/local/entity/TransactionEntity.kt:63`
2. **Evidence:**
   - `TransactionDao`: `SUM(CAST(grossAmount AS REAL))` returns `Double?` / `Double` (`AssetClassAllocationRow.totalAmount: Double`, `DailyNetWorthDelta.delta: Double`) — directly violating `AGENTS.md` ("Never use `Double` for money. Use `BigDecimal` or `Long` (minor units)").
   - `GetDashboardDataUseCase.kt:22,25,40,46,49-52`: instantiates `BigDecimal(it)` (passing binary `Double`), calculates `netWorth = totalAssets - totalLiabilities` using `assetDao.getTotalPortfolioValueFlow()` (active holdings' `totalInvested` cost basis) minus `FEE`/`TAX` (excluding `CHARGE`, market value `currentPrice`, and `realizedPnL`), and line 46 executes `txn.copy(assetId = displayName)`.
   - `FifoCostBasisCalculator.kt:139-152`:
     ```kotlin
     for (lot in lots) {
         if (remainingToSell <= BigDecimal.ZERO) break
         ...
     }
     val saleGain = proceeds.subtract(costOfSoldShares)
     ```
     If `remainingToSell > BigDecimal.ZERO` (missing buy history), `costOfSoldShares` only sums matched lots while `proceeds` covers all sold units.
3. **Failure Scenario / Impact:**
   - SQLite `REAL` introduces floating-point drift on large INR totals and fractional MF/crypto quantities.
   - Deleting/selling an asset with realized profit reduces `totalInvested` while historical `FEE`/`TAX` remain subtracted, making displayed `netWorth` lower than actual equity/cash value; mutating `txn.assetId = displayName` corrupts provenance semantics inside UI models.
   - Partial buy history inflates `realizedPnL` by treating proceeds from unmatched units as zero-cost 100% capital gain.
4. **Concrete Fix:**
   - Return raw string/entity `BigDecimal` rows from `TransactionDao` (or minor-unit `Long` sums) and perform all summation and percentage division using `BigDecimal` arithmetic in Kotlin.
   - Replace `BigDecimal(double)` with `BigDecimal.valueOf(...)` only where unavoidable and remove `Double` from `AssetClassAllocationRow`, `DailyNetWorthDelta`, and `TrajectoryCalculator`.
   - Separate `assetId` from `assetDisplayName` in a dedicated dashboard recent-activity DTO, clarify/fix `netWorth` valuation vs cost-basis + `CHARGE`, and flag unmatched sell units (`unknownCostBasis = true`) instead of silently treating them as 0 cost.

---

### P1-3. Swallowed `CancellationException` Across Coroutines & Native PDF/Bitmap Leaks on Cancellation
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/feature/review/ImagePdfPreview.kt:171-185,237-254`
   - `app/src/main/java/com/openfolio/feature/review/TabularPreview.kt:83`
   - `app/src/main/java/com/openfolio/core/worker/DocumentImportWorker.kt:67-74`
   - `app/src/main/java/com/openfolio/core/data/repository/DocumentRepositoryImpl.kt:1226-1250,1561-1588`
   - `app/src/main/java/com/openfolio/feature/assets/AssetDetailViewModel.kt:138,161,177,223,237,282,301,320`
   - `app/src/main/java/com/openfolio/feature/activity/ActivityViewModel.kt:228`
   - `app/src/main/java/com/openfolio/feature/inbox/InboxViewModel.kt:56`
   - `app/src/main/java/com/openfolio/feature/settings/SettingsViewModel.kt:100,127,164,207,237,267`
2. **Evidence:**
   ```kotlin
   // ImagePdfPreview.kt:174-184
   try {
       val holder = withContext(Dispatchers.IO) {
           renderPdfFirstPage(filePath, password)
       }
       resourceHolder?.close()
       resourceHolder = holder
   } catch (_: Exception) { // Swallows CancellationException!
       errorMessage = "Preview unavailable"
   }

   // DocumentImportWorker.kt:67-74
   if (documentRepository.hasGeminiApiKey()) {
       runCatching {
           documentRepository.processNextAiBatch(documentId = documentId, onProgress = onProgress)
       }
   }
   Result.success()
   ```
   And `DocumentRepositoryImpl.kt:1226-1250` runs the legacy AI text chunk loop without `currentCoroutineContext().ensureActive()` or `CancellationException` DB status revert (unlike the sidecar loop), while lines `1561-1588` wrap `applicationScope.launch` bodies in `runCatching`. All 8 `catch (e: Exception)` blocks in `AssetDetailViewModel`, `rollbackImport` in `InboxViewModel:56`, `onConfirmDeleteTransaction` in `ActivityViewModel:228`, and 6 `catch (t: Throwable)` blocks in `SettingsViewModel` omit `if (t is CancellationException) throw t`.
3. **Failure Scenario / Impact:**
   - If the user navigates away or changes password while `renderPdfFirstPage` or `decodeSampledBitmapFromFile` is running on `Dispatchers.IO`, `withContext` throws `CancellationException` after allocating the `Bitmap`, `PdfRenderer`, and `ParcelFileDescriptor`; `holder` is never assigned to `resourceHolder`, leaking native memory/file descriptors until GC finalization, and `catch (_: Exception)` breaks structured concurrency.
   - Cancelling a `DocumentImportWorker` mid-batch returns `Result.success()` via `runCatching`.
4. **Concrete Fix:**
   - Explicitly rethrow `CancellationException` in every `catch` block and replace bare `runCatching` over `suspend` functions with a `runSuspendCatching` helper that rethrows `CancellationException`.
   - In `ImagePdfPreview`, manage cancellation cleanup inside `renderPdfFirstPage`/`decodeSampledBitmapFromFile` (close `PdfRenderer`/`PFD` and recycle `Bitmap` if `!coroutineContext.isActive` before returning) and call `ensureActive()` inside `DocumentRepositoryImpl`'s legacy chunk loop.

---

### P1-4. Race Conditions, Double-Tap Duplicates, Unchecked WorkManager Scheduling & Blocking `.get()`
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/feature/settings/SettingsViewModel.kt:79,85,95,107,145-173,176-217,226-276`
   - `app/src/main/java/com/openfolio/feature/assets/AssetDetailViewModel.kt:193-229`
   - `app/src/main/java/com/openfolio/feature/review/ReviewViewModel.kt:86-186,644-679`
   - `app/src/main/java/com/openfolio/core/worker/DocumentImportScheduler.kt:71-78,93-97`
   - `app/src/main/java/com/openfolio/feature/screens/OcrTableReviewScreen.kt:392-402`
2. **Evidence:**
   - `SettingsViewModel` updates `_uiState.value = _uiState.value.copy(...)` across Main (`onApiKeyChange`, collectors) and `ioDispatcher` (`loadDatabaseSummary`, export functions), and sets `isExporting = true` *inside* `viewModelScope.launch(ioDispatcher)` without checking if an export is already in flight.
   - `AssetDetailViewModel.recordSell` does not check `_isSaving.value`; line 218 stamps `"SELL_${currentAsset.id}_${tradeDate}_${System.currentTimeMillis()}"` as `fingerprint`.
   - `ReviewViewModel.loadDocument` launches an untracked outer `viewModelScope.launch { ... }` alongside cancelling only `draftsJob` and `documentJob`, and `saveConfirmed()` (`lines 644-678`) never returns early when `_actionState.value == ReviewActionState.Saving`.
   - `DocumentImportScheduler.enqueue` (`lines 93-97`) uses `ExistingWorkPolicy.KEEP`, while `DocumentRepositoryImpl.enqueueAiBatch`/`enqueueAiAutoProcess` (`lines 1569,1581`) already overwrite DB status to `PROCESSING` before enqueueing; line 75 blocks a thread via `.getWorkInfosForUniqueWork(...).get()`.
3. **Failure Scenario / Impact:**
   - Double-tapping **Confirm Sell** launches two concurrent `recordSell` coroutines with distinct millisecond fingerprints, bypassing deduplication and creating duplicate sell transactions / negative lot mismatches.
   - Concurrent `SettingsViewModel` state mutations drop fields (`apiKey` or `isExporting`), and double-tapping export/backup starts overlapping writes to shared files.
   - Rapidly switching documents in `ReviewViewModel` lets an earlier `loadDocument` coroutine attach stale collectors after a later call began; double-tapping Save launches duplicate `saveDraftTransactions` calls.
   - Triggering a new AI batch while a previous `openfolio_import_<id>` worker is still finalizing (`!state.isFinished`) drops the new request under `KEEP` while leaving the document stuck in `PROCESSING` forever.
4. **Concrete Fix:**
   - Replace `_uiState.value = _uiState.value.copy(...)` with `_uiState.update { ... }` across `SettingsViewModel`, and reject duplicate actions synchronously before `launch`.
   - Guard `recordSell`, `saveEditedTransaction`, `confirmDeleteTransaction`, `ReviewViewModel.saveConfirmed`, and `OcrTableReviewScreen` Approve button with single-flight loading flags.
   - Store and cancel `loadJob` in `ReviewViewModel.loadDocument()`.
   - Replace `ExistingWorkPolicy.KEEP` for user-triggered retries/next-batches (or use `ExistingWorkPolicy.APPEND_OR_REPLACE` / status gating only inside the worker) and await WorkManager `ListenableFuture` asynchronously via `awaitCancellable` instead of `.get()`.

---

### P1-5. Unencoded Dynamic Navigation Route Arguments Can Crash or Misroute `NavController`
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/feature/navigation/OpenFolioApp.kt:238-240,280-282,288-293`
2. **Evidence:**
   ```kotlin
   onAssetClick = { assetId -> navController.navigate("asset_detail/$assetId") }
   onNavigateToAsset = { assetId -> navController.navigate("asset_detail/$assetId") }
   onDocumentClick = { documentId -> navController.navigate("review/$documentId") }
   onProcessWithAiClick = { documentId -> navController.navigate("review/$documentId") }
   ```
3. **Failure Scenario / Impact:**
   If a restored backup, imported DB export, or deterministic asset/document ID contains URI-reserved characters (`/`, `?`, `#`, `%`), Jetpack Navigation fails to match the destination route and throws `IllegalArgumentException` at runtime or truncates query fragments.
4. **Concrete Fix:**
   Encode route arguments via `Uri.encode(assetId)` and `Uri.encode(documentId)` before constructing route strings (or adopt Navigation Compose type-safe `@Serializable` route classes).

---

### P1-6. Missing Android 12+ `dataExtractionRules` & Missing Network Security Config for API 26–27
1. **File & Line Reference:**
   - `app/src/main/AndroidManifest.xml:9-16`
   - `app/build.gradle.kts:16-17`
   - `app/build/reports/lint-results-debug.txt:225-243` (`[DataExtractionRules]`)
2. **Evidence:**
   ```xml
   <!-- app/src/main/AndroidManifest.xml:9-16 -->
   <application
       android:name=".OpenFolioApplication"
       android:allowBackup="false"
       ...
   ```
   No `android:dataExtractionRules`, no `android:fullBackupContent`, no `android:networkSecurityConfig`, and no `android:usesCleartextTraffic="false"`. Android Lint reports: `The attribute android:allowBackup is deprecated from Android 12 and higher... Consider adding the attribute android:dataExtractionRules`.
3. **Failure Scenario / Impact:**
   On Android 12+ (API 31+), `android:allowBackup="false"` disables cloud backups but **does not block device-to-device (D2D) transfers** unless `dataExtractionRules` explicitly excludes data—allowing unencrypted `extracted_text/`, `documents/`, and `openfolio_prefs_fallback` (if triggered) to transfer to a new device while Android Keystore keys stay behind. Meanwhile, `minSdk = 26` means Android 8.0–8.1 devices allow cleartext HTTP traffic by default unless restricted by `networkSecurityConfig` / `usesCleartextTraffic="false"`.
4. **Concrete Fix:**
   - Add `res/xml/data_extraction_rules.xml` (`<cloud-backup>` and `<device-transfer>` both excluding `.`) and `res/xml/backup_rules.xml` (`fullBackupContent`) referenced from `AndroidManifest.xml`.
   - Set `android:usesCleartextTraffic="false"` and configure `res/xml/network_security_config.xml` (`cleartextTrafficPermitted="false"` with explicit system trust anchors).

---

### P1-7. Activity-Scoped ViewModels Retain Unlocked Passwords & Trigger Root-Level Recompositions
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/MainActivity.kt:23-50`
   - `app/src/main/java/com/openfolio/feature/navigation/OpenFolioApp.kt:60-90`
2. **Evidence:**
   ```kotlin
   // MainActivity.kt:24-30
   private val homeViewModel: HomeViewModel by viewModels()
   private val assetsViewModel: AssetsViewModel by viewModels()
   private val assetDetailViewModel: AssetDetailViewModel by viewModels()
   private val activityViewModel: ActivityViewModel by viewModels()
   private val inboxViewModel: InboxViewModel by viewModels()
   private val reviewViewModel: ReviewViewModel by viewModels()
   private val settingsViewModel: SettingsViewModel by viewModels()
   ```
   `OpenFolioApp` (`lines 72-88`) calls `.collectAsStateWithLifecycle()` on 17 `StateFlow`s at the root composable above `NavHost`.
3. **Failure Scenario / Impact:**
   Screen-scoped ViewModels (`ReviewViewModel`, `AssetDetailViewModel`, `SettingsViewModel`) never get cleared when leaving their screens. Notably, `_unlockedPassword` (user's sensitive PDF bank-statement password in `ReviewViewModel`) stays pinned in Activity-scoped heap memory for the entire app session even after finishing review, and state updates in Home/Inbox/Review recompose the entire `OpenFolioApp` root shell.
4. **Concrete Fix:**
   Remove the 7 Activity-level `by viewModels()` properties and resolve each ViewModel at its `NavBackStackEntry` via `hiltViewModel()`, collecting screen states locally within each screen composable and scrubbing `_unlockedPassword` once extraction/preview closes.

---

## P2 — Medium / Tech Debt

### P2-1. Main-Thread Heavy Flows, Eager Dashboard Collection & Synchronous Startup Keystore Initialization
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/feature/activity/ActivityViewModel.kt:63,65-169`
   - `app/src/main/java/com/openfolio/feature/assets/AssetsViewModel.kt:95-107`
   - `app/src/main/java/com/openfolio/feature/home/HomeViewModel.kt:61-72`
   - `app/src/main/java/com/openfolio/core/data/repository/SettingsRepository.kt:19-48`
2. **Evidence:**
   - `ActivityViewModel` runs `FifoCostBasisCalculator.calculateTransactionGains(assetTxns)` across all asset groups, totals, filters, and month grouping inside `combine { ... }` without `.flowOn(Dispatchers.Default)` and captures `Locale.getDefault()` once in `monthYearFormatter`.
   - `AssetsViewModel` executes filtering/sorting/BigDecimal summarization without `.flowOn(Dispatchers.Default)`.
   - `HomeViewModel.uiState` collects with `SharingStarted.Eagerly` and invokes `TrajectoryCalculator.calculate(...)` on the Main collector without `.flowOn(Dispatchers.Default)`.
   - `SettingsRepository` synchronously calls `MasterKeys.getOrCreate(...)` and reads `EncryptedSharedPreferences` during `init`.
3. **Failure Scenario / Impact:**
   Large transaction histories introduce UI jank / skipped frames during Main-thread recomputation; `HomeViewModel` keeps running SQLCipher dashboard queries when the user is on other tabs; runtime locale changes do not update Activity month headings; main-thread Hilt injection triggers synchronous Keystore + file I/O.
4. **Concrete Fix:**
   Add `.flowOn(Dispatchers.Default)` before `.stateIn(...)` in `ActivityViewModel`, `AssetsViewModel`, and `HomeViewModel`; switch `HomeViewModel` to `SharingStarted.WhileSubscribed(5_000)`; defer `SettingsRepository` Keystore loading to an injected `Dispatchers.IO` initializer.

---

### P2-2. Release Hardening, Disabled CI Quality Gates, Overly Broad ProGuard Rules & Release Logging
1. **File & Line Reference:**
   - `app/build.gradle.kts:79-90`
   - `app/proguard-rules.pro:12-46`
   - `.github/workflows/build-release-apk.yml:71-73`
   - `.github/workflows/android.yml:36-37`
   - `app/src/main/java/com/openfolio/core/data/remote/ai/GeminiAiProvider.kt:288`
   - `app/src/main/java/com/openfolio/core/data/repository/DocumentRepositoryImpl.kt:414`
2. **Evidence:**
   - `app/build.gradle.kts:80-83`: `if (releaseSigning.storeFile != null) signingConfig = releaseSigning` silently produces an unsigned release build if keystore files are missing; `lines 87-90`: `lint { abortOnError = false; checkReleaseBuilds = false }`.
   - `.github/workflows/build-release-apk.yml:72-73`: `./gradlew lintRelease --no-daemon` has `continue-on-error: true`.
   - `.github/workflows/android.yml:36-37`: `./gradlew --no-daemon :app:testDebugUnitTest` is commented out.
   - `app/proguard-rules.pro`: keeps entire packages (`net.sqlcipher.**`, `com.opencsv.**`, `com.tom_roush.pdfbox.**`, `com.openfolio.core.data.local.entity.**`, `com.openfolio.core.domain.model.**`, `com.openfolio.core.worker.**`, `androidx.hilt.work.**`) plus nonexistent `com.openfolio.core.data.local.model.**` (`line 36`) and `-dontwarn`.
   - `GeminiAiProvider.kt:288` (`Log.e(...)`) and `DocumentRepositoryImpl.kt:414` (`Log.d("SaveTxn", ...)`) run unguarded in release builds.
3. **Failure Scenario / Impact:**
   Pull requests can merge failing unit tests or new lint errors unnoticed; overly broad `-keep` + `-dontwarn` rules defeat R8 shrinking/obfuscation on release APKs; `Log.e` can leak HTTP/JSON error snippets into system logs.
4. **Concrete Fix:**
   Uncomment unit tests in `android.yml`, enable `abortOnError = true` and `checkReleaseBuilds = true` after triaging lint findings, narrow ProGuard rules to actual JNI/reflection/Room/Worker entry points, and remove/gate `Log.*` behind `BuildConfig.DEBUG`.

---

### P2-3. Transitive Dependency Vulnerabilities (`commons-beanutils:1.9.4`, `bcpkix:1.72`) & Outdated / Deprecated Libraries
1. **File & Line Reference:**
   - `gradle/libs.versions.toml:2,3,5-9,11,16,17,19-21,24-25`
   - `app/build.gradle.kts:119,121,122`
   - `app/build/reports/lint-results-debug.txt:1-186,213-223` (`[AndroidGradlePluginVersion]`, `[GradleDependency]`, `[TrustAllX509TrustManager]`)
   - Gradle CLI outputs (`:app:dependencies` & `:app:dependencyInsight`)
2. **Evidence:**
   - `com.opencsv:opencsv:5.9` transitively resolves `commons-beanutils:commons-beanutils:1.9.4` (affected by **CVE-2025-48734**, CVSS 8.8; fixed in `1.11.0`) and EOL `commons-collections:commons-collections:3.2.2`.
   - `com.tom-roush:pdfbox-android:2.0.27.0` transitively resolves `org.bouncycastle:bcpkix-jdk15to18:1.72`, triggering 3 Android Lint `[TrustAllX509TrustManager]` warnings (`checkClientTrusted`/`checkServerTrusted` empty).
   - `androidx.security:security-crypto:1.1.0-alpha06` generates 4 Kotlin compiler deprecation warnings (`MasterKeys`, `EncryptedSharedPreferences.create` deprecated in `SettingsRepository.kt:5,19,23`).
   - `kapt` warns (`language version 2.0+. Falling back to 1.9`), and Android Lint flags 54 `GradleDependency` + 3 `AndroidGradlePluginVersion` (`8.7.3` -> `9.5.3`) updates.
3. **Failure Scenario / Impact:**
   Transitive libraries fail SCA security scans (`CVE-2025-48734`), trigger Google Play `TrustAllX509TrustManager` pre-launch automated rejections/reviews unless stripped or proven unreachable, and rely on deprecated crypto/Kapt toolchains.
4. **Concrete Fix:**
   - Either replace `opencsv` with a lightweight RFC-4180 Kotlin CSV reader/writer (eliminating `commons-beanutils` and `commons-collections` entirely) or constrain `commons-beanutils >= 1.11.0` in `libs.versions.toml`.
   - Verify with R8 (`configuration`/`usage.txt`) that `bcpkix` `TrustManager` classes are stripped (or explicitly exclude unused BouncyCastle TLS/OCSP modules if unneeded by PDFBox reading), and migrate Hilt from `kapt` to KSP once compatible.

---

### P2-4. Compose Performance Traps, Sheet Clipping, Blocked Navigation & Design System (`AGENTS.md`) Violations
1. **File & Line Reference:**
   - `app/src/main/java/com/openfolio/feature/screens/HomeScreen.kt:634-679`
   - `app/src/main/java/com/openfolio/feature/screens/ReviewScreen.kt:158-170,1425-1541`
   - `app/src/main/java/com/openfolio/feature/review/TabularPreview.kt:143,195-205`
   - `app/src/main/java/com/openfolio/feature/screens/SettingsScreen.kt:177-178,398`
   - `app/src/main/java/com/openfolio/feature/screens/ActivityScreen.kt:376-389,818,988`
   - `app/src/main/java/com/openfolio/feature/screens/SellAssetDialog.kt:263-271`
   - `app/src/main/java/com/openfolio/core/ui/components/MoneyText.kt:41-54`
   - `app/src/main/java/com/openfolio/core/ui/theme/OpenFolioColors.kt:53-54`
   - `app/src/main/java/com/openfolio/feature/screens/AssetsScreen.kt:99,300,309,854,917` & `AssetDetailScreen.kt:510` & `DocumentPreview.kt:19` & `ImagePdfPreview.kt:157`
2. **Evidence:**
   - `HomeScreen`: `Canvas` allocates `Path`, `Brush.verticalGradient`, `Stroke`, and `PathEffect.dashPathEffect(floatArrayOf(...))` on every draw pass (`lines 634-679`) and calls deprecated `quadraticBezierTo` (`line 643`).
   - `ReviewScreen`: `ManualDraftForm` (`line 1425`) lacks `verticalScroll(rememberScrollState())` inside a `ModalBottomSheet`; `LaunchedEffect(actionState)` (`lines 158-170`) awaits `snackbarHostState.showSnackbar(...)` *before* invoking `onSaveComplete()` / `onNavigateToInbox()`.
   - `SettingsScreen`: `context.startActivity(chooserIntent)` (`line 178`) has no `ActivityNotFoundException` catch; `line 398` displays `"Database Export (Testing)"` to end users.
   - **Design System (`AGENTS.md`):**
     - `ActivityScreen.kt:382,387` and `SellAssetDialog.kt:265,270` color numeric P&L amounts with `colors.gainGlyph`/`colors.lossGlyph` instead of neutral `colors.content` (`#1A1D1F` light / `#E6E8E6` dark) paired with a colored glyph.
     - `ActivityScreen.kt:382` bypasses `formatIndianRupees` (`"$sign₹${totalRealizedGain.toPlainString()}"` -> `"₹-100"`), and `MoneyText.kt:53` formats negatives as `"₹ -100.00"` rather than `"-₹ 100.00"`.
     - `TabularPreview.kt:195-196` builds a raw `TextStyle(fontFamily = FontFamily.Monospace)` rather than `MaterialTheme.typography` and uses unkeyed `itemsIndexed(rows)` (`line 143`).
     - `DarkOpenFolioColors.chartPalette[0]` (`OpenFolioColors.kt:54`) uses light-mode `0xFF0F6E5C` instead of `0xFF2A9C86`.
     - Android Lint `[ModifierParameter]` flags `AssetsScreen.kt:99,854`, `DocumentPreview.kt:19`, and `ImagePdfPreview.kt:157`; Kotlin compiler warns on deprecated `outlinedButtonBorder` and non-`AutoMirrored` icons.
3. **Failure Scenario / Impact:**
   `ManualDraftForm` becomes unusable on compact displays or enlarged system font scales; saving drafts freezes the Review screen for ~4 seconds while the snackbar is visible; sharing fails if no share target exists; P&L visuals and currency strings diverge from OpenFolio's accessibility/design rules.
4. **Concrete Fix:**
   Cache `Path`/`Brush`/`PathEffect` with `Modifier.drawWithCache`, add `verticalScroll` to `ManualDraftForm`, trigger navigation immediately while showing feedback in the parent/Inbox snackbar, catch `ActivityNotFoundException`, remove `" (Testing)"`, use `colors.content` + `formatIndianRupees` with `-₹` prefix on negative amounts, apply `MaterialTheme.typography`, and fix the 4 `ModifierParameter` signatures.

---

### P2-5. Redundant `ACCESS_NETWORK_STATE` Permission, Duplicate Status Enums & Missing Worker / Instrumented Tests
1. **File & Line Reference:**
   - `app/src/main/AndroidManifest.xml:4`
   - `app/src/main/java/com/openfolio/core/worker/DocumentImportScheduler.kt:80-97`
   - `app/src/main/java/com/openfolio/core/data/local/entity/DocumentEntity.kt:9-20` vs `Enums.kt:56-67`
   - `app/src/test/java/com/openfolio/core/data/repository/DocumentRepositoryImplTest.kt:665-744`
   - `app/src/androidTest` (absent)
2. **Evidence:**
   - `ACCESS_NETWORK_STATE` is declared in `AndroidManifest.xml:4`, yet codebase searches found zero `ConnectivityManager` / `NetworkCapabilities` references and `DocumentImportScheduler.enqueue` sets no `Constraints` (`NetworkType.CONNECTED`).
   - `DocumentStatus` (`DocumentEntity.kt:9-20`) and `ExtractionStatus` (`Enums.kt:56-67`) define identical 10-value enums, while `DocumentEntity` persists both `extractionStatus: ExtractionStatus` (`line 30`) and `status: String` (`line 32`).
   - `DocumentRepositoryImplTest` disables two tests via commented `// @Test` + `@Ignore` (`saveDraftTransactionsAssignsUniqueIdsWhenDraftIdsAreIdentical` at `line 666` and `saveDraftTransactionsRespectsSelectedDraftIds` at `line 743`).
   - Zero tests exist for `DocumentImportWorker` and zero UI/Room instrumented tests exist under `app/src/androidTest`.
3. **Failure Scenario / Impact:**
   Over-scoped permission surface; duplicated document status fields risk state drift if updated independently; WorkManager foreground service promotion (`SystemForegroundService`) and transaction deduplication/selection edge cases lack regression protection.
4. **Concrete Fix:**
   Add `Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)` for AI WorkManager tasks (or drop `ACCESS_NETWORK_STATE`), consolidate `DocumentStatus`/`ExtractionStatus`, fix the two `@Ignore` tests using an in-memory/Robolectric Room DB or extracted transaction seam, and add `DocumentImportWorkerTest` + `androidTest` Compose/Room suites.

---

## P3 — Cleanup & Dead Code

### Summary of Estimated Savings
- **Production Kotlin Source Files (18 unreferenced/unreachable files):** **111,736 bytes (~109.1 KiB)**
- **Leftover Root / Duplicate / Temp Workspace Files (5 files + 1 empty directory):** **1,976,427 bytes (~1.88 MiB)**
- **Total Safe-to-Remove Footprint (23 files + 1 empty directory):** **2,088,163 bytes (~1.99 MiB)** *(or **2,128,726 bytes / ~2.03 MiB** across 31 files if paired orphaned unit test files — 40,563 bytes — are deleted alongside the dead production classes)*
- **Android Lint `UnusedResources` / `UnusedIds`:** **0 warnings** (`app/src/main/res/` and `Inter-OFL.txt` license file in `assets/fonts/` have no unused files).

---

### P3-1. Unreferenced & Unreachable Production Kotlin Files (18 Files — 111,736 Bytes)

1. **`app/src/main/java/com/openfolio/core/domain/model/AssetClass.kt`** (`184 B`)
   - **Evidence:** Zero references anywhere in `app/src` (`AssetClass` references resolve to `com.openfolio.core.data.local.entity.AssetClass`). Notice its values (`MF`, `FD`) even conflict with `entity.AssetClass` (`MUTUAL_FUND`, `FD`, `SGB`).
   - **Impact:** Safe to delete immediately; eliminates duplicate/conflicting enum confusion.
   - **Fix:** Delete `app/src/main/java/com/openfolio/core/domain/model/AssetClass.kt`.

2. **Unused `DocumentParser` SPI & Extractors (3 Files — `4,343 B`):**
   - `app/src/main/java/com/openfolio/core/utils/DocumentParser.kt` (`186 B`)
   - `app/src/main/java/com/openfolio/core/utils/PdfTextExtractor.kt` (`3,651 B`)
   - `app/src/main/java/com/openfolio/core/utils/CsvTextExtractor.kt` (`506 B`)
   - (Linked in `app/src/main/java/com/openfolio/core/di/DocumentParserModule.kt:17-23`)
   - **Evidence:** `DocumentParserModule` binds `PdfTextExtractor` and `CsvTextExtractor` into `Set<DocumentParser>` via `@IntoSet`, which is never injected anywhere in `app/src`; extraction uses `DocumentTextExtractor` -> `LocalDocumentTextExtractor` + `StreamingPdfProcessor`. (`PdfTextExtractorTest.kt` `1,619 B` & `CsvTextExtractorTest.kt` `932 B` only test the unused classes.)
   - **Fix:** Remove `@IntoSet` bindings in `DocumentParserModule.kt` and delete the 3 production files (and optionally their 2 unit test files).

3. **Dead Local Statement Parsers Injected Without Callers (2 Files — `39,839 B`):**
   - `app/src/main/java/com/openfolio/core/utils/CasStatementParser.kt` (`18,002 B`)
   - `app/src/main/java/com/openfolio/core/utils/BrokerStatementParser.kt` (`21,837 B`)
   - **Evidence:** Both are constructor parameters in `DocumentRepositoryImpl.kt:94-95` (`casStatementParser`, `brokerStatementParser`) with **0 method calls** anywhere in `app/src/main`. (Only `CasStatementParserTest.kt` `9,180 B`, `CasStatementParserStreamingTest.kt` `3,083 B`, `BrokerStatementParserTest.kt` `5,653 B`, `BrokerStatementParserStreamingTest.kt` `2,293 B` exercise them.)
   - **Fix:** Unless local regex CAS/broker parsing is intentionally scheduled for wiring into `DocumentRepositoryImpl`, remove constructor injection from `DocumentRepositoryImpl.kt:94-95` and delete both files (or archive/wire them intentionally).

4. **Unreachable Local Table-Extraction UI & Pipeline (5 Files — `50,788 B`):**
   - `app/src/main/java/com/openfolio/feature/screens/OcrTableReviewScreen.kt` (`22,777 B`) — **0 callers anywhere**
   - `app/src/main/java/com/openfolio/core/tableextraction/LocalTableExtractionPipeline.kt` (`2,378 B`) — unused constructor param on `DocumentRepositoryImpl.kt:96`, only called by unreachable `ReviewViewModel.extractTablesLocally`
   - `app/src/main/java/com/openfolio/core/tableextraction/GeometryTextExtractor.kt` (`10,001 B`) — only called by `LocalTableExtractionPipeline`
   - `app/src/main/java/com/openfolio/core/tableextraction/TableDetectionEngine.kt` (`14,075 B`) — only called by `LocalTableExtractionPipeline` (+ `TableDetectionEngineTest.kt` `14,172 B`)
   - `app/src/main/java/com/openfolio/core/tableextraction/GeometryModels.kt` (`1,557 B`) — only used by `GeometryTextExtractor` & `TableDetectionEngine`
   - **Evidence:** `OpenFolioApp.kt:87-88,351-355` passes `extractedTables`, `showTableReview`, `onExtractTablesLocally`, `onDismissTableReview`, and `onApproveExtractedTable` into `ReviewScreen.kt:132-136`, which **never reads or renders any of those parameters**. Consequently, `OcrTableReviewScreen` and the entire geometry table-detection pipeline are unreachable in the app. (*Note:* `CsvExtractor.kt:24,41,61` still uses `CsvTableGenerator.COMMA_TOKEN`, so `CsvTableGenerator.kt` must be retained unless `COMMA_TOKEN` is moved into `CsvExtractor.kt`.)
   - **Fix:** Either wire `showTableReview` in `ReviewScreen` to open `OcrTableReviewScreen`, or remove the unreachable params in `ReviewScreen` / `OpenFolioApp` / `ReviewViewModel` / `DocumentRepositoryImpl` and delete these 5 files cleanly.

5. **Unused Domain Metadata Models (1 File — `12,181 B`):**
   - `app/src/main/java/com/openfolio/core/domain/model/AssetMetadataModels.kt` (`12,181 B`)
   - **Evidence:** `StockMetadata`, `MutualFundMetadata`, `FixedDepositMetadata`, `RealEstateMetadata`, `BondSgbMetadata`, `CryptoMetadata` have **0 production callers** in `app/src/main` (only `AssetMetadataModelsTest.kt` `3,631 B`).
   - **Fix:** Delete `AssetMetadataModels.kt` (and `AssetMetadataModelsTest.kt`) unless planned for immediate detail-screen metadata editors.

6. **Unused Room Entities & DAOs Wired Without Consumers (6 Files — `4,401 B`):**
   - `app/src/main/java/com/openfolio/core/data/local/AppMetadataDao.kt` (`467 B`) & `AppMetadataEntity.kt` (`234 B`)
   - `app/src/main/java/com/openfolio/core/data/local/dao/CorporateActionDao.kt` (`1,020 B`) & `entity/CorporateActionEntity.kt` (`778 B`)
   - `app/src/main/java/com/openfolio/core/data/local/dao/ValuationDao.kt` (`1,122 B`) & `entity/ValuationEntity.kt` (`780 B`)
   - **Evidence:** Declared only in `OpenFolioDatabase.kt` and `DatabaseModule.kt`; zero repositories, use cases, ViewModels, or tests inject or call them.
   - **Fix:** Once explicit Room migrations are introduced (`P0-1`), remove these 3 DAOs/entities from `DatabaseModule` and `OpenFolioDatabase` (via a `DROP TABLE` migration) and delete the 6 files.

---

### P3-2. Dead Symbols, Legacy Overloads, Redundant Keep Rules & Imports in Active Files
1. **File & Line Reference + Evidence:**
   - `app/src/main/java/com/openfolio/core/ui/components/ManualTransactionDialog.kt:960-999`: `ManualTransactionDialog` composable has `0` callers (`ManualEntryBottomSheet` is used instead).
   - `app/src/main/java/com/openfolio/feature/screens/HomeScreen.kt:125-149`: 10-parameter `HomeScreen(..., onImport: (Uri) -> Unit, ...)` legacy overload has `0` external callers (`OpenFolioApp.kt:213` calls the ViewModel overload).
   - `app/src/main/java/com/openfolio/feature/home/HomeUiState.kt:63-73`: `RecentActivity` and `ActivityIcon` have `0` references anywhere.
   - `app/src/main/java/com/openfolio/feature/home/HomeViewModel.kt:59,66`: `private var currentDashboardData: DashboardData? = null` is written in `combine` and never read.
   - `app/src/main/java/com/openfolio/feature/screens/ReviewScreen.kt:1367-1379`: `private fun DraftTransactionRow(draft: DraftTransactionEntity, ...)` overload has `0` callers.
   - `app/src/main/java/com/openfolio/core/ui/components/MoneyText.kt:6,11,43,56-62`: unused `remember` and `NumberFormat` imports, unused `formatter: NumberFormat? = null` parameter, and unused `createIndianNumberFormatter()`.
   - `app/src/main/java/com/openfolio/core/data/local/Converters.kt:65-68`: `enumToString` / `stringToEnum` helpers unused in `main` (only tested directly).
   - `app/src/main/java/com/openfolio/core/data/repository/DocumentRepositoryImpl.kt:80-81,1272-1294,2509-2563`: `processDocumentWithMockAi` / `mockAiDraftTransactions` unused in `main` + stale `kotlinx.serialization.MissingFieldException` imports/catch blocks (`lines 843,850`).
   - `app/proguard-rules.pro:25-32,36`: unused `kotlinx.serialization` keep block and stale nonexistent rule `-keep class com.openfolio.core.data.local.model.** { *; }`.
2. **Impact / Fix:** Safely delete these unreferenced wrappers, parameters, imports, and stale ProGuard lines to cut maintenance overhead (inline test-only mock helpers into test doubles if needed).

---

### P3-3. Unused Declared Gradle Dependencies (`app/build.gradle.kts` & `gradle/libs.versions.toml`)
1. **File & Line Reference + Evidence:**
   - `app/build.gradle.kts:123` (`libs.kotlinx.serialization.json` `1.6.3` in `libs.versions.toml:23,57`): Zero Kotlinx Serialization plugin or `@Serializable` models exist; project uses `org.json.JSONObject` and `JsonStreamWriter`. Only dead catch clauses for `MissingFieldException` remain in `DocumentRepositoryImpl.kt:80-81,843,850` (+ 1 mock exception in `DocumentRepositoryImplTest.kt:524,545`).
   - `app/build.gradle.kts:116` (`libs.androidx.sqlite.ktx` `2.4.0` in `libs.versions.toml:11,44`): Zero `androidx.sqlite.ktx` extension imports (`transaction {}`, etc.); Room and SQLCipher supply `androidx.sqlite.db` framework types directly.
   - `app/build.gradle.kts:108,139` (`androidx.compose.ui.tooling.preview` & `debugImplementation(ui.tooling)`): Zero `@Preview` functions exist in `app/src`.
   - `app/build.gradle.kts:135-138,140` (`androidTestImplementation` suite): `app/src/androidTest` does not exist.
2. **Impact / Fix:** Remove `kotlinx-serialization-json` (and the dead `MissingFieldException` reference), remove direct `androidx.sqlite.ktx` and unused `ui-tooling-preview`/`ui-tooling` declarations from `app/build.gradle.kts` & `libs.versions.toml`, and either populate `app/src/androidTest` (`P2-5`) or prune unused test runner dependencies.

---

### P3-4. Duplicate Assets, Orphaned Root Artifacts & Resource Cleanup (5 Files + 1 Empty Dir — 1,976,427 Bytes)
1. **File & Line Reference + Evidence:**
   - `others/icon.png` (`97,590 B`): Byte-for-byte duplicate (SHA-256 `82E48B0E5D16D9BECEF7B315DF00CC65B3EDDEB5B86EA4DC774825D051CE87A4`) of `app/src/main/res/drawable-nodpi/ic_openfolio_logo.png`.
   - `project_export.txt` (`1,822,680 B`) & `export_project.py` (`15,063 B`): Leftover repo-dump script and 1.82 MB output dump in workspace root (even though `project_export.txt` is in `.gitignore`).
   - `audit.md` (`40,908 B`): Prior static audit markdown document superseded by this audit.
   - `.kotlin/errors/errors-1794844080844.log` (`186 B`) & empty directory `.kotlin/sessions/`: Local Kotlin compiler cache residue.
   - `app/src/main/res/mipmap-anydpi-v26/`: Flagged by Android Lint `[ObsoleteSdkInt]` because `minSdk = 26` already covers Android 8.0 adaptive icons; fold `ic_launcher.xml` and `ic_launcher_round.xml` into `res/mipmap-anydpi/` and add `<monochrome>` layers (`[MonochromeLauncherIcon]`).
   - **Safe Retention Note:** `app/src/main/assets/fonts/Inter-OFL.txt` (`4,377 B`) is unreferenced by Kotlin/XML because it is the required SIL Open Font License notice for `res/font/inter_*.ttf` — **keep**.
2. **Concrete Fix (Deletion Commands for Workspace Cleanup):**
   ```powershell
   Remove-Item others\icon.png, project_export.txt, export_project.py, audit.md
   Remove-Item others -Force  # if empty
   Remove-Item .kotlin\errors\errors-1794844080844.log
   Remove-Item .kotlin\sessions -Force
   ```

---

## Phase 6 Automated Static Verification Summary

| Command | Outcome | Key Details |
| :--- | :--- | :--- |
| `.\gradlew.bat lintDebug testDebugUnitTest --continue --no-daemon` | `BUILD SUCCESSFUL` (`11m 54s`) | Updated `app/build/reports/lint-results-debug.{txt,xml,html}`; **0 errors, 68 warnings**; **0 `UnusedResources`**, **0 `UnusedIds`**. |
| `.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon` | `BUILD SUCCESSFUL` (`20m 49s`) | Parsed all 37 JUnit XML suites: **277 tests, 0 failures, 0 errors, 0 JUnit skips** (`2` methods disabled via `// @Test` + `@Ignore` in `DocumentRepositoryImplTest.kt:666,743`). |
| `.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath --no-daemon` | `BUILD SUCCESSFUL` | Confirmed `opencsv:5.9` pulls `commons-beanutils:1.9.4` + `commons-collections:3.2.2`. |
| `.\gradlew.bat :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency bcpkix-jdk15to18` | `BUILD SUCCESSFUL` | Confirmed `com.tom-roush:pdfbox-android:2.0.27.0` pulls `org.bouncycastle:bcpkix-jdk15to18:1.72` (flagged by `[TrustAllX509TrustManager]`). |
| `.\gradlew.bat :app:dependencyInsight --configuration debugRuntimeClasspath --dependency sqlite-ktx` | `BUILD SUCCESSFUL` | Confirmed direct declaration in `app/build.gradle.kts` (`debugRuntimeClasspath`) is redundant with Room/SQLite transitive constraint. |
