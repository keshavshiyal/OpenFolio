# OpenFolio: Project Roadmap & Build Plan (v1.0)

## 1. Vision & Core Philosophy
OpenFolio is a local-first, privacy-respecting, open-source personal wealth tracker. 
* **The Problem:** Existing apps are either ad-heavy, gamified, cloud-dependent, or lack transparency in how they calculate your wealth.
* **The Solution:** A tool that ingests messy financial documents (PDFs, CSVs, images) and turns them into a clean, auditable portfolio. 
* **Trust is the feature:** AI extracts data, but the user is always presented with the source document, the confidence score, and the math checksums. We never silently alter financial data. Every number is traceable to its source.

## 2. Tech Stack & Architecture
* **Language:** Kotlin
* **UI:** Jetpack Compose (100% — no XML layouts), Material 3.
* **Architecture:** MVVM + Clean Architecture (UI → ViewModel → UseCase → Repository → Data Source).
* **Dependency Injection:** Hilt.
* **Database:** Room + SQLCipher (local encryption at rest).
* **Async:** Kotlin Coroutines & StateFlow.
* **Navigation:** Jetpack Navigation Compose.
* **Document Processing:** `PdfBox-Android` (text extraction), `ML Kit` (OCR for images).
* **AI Integration:** OpenAI-compatible API (BYOK), Gemini, Anthropic, or local Ollama.
* **CI/CD:** GitHub Actions (Builds APK on push to `main`).

## 3. Development Workflow (AI Agent & CI)
1. **Agent writes code** based on the specs below.
2. **Agent pushes to `main`**.
3. **GitHub Action triggers**, compiles the code, and publishes a downloadable `app-debug.apk` artifact.
4. **Human Developer downloads the APK** to a physical device to review the UI/UX and provide feedback to the Agent.
5. **Zero Android Studio dependency** for the build process.

## 4. Design System Rules (The "Teal Anchor")
The AI must strictly adhere to these rules. No hardcoded colors outside of `core/ui/theme/`.

* **Brand Color:** Deep Teal `#0F6E5C` (Light) / `#2A9C86` (Dark).
* **Backgrounds:** Warm Off-white `#FAFAF7` (Light) / Deep Gray `#0E1113` (Dark).
* **Surfaces:** Pure White `#FFFFFF` (Light) / `#171B1E` (Dark).
* **Typography:** Inter font. All financial numbers MUST use tabular figures (`tnum` feature in Compose).
* **Gain/Loss Rule (1B):** Numbers are ALWAYS neutral text color (e.g., `#1A1D1F`). Only the tiny direction glyph (▲/▼) is colored Green (`#1F7A4D`) or Red (`#A93226`).
* **Icons (2B):** Monochrome in lists. Tinted using the chart palette only on the Asset Detail header.
* **Navigation (3A):** 5-Tab Bottom Navigation: `Home`, `Assets`, `Activity`, `Inbox`, `Settings`.
* **Empty States:** Every screen must have a specific, actionable empty state (e.g., "No assets yet. Import a statement or add one manually."). No generic "You're all set!" screens.
* **Accessibility:** All text/background pairs must pass WCAG AA (4.5:1). Font scaling up to 200% must not break layouts.

## 5. Data Model & Math Rules
* **Money:** Never use `Double`. Use `BigDecimal` for all financial calculations.
* **Assets:** Generic `Asset` table with an `assetClass` enum (STOCK, MF, FD, HOUSE, LAND, VEHICLE, CRYPTO, CASH, GOLD, BOND) and a JSON `detailsJson` string for class-specific fields.
* **Transactions:** Generic `Transaction` table. Tracks `tradeDate`, `settlementDate`, `quantity`, `grossAmount`, `fees`, `taxes`, `netAmount`, `currency`.
* **Provenance:** Every transaction must have a `fingerprint` (for deduplication), `documentId`, `sourcePage`, `confidenceScore`, and `status` (DRAFT, REVIEWED, CONFIRMED).
* **Idempotency:** Document imports must hash the file (SHA-256). If the file was already imported, abort silently. If the file has overlapping transactions, use the transaction fingerprint to skip duplicates.
* **Multi-Currency:** All foreign transactions must store the `fxRate` at the time of transaction and the `baseCurrencyAmount`.
* **Corporate Actions:** Must explicitly handle Splits, Bonuses, and Mergers, adjusting historical cost basis and lot quantities automatically.

