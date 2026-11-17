# Comprehensive Architecture, Security & Production-Readiness Audit
**Target Application:** OpenFolio (`com.openfolio`)  
**Auditor Roles:** Principal Android Engineer, Security Auditor & Product QA Architect  
**Evaluation Standard:** Clean Architecture, Modern Android Development (Jetpack Compose M3, Coroutines/Flow, Room + SQLCipher, Hilt), Zero-Trust Mobile Security & Google Play Guidelines.

---

## Executive Architectural Summary

OpenFolio presents a modern Jetpack Compose + Clean Architecture foundation (Room with SQLCipher, Kotlin Coroutines, MVVM, and Material 3). However, a deep forensic inspection across the codebase reveals **severe production-blocking defects**:
1. **Critical Data Destruction & Loss:** Room is configured with `.fallbackToDestructiveMigration()` in `DatabaseModule`, but migrations are **never registered** via `.addMigrations()`, causing automatic database wipes on schema bumps.
2. **Financial Logic Exploit:** In `DocumentRepositoryImpl.saveDraftTransactions()`, selling an asset or receiving dividends **adds** quantity and invested capital to the asset rather than deducting or calculating realized P&L.
3. **Severe Resource & File Descriptor Leaks:** `PdfPreview` in `ImagePdfPreview.kt` fails to close `PdfRenderer` and `ParcelFileDescriptor` due to a closure capture bug in `DisposableEffect`, leading to process termination via Linux `EMFILE` exhaustion.
4. **Lifecycle & State Hoisting Anti-Patterns:** All screen ViewModels are created at the `MainActivity` scope and collected in the root `OpenFolioApp` composable, causing global recomposition storms and state pollution between routes.
5. **Cleartext PII Leakage:** Database JSON exports dump the entire encrypted database in cleartext into the app's cache directory without post-share sanitization.

---

# Module 1: Core Database Engine & Storage Layer
**Files Inspected:**
* `core/data/local/OpenFolioDatabase.kt`
* `core/di/DatabaseModule.kt`
* `core/data/local/DatabasePassphraseProvider.kt`
* `core/data/repository/SettingsRepository.kt`
* `core/data/local/export/DatabaseExporterImpl.kt`
* `core/data/local/Converters.kt`

---

### 1. Feature & Flow Mapping (Current State)
* **Happy Path (Current Flow):**
  1. `DatabasePassphraseProvider` retrieves/generates an AES-256 GCM key in the Android KeyStore, encrypts a random 32-byte database passphrase, and persists it in `SharedPreferences`.
  2. `DatabaseModule` loads SQLCipher native libraries via `SQLiteDatabase.loadLibs(context)` and provides `OpenFolioDatabase` using `SupportFactory(passphraseProvider.getPassphrase())`.
  3. All data queries execute via Room DAOs into SQLCipher v4 encrypted storage (`openfolio.db`).
* **State Management:**
  Room DAOs emit reactive `Flow` instances for database queries. However, migrations are decoupled from the DI provider, and database access runs through singleton-scoped DAOs.

---

### 2. Unhandled Flows & Edge Cases (The Gaps)
* **Lifecycle & Configuration Changes:**
  `DatabaseExporterImpl` and `SettingsRepository` initialize during dependency graph resolution. If the app is launched from the background or process death, `SettingsRepository.init` triggers Keystore decryption on the calling thread.
* **Corrupted Key Recovery:**
  In `DatabasePassphraseProvider.kt` (lines 35–37), if an app crash, backup restore, or manufacturer backup tool restores the IV but not the ciphertext (or vice versa), `check(storedIv == null && storedCiphertext == null)` throws `IllegalStateException`. This results in an **unrecoverable crash loop** on startup.
* **Corrupted Type Converter Floats:**
  `Converters.kt` deserializes `BigDecimal` using `BigDecimal(it)`. If a database column contains empty string `""` or invalid characters from an unvalidated CSV import, `BigDecimal` constructor throws `NumberFormatException`, crashing every UI screen observing that DAO.

---

### 3. Danger Flows & Critical Vulnerabilities (High Risk)

#### [VULN-01] Missing Migration Registration with Active Destructive Migration
* **Location:** `app/src/main/java/com/openfolio/core/di/DatabaseModule.kt:38-45`
* **Finding:** While `OpenFolioDatabase.kt` defines companion objects for `MIGRATION_1_2` through `MIGRATION_9_10`, `DatabaseModule.kt` invokes `.fallbackToDestructiveMigration()` without calling `.addMigrations(...)`:
```kotlin
// DatabaseModule.kt
return Room.databaseBuilder(context, OpenFolioDatabase::class.java, "openfolio.db")
    .fallbackToDestructiveMigration()
    .openHelperFactory(SupportFactory(passphraseProvider.getPassphrase()))
    .build()
```
* **Impact:** Every single user who updates the app when the database schema changes will have their **entire encrypted portfolio database completely wiped**. Furthermore, `MIGRATION_8_9` and `MIGRATION_10_11` are missing entirely from `OpenFolioDatabase.kt`.

#### [VULN-02] Main-Thread Blocking in Keystore & EncryptedSharedPreferences Initialization
* **Location:** `app/src/main/java/com/openfolio/core/data/repository/SettingsRepository.kt:19-36`
* **Finding:**
```kotlin
// SettingsRepository.kt
private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
private val prefs by lazy { EncryptedSharedPreferences.create(...) }
init {
    _geminiApiKey.value = getGeminiApiKey() // Main thread I/O & KeyStore crypto!
}
```
* **Impact:** Android Keystore generation and SIV/GCM cipher initialization involve synchronous binder IPC to the hardware Secure Element/TEE. Executing this inside `init` on the main thread during app launch causes severe frame drops and ANRs (Application Not Responding) on lower-end devices.

#### [VULN-03] Insecure Cleartext Database Export to Cache Without Lifecycle Cleanup
* **Location:** `app/src/main/java/com/openfolio/feature/settings/SettingsViewModel.kt:148-169`
* **Finding:** `exportAndShareDatabase()` decrypts the entire SQLCipher-protected database (including all accounts, assets, net worth, and document metadata) and streams it into `context.cacheDir/exports/<filename>.json`.
* **Impact:** The decrypted JSON file is left permanently on disk. Any third-party backup tool, file manager, or debug bridge can inspect user wealth data in plaintext, bypassing the hardware-backed SQLCipher encryption layer entirely.

---

### 4. Performance, Accessibility & Play Store Readiness
* **Compliance & Deprecations:** `MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)` is deprecated in Android Jetpack Security. Must use `MasterKey.Builder(context)`.
* **Schema Verification:** `exportSchema = false` is configured on `@Database`, preventing Room from exporting schema JSON files to version control, making automated migration tests impossible.

---

### 5. Actionable Refactoring Plan (Module 1)

#### Issue 1.1: Automatic Data Wipe on Database Update
* **Severity:** `[CRITICAL - Blocker]`
* **Root Cause:** Room migrations are declared in `OpenFolioDatabase` but omitted from `Room.databaseBuilder()`, while `.fallbackToDestructiveMigration()` is active.
* **Production-Grade Fix:**
Register all migrations, complete missing steps (8->9, 10->11), enable schema exports, and remove `.fallbackToDestructiveMigration()` in production builds.

```kotlin
// core/di/DatabaseModule.kt
@Provides
@Singleton
fun provideDatabase(
    @ApplicationContext context: Context,
    passphraseProvider: DatabasePassphraseProvider,
): OpenFolioDatabase {
    SQLiteDatabase.loadLibs(context)
    return Room.databaseBuilder(
        context,
        OpenFolioDatabase::class.java,
        "openfolio.db",
    )
        .openHelperFactory(SupportFactory(passphraseProvider.getPassphrase()))
        .addMigrations(
            OpenFolioDatabase.MIGRATION_1_2,
            OpenFolioDatabase.MIGRATION_2_3,
            OpenFolioDatabase.MIGRATION_3_4,
            OpenFolioDatabase.MIGRATION_4_5,
            OpenFolioDatabase.MIGRATION_5_6,
            OpenFolioDatabase.MIGRATION_6_7,
            OpenFolioDatabase.MIGRATION_7_8,
            OpenFolioDatabase.MIGRATION_8_9,
            OpenFolioDatabase.MIGRATION_9_10,
            OpenFolioDatabase.MIGRATION_10_11,
        )
        // Guard destructive migration strictly to dev/debug builds
        .apply {
            if (BuildConfig.DEBUG) {
                fallbackToDestructiveMigrationOnDowngrade()
            }
        }
        .build()
}
```

#### Issue 1.2: Main-Thread KeyStore I/O and Deprecated MasterKeys
* **Severity:** `[HIGH - Major Risk]`
* **Root Cause:** Direct synchronous KeyStore invocation and initialization inside ViewModel/Repository constructors.
* **Production-Grade Fix:**
Refactor to modern `MasterKey.Builder`, initialize lazily on `Dispatchers.IO`, and provide fallback recovery for corrupted keystore states.

```kotlin
// core/data/repository/SettingsRepository.kt
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                "openfolio_secure_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e("SettingsRepository", "KeyStore corrupted. Resetting preferences.", e)
            context.deleteSharedPreferences("openfolio_secure_settings")
            EncryptedSharedPreferences.create(
                context,
                "openfolio_secure_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    private val _geminiApiKey = MutableStateFlow<String?>(null)
    val geminiApiKeyFlow: StateFlow<String> = _geminiApiKey
        .filterNotNull()
        .stateIn(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            started = SharingStarted.Eagerly,
            initialValue = "",
        )

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (_geminiApiKey.value == null) {
            _geminiApiKey.value = prefs.getString(KEY_GEMINI_API_KEY, "").orEmpty()
        }
    }
}
```

---

# Module 2: Ingestion, Parsers & AI Pipeline
**Files Inspected:**
* `core/data/repository/DocumentRepositoryImpl.kt`
* `core/data/remote/ai/GeminiAiProvider.kt`
* `core/utils/LocalDocumentTextExtractor.kt`
* `core/utils/StructuredFileParser.kt`
* `core/utils/XlsxExtractor.kt`