---

## 6. Phased Build Plan

### Phase 1: The Skeleton (CI & Navigation)
**Goal:** Get the app compiling, themed, and navigating.
* [x] Initialize Gradle, Hilt, Room, and Compose. Setup `libs.versions.toml`.
* [x] Add `OpenFolioTheme.kt` with Teal Anchor tokens (Light/Dark).
* [x] Build 5-tab `BottomNav` with monochrome icons.
* [x] Setup `NavHost` with 5 empty screens (Home, Assets, Activity, Inbox, Settings).
* [ ] Ensure GitHub Action builds `app-debug.apk` successfully.
* [ ] **Milestone:** APK installs and shows 5 tabs with correct colors.

### Phase 2: Core Ledger (UI & Local DB)
**Goal:** See the dashboard and manually add data.
* [ ] Implement Room Entities (`Account`, `Asset`, `Transaction`, `Lot`).
* [ ] Build `MoneyText` composable (tabular numerals, hide-values state, currency formatting).
* [ ] Build **Home Screen UI**: Net Worth, Trajectory Chart (Canvas), Allocation Bar, Recent Activity.
* [ ] Build **Assets Screen**: List grouped by class, with monochrome icons.
* [ ] Build **Manual Entry Forms**: Add Asset, Add Transaction (Buy/Sell), Add Valuation (for Real Estate/Gold).
* [ ] **Milestone:** App can save manual transactions, calculate basic net worth, and reflect it on the dashboard.

### Phase 3: Ingestion Engine (Files & PDF)
**Goal:** Import documents and extract raw text.
* [x] Implement SAF (Storage Access Framework) file picker (PDF, CSV, XLSX, PNG, JPG).
* [x] Implement SHA-256 file hashing to prevent duplicate file imports.
* [x] Store imported files in app-private storage and track them in the Room Inbox.
* [x] Setup `PdfBox` to extract raw text from local PDFs.
* [x] Setup ML Kit to OCR scanned PDF pages locally.
* [x] Parse CSV/XLSX directly and persist reusable broker column mappings.
* [ ] Setup password-protected PDF handling (prompt for password, decrypt locally in memory).
* [x] Build **Inbox Screen**: Show files processing, failed jobs, and awaiting review.

### Phase 4: AI Extraction & Review Loop
**Goal:** Send text to AI, parse JSON, and let user review.
* [x] Extract local text from PDFs and images during ingestion.
* [x] Map structured CSV/XLSX columns directly into review drafts without OCR or AI.
* [x] Add a batch split-view review screen with confidence indicators.
* [x] Persist draft transactions and deduplicate confirmed records by fingerprint.
* [x] Add manual draft entry and editing to the review flow.
* [x] Add mock chunked extraction to validate the review pipeline without network calls.
* [ ] Build AI Provider interface (OpenAI-compatible, Ollama).
* [ ] Implement a PII Redaction layer (strip names/account numbers before sending to cloud AI).
* [ ] Define strict JSON schema for AI extraction (Asset, Txn Type, Qty, Price, Net Amount).
* [ ] Build **Review Split-View**:
    *   Top 40%: PDF preview (pinch to zoom, highlight bounding box).
    *   Bottom 60%: Bottom Sheet with extracted fields.
    *   **Fields:** Inline editable. `ConfidenceBadge` (Green >90%, Amber 70-90%, Red <70%).
    *   **Actions:** "Reject" (keeps as DRAFT) or "Accept" (saves to DB as CONFIRMED).
* [ ] Implement math validation (e.g., `Qty * Price + Fees == Net Amount`). Show warning if AI hallucinated.