---

### 1. Feature & Flow Mapping (Current State)
* **Happy Path (Current Flow):**
  1. `DocumentRepositoryImpl.ingestFile(uri)` opens `ContentResolver.openInputStream(uri)`, reads SHA-256, verifies duplication, and copies the stream to private storage `files/documents/<uuid>.<ext>`.
  2. If CSV/XLSX: status becomes `READY_FOR_MAPPING`. If a known mapping matches, drafts are auto-extracted.
  3. If PDF/Image: an in-process coroutine extracts searchable text via PDFBox and ML Kit Text Recognition, transitioning status from `PENDING_OCR` to `OCR_COMPLETE`.
  4. User triggers `processDocumentWithAi()`, which chunks extracted text into 3,000-character segments, dispatches to Gemini 2.5 Flash, parses JSON drafts, and saves them to `draft_transactions`.
* **State Management:**
  State is coordinated via Room DB updates (`DocumentStatus`: `PENDING_OCR` -> `OCR_COMPLETE` -> `AI_COMPLETE` -> `COMPLETED`).

---

### 2. Unhandled Flows & Edge Cases (The Gaps)
* **OS Process Death During Ingestion:**
  OCR is launched via `applicationScope.launch` in `ingestFile` (lines 475–495). If Android terminates the process while the user backgrounds the app, the in-process job dies. When reopened, the document remains permanently locked in `PENDING_OCR`. There is **no WorkManager integration or startup recovery worker**.
* **Password-Protected Statement Crash:**
  Indian financial institutions (Zerodha, CAMS, KFintech, HDFC Bank, ICICI) issue password-protected PDFs (PAN/DOB). `PDDocument.load(file)` throws `InvalidPasswordException`, resulting in silent failure to `OCR_FAILED` with **no UI affordance to enter a decryption password**.
* **Stream Re-open Failures on Scoped Storage Content Providers:**
  Lines 418 and 431 open `resolver.openInputStream(uri)` twice in succession. Virtual storage providers (Google Drive, Nextcloud, OneDrive) often invalidate or close the underlying stream descriptor after the first consumption, causing the second call to throw `IOException`.
* **Rogue Large Cell / Billion-Row DoS in XLSX:**
  `XlsxExtractor.kt` (lines 322–333) processes row indexes:
  ```kotlin
  while (allRows.size < targetRow && allRows.size < rowLimit) {
      allRows.add(emptyList())
  }
  ```
  If an Excel file contains a formatted cell at row `1,048,576`, this loop allocates over 1 million empty lists, triggering an instant `OutOfMemoryError`.

---

### 3. Danger Flows & Critical Vulnerabilities (High Risk)

#### [VULN-04] LLM Output Parsing Fragility & Failure on Explanatory Preambles
* **Location:** `app/src/main/java/com/openfolio/core/data/remote/ai/GeminiAiProvider.kt:58-65`
* **Finding:**
```kotlin
val cleaned = responseText
    .removePrefix("```json")
    .removePrefix("```JSON")
    .removePrefix("```")
    .removeSuffix("```")
    .trim()
val jsonArray = JSONArray(cleaned)
```
* **Impact:** If Gemini includes any text prior to the markdown block (e.g. *"Here are the extracted transactions:\n```json..."*), `removePrefix` fails completely. `JSONArray(cleaned)` throws `JSONException`, causing extraction to crash and fail for valid LLM responses.

#### [VULN-05] Financial PII Transmission to Cloud Without Data Sanitization
* **Location:** `app/src/main/java/com/openfolio/core/data/remote/ai/GeminiAiProvider.kt:44`
* **Finding:** The entire `rawText` from bank/brokerage statements is transmitted verbatim to the Gemini API endpoint.
* **Impact:** User full names, residential addresses, permanent account numbers (PAN), bank account numbers, and IFSC codes are sent across network boundaries without local redaction or explicit user consent disclosures, violating privacy compliance and data minimization standards.

---

### 4. Performance, Accessibility & Play Store Readiness
* **Thread Starvation:** In `LocalDocumentTextExtractor.kt` (lines 34 & 60), `Tasks.await(recognizer.process(...))` is called synchronously on `Dispatchers.IO`. This blocks coroutine threads instead of suspending them using `await()` from `kotlinx-coroutines-play-services`.
* **Bitmap Memory Spikes:** Rendering PDF pages at 150 DPI without memory pooling allocates large uncompressed ARGB_8888 Bitmaps (8–12 MB each) on the managed heap, risking OOM crashes during batch scans.

---

### 5. Actionable Refactoring Plan (Module 2)

#### Issue 2.1: Robust JSON Sanitizer for LLM Extraction
* **Severity:** `[HIGH - Major Risk]`
* **Root Cause:** Brittle string prefix matching fails when models produce conversational wrapper text.
* **Production-Grade Fix:**
Extract JSON arrays using regex boundary matching, and enforce sanitized PII masking before dispatching.

```kotlin
// core/data/remote/ai/GeminiAiProvider.kt
internal fun extractJsonArrayString(rawResponse: String): String {
    val trimmed = rawResponse.trim()
    val startIndex = trimmed.indexOf('[')
    val endIndex = trimmed.lastIndexOf(']')
    if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
        return trimmed.substring(startIndex, endIndex + 1)
    }
    throw JSONException("No valid JSON array found in LLM response: $rawResponse")
}

override suspend fun extractTransactions(
    rawText: String,
    apiKey: String,
    documentId: String,
): Result<List<DraftTransactionEntity>> = withContext(Dispatchers.IO) {
    runCatching {
        require(apiKey.isNotBlank()) { "API key must not be blank." }
        require(rawText.isNotBlank()) { "Raw text must not be blank." }

        // Sanitize obvious PII before cloud dispatch
        val sanitizedText = sanitizePii(rawText)

        val model = GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = apiKey,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
            },
            systemInstruction = content { text(SYSTEM_PROMPT) },
        )

        val response = model.generateContent(sanitizedText)
        val responseText = response.text.orEmpty()
        val jsonCleaned = extractJsonArrayString(responseText)
        parseJsonResponse(jsonCleaned, documentId)
    }
}

private fun sanitizePii(input: String): String {
    // Redact 10-digit Indian PAN: [A-Z]{5}[0-9]{4}[A-Z]
    val panRegex = "\\b[A-Z]{5}[0-9]{4}[A-Z]\\b".toRegex()
    // Redact 12-digit Aadhaar / Account numbers
    val accountRegex = "\\b\\d{9,18}\\b".toRegex()
    return input
        .replace(panRegex, "[REDACTED_PAN]")
        .replace(accountRegex, "[REDACTED_ACCOUNT]")
}
```

#### Issue 2.2: Single-Pass Ingestion & Hash Generation
* **Severity:** `[MEDIUM - UX/Tech Debt]`
* **Root Cause:** Opening `contentResolver.openInputStream(uri)` twice risks `IOException` on virtual and cloud providers.
* **Production-Grade Fix:**
Stream once to a temporary file while updating a `DigestInputStream`, then atomically move to target path.

```kotlin
// core/data/repository/DocumentRepositoryImpl.kt
val tempFile = File(context.cacheDir, "ingest_${UUID.randomUUID()}.tmp")
val digest = MessageDigest.getInstance("SHA-256")

val copiedBytes = resolver.openInputStream(uri)?.use { inputStream ->
    DigestInputStream(inputStream, digest).use { dis ->
        FileOutputStream(tempFile).use { fos ->
            dis.copyTo(fos)
        }
    }
} ?: throw IOException("Unable to open source document stream.")

val fileHash = digest.digest().toLowerHex()

if (documentDao.getDocumentByHash(fileHash) != null) {
    tempFile.delete()
    throw DuplicateDocumentException(fileHash)
}

val finalFile = File(documentsDirectory, "${UUID.randomUUID()}.$extension")
if (!tempFile.renameTo(finalFile)) {
    tempFile.copyTo(finalFile, overwrite = true)
    tempFile.delete()
}
```

---

# Module 3: Wealth Domain & Financial Calculations Engine
**Files Inspected:**
* `core/data/repository/DocumentRepositoryImpl.kt` (`saveDraftTransactions`)
* `core/domain/usecase/GetDashboardDataUseCase.kt`
* `core/data/local/dao/TransactionDao.kt`
* `core/data/local/dao/AssetDao.kt`
* `core/data/local/dao/LotDao.kt`
* `core/ui/components/MoneyText.kt`

---

### 1. Feature & Flow Mapping (Current State)
* **Happy Path (Current Flow):**
  1. User reviews draft transactions extracted from a document.
  2. Tapping "Save Confirmed" invokes `DocumentRepository.saveDraftTransactions(documentId)`.
  3. Inside a Room transaction, drafts are checked against existing SHA-256 fingerprints.
  4. Non-duplicate drafts are written to the `txn` table, asset records are created/updated in the `asset` table, drafts are purged, and the document is marked `COMPLETED`.
* **State Management:**
  Transactions and assets are observed via reactive Room flows in `GetDashboardDataUseCase`.

---

### 2. Unhandled Flows & Edge Cases (The Gaps)
* **Duplicate Fingerprint Collision on Same-Day Legitimate Trades:**
  `createTransactionFingerprint` generates a hash based on `(assetId, type, quantity, netAmount, date)`. If an active investor executes two identical trades on the same day (e.g., buying 10 shares of TCS at 10:00 AM and 10 shares of TCS at 2:00 PM at identical market limit prices), the second trade is **discarded as a duplicate**!
* **Fingerprint Inconsistency Due to Decimal Formatting:**
  Hashed strings use raw inputs: `"10"` vs `"10.0"` or `"₹ 1,000"` vs `"1000.00"`. A transaction parsed via AI with `"10.00"` will not match a manually entered draft of `"10"`, defeating deduplication.
* **Paise & Fractional Cent Truncation in Financial Formatter:**
  `MoneyText.kt` (lines 53–58) hardcodes `minimumFractionDigits = 0` and `maximumFractionDigits = 0` with `HALF_UP`. Fractional mutual fund units (e.g. 14.542 units) and dividend payments with paise (₹ 12.50) are forcibly rounded to zero decimals, leading to permanent balance sheet desynchronization.