### Phase 5: Audit & Calculated Wealth
**Goal:** The trust features and advanced math.
* [ ] Implement FIFO lot tracking for cost-basis and capital gains (STCG/LTCG).
* [ ] Implement XIRR and CAGR calculations for the portfolio.
* [ ] Build **Asset Detail Screen**: Overview (Lots, Cost), Transactions, Documents, Notes.
* [ ] Build the **Long-press Source Trail**: Bottom sheet tracing Net Worth → Asset → Transaction → Document → Page.
* [ ] Build **Documents Vault**: Show SHA-256 hashes, file sizes, and "Cryptographic Audit" badges.
* [ ] Implement "Hide Values" global toggle (Eye icon).
* [ ] **Milestone:** Tap a number and trace it back to the exact page of a PDF.

### Phase 6: Export, Privacy, & Release
**Goal:** Ship v1.0.
* [ ] Implement Settings: AI provider/keys, App Lock (Biometrics), Theme.
* [ ] Build **Data Export**: Full JSON export of all accounts, assets, transactions, and provenance.
* [ ] Build **Encrypted Backup**: ZIP the database + documents, encrypt with a user passphrase.
* [ ] Build Onboarding Flow: Privacy explanation, local-first emphasis.
* [ ] Final UI polish: Dark mode contrast check, 200% font scaling check, empty states.
* [ ] **Milestone:** Public open-source release.

---

## 7. Detailed UI Specs (For the AI Agent)

### Tab 1: Home Dashboard
* **Top Bar:** "OpenFolio" text. Eye icon (toggle `showValues`), Gear icon (Settings).
* **Net Worth:** `MoneyText` at `Display` size (32sp). Neutral text color. Subtitle "as of [Time]".
* **Chart:** Custom Compose Canvas line chart. Smooth teal line, no grid lines, dotted baseline.
* **Allocation:** Stacked horizontal bar. Max 5 segments. Legend below.
* **Recent Activity:** List of last 3 transactions. Icon + Name + Amount (neutral).
* **FAB:** Extended "Import" FAB (opens file picker).

### Tab 2: Assets
* **Header:** Toggle between "By Class" and "By Account".
* **Rows:** Class Icon (monochrome), Asset Name, Ticker (Monospace), Total Value, Gain/Loss (Neutral number + colored ▲/▼).
* **Sort:** By value, by last updated, by name.

### Tab 3: Activity (Transactions)
* **Sticky Day Headers** (e.g., "12 Mar 2025").
* **Filter Chips:** Type, Asset, Account, Status (DRAFT, CONFIRMED).
* **Rows:** Icon, Description (Monospace for tickers), Qty/Price, Net Amount. 
* **Provenance Dot:** `●` Manual, `○` Parsed, `◐` AI-reviewed.

### Tab 4: Inbox
* **Tabs:** "Awaiting Review", "Needs Attention", "Processed".
* **Review Card:** File name, detected type, extraction count, "Review X items" button.
* **Needs Attention:** Cards for password-protected PDFs, low confidence extractions, or stale prices.

### Tab 5: Settings
* **Security:** App Lock, Backup Passphrase.
* **AI Extraction:** Provider selection, API Key entry (encrypted), Local-only mode toggle.
* **Data:** Export JSON, Encrypted Backup, Wipe Data.
* **About:** Version, License, GitHub Repo, Threat Model.

---

## 8. Instructions for the AI Agent

When prompted to build a feature, the AI Agent MUST follow this protocol:
1. **Read `AGENTS.md`** for coding rules and strict architecture guidelines.
2. **Read this `ROADMAP.md`** to understand the current phase and the specific feature scope.
3. **Use the Design System:** Never hardcode Hex values in Compose. Always use `LocalOpenFolioColors.current`.
4. **Write Tests:** Any logic dealing with math (FIFO, Net Worth, FX rates) MUST have a corresponding unit test.
5. **Update `libs.versions.toml`** before adding any new dependency.
6. **Commit & Push:** Write descriptive commit messages. Pushing to `main` triggers the CI APK build.