---

### 3. Danger Flows & Critical Vulnerabilities (High Risk)

#### [VULN-06] Inverted Sell/Dividend Accounting Corrupts Portfolio Value
* **Location:** `app/src/main/java/com/openfolio/core/data/repository/DocumentRepositoryImpl.kt:225-258`
* **Finding:**
```kotlin
// DocumentRepositoryImpl.kt
val existingAsset = database.assetDao().getById(assetId)
val qty = quantity
val invested = netAmount

if (existingAsset == null) {
    ...
} else {
    // CRITICAL FLAW: Blindly adds for SELL, DIVIDEND, FEE, TAX!
    val newQty = existingAsset.totalQuantity.add(qty)
    val newInvested = existingAsset.totalInvested.add(invested)
    val newAvgCost = if (newQty > BigDecimal.ZERO) newInvested.divide(newQty, 4, RoundingMode.HALF_UP) else BigDecimal.ZERO
    database.assetDao().updateAggregates(...)
}
```
* **Impact:** Selling 100 shares of an asset **increases** the tracked share count by 100 rather than decreasing it. A dividend received is added as invested principal. This completely invalidates all portfolio tracking, cost basis, and net worth calculations.

#### [VULN-07] Floating-Point Inaccuracies in SQLite Queries Violating Money Rules
* **Location:** `app/src/main/java/com/openfolio/core/data/local/dao/TransactionDao.kt:44-48`
* **Finding:**
```kotlin
@Query("SELECT SUM(CAST(grossAmount AS REAL)) FROM txn WHERE type IN ('BUY', 'TRANSFER_IN', 'INTEREST', 'DIVIDEND')")
fun getTotalAssetsFlow(): Flow<Double?>
```
* **Impact:** `AGENTS.md` strictly mandates: *"Never use Double for money. Use BigDecimal or Long (minor units)."* Casting string monetary amounts to IEEE 754 `REAL` introduces binary floating-point representation errors (e.g., `0.1 + 0.2 = 0.30000000000000004`).
* **Accounting Misclassification:** This query misclassifies fees and taxes as "Total Liabilities". Fees paid on past trades are historical income statement expenses, not balance sheet liabilities.

#### [VULN-08] Zombie `LotDao` and Missing FIFO Cost Basis Engine
* **Location:** `core/data/local/dao/LotDao.kt`
* **Finding:** `LotEntity` and `LotDao` exist in Room, but are never injected, referenced, or updated in any business logic.
* **Impact:** FIFO cost-basis tracking—an explicit core architecture requirement—is completely absent. Average cost calculations cannot handle partial sales, resulting in incorrect capital gains computations.

---

### 4. Performance, Accessibility & Play Store Readiness
* **Negative Sign Formatting:** `MoneyText.kt` (lines 48–49) formats negative numbers as `"₹ -$groupedAmount"` instead of `"-₹$groupedAmount"` or `"(₹$groupedAmount)"`.
* **Database Aggregates on UI Thread:** `updateAggregates` executes iteratively row-by-row in a Kotlin loop rather than leveraging batch updates or a single SQL statement.

---

### 5. Actionable Refactoring Plan (Module 3)

#### Issue 3.1: Transaction Aggregation & Accounting Engine Fix
* **Severity:** `[CRITICAL - Blocker]`
* **Root Cause:** Failure to branch on `TransactionType` when updating asset position quantities and cost bases.
* **Production-Grade Fix:**
Implement position accounting that properly discriminates buys, sells, and dividends, and integrates FIFO lot deduction.

```kotlin
// core/data/repository/DocumentRepositoryImpl.kt
when (TransactionType.valueOf(draft.transactionType)) {
    TransactionType.BUY, TransactionType.TRANSFER_IN -> {
        val newQty = existingAsset.totalQuantity + quantity
        val newInvested = existingAsset.totalInvested + netAmount
        val newAvgCost = if (newQty > BigDecimal.ZERO) {
            newInvested.divide(newQty, 4, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        database.assetDao().updateAggregates(
            assetId = assetId,
            qty = newQty,
            invested = newInvested,
            avgCost = newAvgCost,
            realizedPnL = existingAsset.realizedPnL,
            timestamp = System.currentTimeMillis(),
        )
    }
    TransactionType.SELL, TransactionType.TRANSFER_OUT -> {
        require(existingAsset != null) { "Cannot sell asset without prior buy history: $assetId" }
        val newQty = (existingAsset.totalQuantity - quantity).coerceAtLeast(BigDecimal.ZERO)
        // Cost basis reduction proportional to sold quantity
        val costBasisPerUnit = existingAsset.averageCost
        val costOfSoldShares = costBasisPerUnit.multiply(quantity)
        val newInvested = (existingAsset.totalInvested - costOfSoldShares).coerceAtLeast(BigDecimal.ZERO)
        val realizedGain = netAmount - costOfSoldShares
        val newRealizedPnL = existingAsset.realizedPnL + realizedGain

        database.assetDao().updateAggregates(
            assetId = assetId,
            qty = newQty,
            invested = newInvested,
            avgCost = if (newQty > BigDecimal.ZERO) existingAsset.averageCost else BigDecimal.ZERO,
            realizedPnL = newRealizedPnL,
            timestamp = System.currentTimeMillis(),
        )
    }
    TransactionType.DIVIDEND, TransactionType.INTEREST -> {
        // Dividends do not alter share count; they add directly to realized PnL
        if (existingAsset != null) {
            database.assetDao().updateAggregates(
                assetId = assetId,
                qty = existingAsset.totalQuantity,
                invested = existingAsset.totalInvested,
                avgCost = existingAsset.averageCost,
                realizedPnL = existingAsset.realizedPnL + netAmount,
                timestamp = System.currentTimeMillis(),
            )
        }
    }
    TransactionType.FEE, TransactionType.TAX -> {
        // Expensed directly, does not change asset share quantity
    }
}
```

#### Issue 3.2: Deterministic Normalized Fingerprint Generation
* **Severity:** `[HIGH - Major Risk]`
* **Root Cause:** Hashing unnormalized strings and omitting transaction sequence uniqueness.
* **Production-Grade Fix:**
Normalize numbers via `BigDecimal.stripTrailingZeros().toPlainString()` and incorporate an optional order sequence/external reference.

```kotlin
// core/data/repository/DocumentRepositoryImpl.kt
internal fun createTransactionFingerprint(
    assetId: String,
    transactionType: String,
    quantity: BigDecimal,
    netAmount: BigDecimal,
    epochDay: Long,
    externalRef: String? = null,
): String {
    val normQty = quantity.stripTrailingZeros().toPlainString()
    val normAmount = netAmount.stripTrailingZeros().toPlainString()
    val identifier = listOfNotNull(
        assetId.trim().lowercase(),
        transactionType.trim().uppercase(),
        normQty,
        normAmount,
        epochDay.toString(),
        externalRef?.trim()?.takeIf(String::isNotEmpty),
    ).joinToString("|")

    return MessageDigest.getInstance("SHA-256")
        .digest(identifier.toByteArray(Charsets.UTF_8))
        .toLowerHex()
}
```

---

# Module 4: Presentation, Navigation & Jetpack Compose UI
**Files Inspected:**
* `MainActivity.kt`
* `feature/navigation/OpenFolioApp.kt`
* `feature/screens/HomeScreen.kt` / `feature/home/HomeViewModel.kt`
* `feature/screens/ReviewScreen.kt` / `feature/review/ReviewViewModel.kt`
* `feature/screens/SettingsScreen.kt` / `feature/settings/SettingsViewModel.kt`
* `feature/review/ImagePdfPreview.kt`
* `feature/review/TabularPreview.kt`

---

### 1. Feature & Flow Mapping (Current State)
* **Happy Path (Current Flow):**
  1. `MainActivity` instantiates all ViewModels via `by viewModels()` and passes them into `OpenFolioApp`.
  2. `OpenFolioApp` constructs a bottom navigation `Scaffold` and `NavHost` switching between Home, Assets, Activity, Inbox, Review, and Settings.
  3. `HomeScreen` displays net worth, allocations, and import FAB.
  4. `ReviewScreen` provides a split view: top 40% previews the raw document (PDF, Image, or CSV/XLSX table), and bottom 60% manages mapping and transaction review.
* **State Management:**
  State is exposed as `StateFlow` and observed in composables via `collectAsStateWithLifecycle()`.

---

### 2. Unhandled Flows & Edge Cases (The Gaps)
* **Screen Rotation Destroys In-Progress Column Mapping:**
  In `ColumnMappingForm` (`ReviewScreen.kt:429–444`), all form state (`brokerName`, `assetColumn`, `priceColumn`, etc.) is declared using `remember { mutableStateOf("") }`. Any orientation change, split-screen resize, or theme change **wipes all user inputs immediately**.
* **Double Pop on Config Change After Save:**
  In `ReviewScreen.kt` (lines 100–120):
  ```kotlin
  LaunchedEffect(actionState) {
      if (actionState is ReviewActionState.Saved) {
          snackbarHostState.showSnackbar(...) // Suspends for several seconds!
          onSaveComplete() // Pops backstack
      }
  }
  ```
  Because `ReviewViewModel` is scoped to the `Activity`, `actionState` remains `Saved` after popping. If the user rotates the device or triggers a configuration change, `LaunchedEffect` executes again and calls `onSaveComplete()`, popping an **additional screen** off the backstack.
* **Zero Window Inset Handling (Edge-to-Edge Glitches):**
  `MainActivity` sets `WindowCompat.setDecorFitsSystemWindows(window, false)`. However, `OpenFolioApp` explicitly sets `contentWindowInsets = WindowInsets(0, 0, 0, 0)` on the root `Scaffold`, causing bottom navigation items and floating action buttons to overlap with Android 15's system gesture bar and 3-button navigation bars.

---

### 3. Danger Flows & Critical Vulnerabilities (High Risk)

#### [VULN-09] Native File Descriptor & Bitmap Leak in `PdfPreview`
* **Location:** `app/src/main/java/com/openfolio/feature/review/ImagePdfPreview.kt:140-153`
* **Finding:**
```kotlin
var bitmap by remember(filePath) { mutableStateOf<Bitmap?>(null) }
var pdfRenderer by remember(filePath) { mutableStateOf<PdfRenderer?>(null) }
var parcelFd by remember(filePath) { mutableStateOf<ParcelFileDescriptor?>(null) }

DisposableEffect(filePath) {
    onDispose {
        bitmap?.let { if (!it.isRecycled) it.recycle() }
        pdfRenderer?.close()
        parcelFd?.close()
    }
}
```
* **Impact:** `DisposableEffect(filePath)`'s `onDispose` block executes within the initial composition closure when `bitmap`, `pdfRenderer`, and `parcelFd` are **null**. When the background `LaunchedEffect` loads the PDF and populates those states, `DisposableEffect(filePath)` is **not re-executed** because its key (`filePath`) has not changed.
* When the user navigates away, `onDispose` runs against the captured `null` references. The underlying Linux `ParcelFileDescriptor` and multi-megabyte `PdfRenderer` instances **leak completely**. Repeatedly reviewing PDFs exhausts the OS process file descriptor limit (`EMFILE`), resulting in a **native SIGABRT crash**.

#### [VULN-10] Global ViewModel Hoisting Recomposition Storm
* **Location:** `app/src/main/java/com/openfolio/MainActivity.kt:21-25` & `feature/navigation/OpenFolioApp.kt:49-56`
* **Finding:**
All four ViewModels are declared at `Activity` scope and their individual states are collected inside the top-level `OpenFolioApp` function:
```kotlin
val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
val inboxDocuments by inboxViewModel.documents.collectAsStateWithLifecycle()
val reviewUiState by reviewViewModel.uiState.collectAsStateWithLifecycle()
val reviewDrafts by reviewViewModel.drafts.collectAsStateWithLifecycle()
val structuredHeaders by reviewViewModel.structuredHeaders.collectAsStateWithLifecycle()
val reviewActionState by reviewViewModel.actionState.collectAsStateWithLifecycle()
```
* **Impact:** Any keystroke in the Review screen or background update in the Inbox triggers a recomposition of the **entire root app**, including the root `Scaffold`, top bars, and the entire `NavHost` tree. Furthermore, `ReviewViewModel` retains loaded document state in memory forever even after leaving the review flow.

---

### 4. Performance, Accessibility & Play Store Readiness
* **Infinite Constraint Crash Risk in `TabularPreview`:**
  `TabularPreview.kt` nests a vertical `LazyColumn` inside a horizontal scroll container `Modifier.horizontalScroll()`. In Jetpack Compose, this assigns infinite width constraints to items. With 40+ columns, Compose instantiates and renders all cell nodes simultaneously without horizontal view virtualization.
* **Missing A11y Content Descriptions:**
  In `HomeScreen.kt` (line 183), the FAB icon sets `contentDescription = null` even when no text label is read out by TalkBack during loading states.
* **Touch Target Violations:**
  Header row navigation buttons and table cells are smaller than the Material 3 minimum accessibility touch target (48x48 dp).

---

### 5. Actionable Refactoring Plan (Module 4)

#### Issue 4.1: Native Resource & File Descriptor Leak in `PdfPreview`
* **Severity:** `[CRITICAL - Blocker]`
* **Root Cause:** Capturing mutable state references inside `DisposableEffect` without state-backed reference tracking or `rememberUpdatedState`.
* **Production-Grade Fix:**
Decouple resource lifecycle into a dedicated holder or manage disposal via a lifecycle-aware controller.

```kotlin
// feature/review/ImagePdfPreview.kt
private class PdfResourceHolder(
    val bitmap: Bitmap,
    val renderer: PdfRenderer,
    val pfd: ParcelFileDescriptor,
) {
    fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
        try { renderer.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}

@Composable
fun PdfPreview(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    var resourceHolder by remember { mutableStateOf<PdfResourceHolder?>(null) }
    var isLoading by remember(filePath) { mutableStateOf(true) }
    var errorMessage by remember(filePath) { mutableStateOf<String?>(null) }

    DisposableEffect(filePath) {
        onDispose {
            resourceHolder?.close()
            resourceHolder = null
        }
    }

    LaunchedEffect(filePath) {
        isLoading = true
        errorMessage = null
        try {
            val holder = withContext(Dispatchers.IO) {
                val file = File(filePath)
                require(file.exists() && file.canRead()) { "File unreadable" }
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val page = renderer.openPage(0)
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                PdfResourceHolder(bitmap, renderer, pfd)
            }
            // Close any previous instance before reassigning
            resourceHolder?.close()
            resourceHolder = holder
        } catch (e: Exception) {
            errorMessage = "Preview unavailable"
        } finally {
            isLoading = false
        }
    }

    when {
        isLoading -> CircularProgressIndicator(color = colors.brand)
        errorMessage != null || resourceHolder == null -> Text(text = errorMessage ?: "Preview unavailable")
        else -> ZoomableBitmapPreview(bitmap = resourceHolder!!.bitmap, modifier = modifier)
    }
}
```

#### Issue 4.2: Scoping ViewModels to Navigation BackStack & SavedStateHandle
* **Severity:** `[HIGH - Major Risk]`
* **Root Cause:** Hoisting all ViewModels to `MainActivity` instead of scoping them to destination routes via `hiltViewModel()`.
* **Production-Grade Fix:**
Remove ViewModels from `MainActivity` parameters. Inject them locally inside each composable destination, and use `rememberSaveable` for user inputs.

```kotlin
// feature/navigation/OpenFolioApp.kt
@Composable
fun OpenFolioApp() {
    val navController = rememberNavController()
    val colors = LocalOpenFolioColors.current

    Scaffold(
        containerColor = colors.background,
        bottomBar = { /* Bottom Bar logic */ },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(AppDestination.Home.route) {
                val viewModel: HomeViewModel = hiltViewModel()
                HomeScreen(
                    viewModel = viewModel,
                    onImportSuccess = { navController.navigate(AppDestination.Inbox.route) },
                    onSettings = { navController.navigate(AppDestination.Settings.route) },
                )
            }
            composable(AppDestination.Inbox.route) {
                val viewModel: InboxViewModel = hiltViewModel()
                InboxScreen(
                    viewModel = viewModel,
                    onDocumentClick = { id -> navController.navigate("review/$id") },
                )
            }
            composable(
                route = "review/{documentId}",
                arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
            ) {
                val viewModel: ReviewViewModel = hiltViewModel()
                ReviewScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(AppDestination.Settings.route) {
                val viewModel: SettingsViewModel = hiltViewModel()
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
```

---

# Consolidated Risk Matrix & Remediation Roadmap

| ID | Issue Description | Layer | Severity | Status |
| :--- | :--- | :--- | :--- | :--- |
| **VULN-01** | Missing Room `.addMigrations(...)` with fallback to destructive migration | Data / DB | `[CRITICAL - Blocker]` | Action Required |
| **VULN-06** | Inverted accounting on `SELL` / `DIVIDEND` transactions | Domain / Repo | `[CRITICAL - Blocker]` | Action Required |
| **VULN-09** | Native `ParcelFileDescriptor` & Bitmap leak in `PdfPreview` | UI / Presentation | `[CRITICAL - Blocker]` | Action Required |
| **VULN-02** | Main-thread blocking Keystore I/O in `SettingsRepository` | Storage / Security| `[HIGH - Major Risk]` | Action Required |
| **VULN-03** | Unencrypted database JSON dump to cache without cleanup | Security / Privacy| `[HIGH - Major Risk]` | Action Required |
| **VULN-04** | LLM parsing failure on explanatory markdown wrappers | Remote / AI | `[HIGH - Major Risk]` | Action Required |
| **VULN-05** | Unsanitized transmission of user PAN/Account PII to cloud | Security / Privacy| `[HIGH - Major Risk]` | Action Required |
| **VULN-07** | Floating-point inaccuracies via SQLite `CAST(AS REAL)` | Data / DAO | `[HIGH - Major Risk]` | Action Required |
| **VULN-08** | Inactive `LotDao` and absence of FIFO cost-basis engine | Domain / Wealth | `[HIGH - Major Risk]` | Action Required |
| **VULN-10** | Activity-scoped ViewModel hoisting causing global recomposition | UI / Architecture | `[HIGH - Major Risk]` | Action Required |
| **GAP-01** | Screen rotation clears column mapping state (`remember` vs `rememberSaveable`) | UI / State | `[MEDIUM - UX/Tech Debt]`| Action Required |
| **GAP-02** | Process death halts in-flight OCR jobs without WorkManager | OS / Worker | `[MEDIUM - UX/Tech Debt]`| Action Required |
| **GAP-03** | Lack of password-protected PDF handling for bank/CAS statements | Ingestion / Flow | `[MEDIUM - UX/Tech Debt]`| Action Required |
| **GAP-04** | Virtual storage stream reopening failure | Ingestion / Storage| `[MEDIUM - UX/Tech Debt]`| Action Required |
| **POL-01** | Window inset collisions with system navigation gesture bar | UI / UX Polish | `[LOW - Polish]` | Action Required |
| **POL-02** | Negative currency sign formatted as `₹ -X` instead of `-₹X` | UI / Formatting | `[LOW - Polish]` | Action Required |

---

### Recommended Execution Plan
1. **Sprint 1 (Immediate Safety):** Resolve `VULN-01` (Migration registration), `VULN-06` (Sell/Dividend accounting), and `VULN-09` (Native PDF resource leak).
2. **Sprint 2 (Security & Architecture):** Refactor ViewModels to backstack scoping (`VULN-10`), sanitize cleartext exports (`VULN-03`), and implement single-pass stream ingestion (`GAP-04`).
3. **Sprint 3 (Domain Polish):** Implement true FIFO cost-basis utilizing `LotDao` (`VULN-08`), replace SQLite floating-point casts with minor-unit Long or integer Room queries (`VULN-07`), and add password unlock dialogs for financial PDFs (`GAP-03`).
