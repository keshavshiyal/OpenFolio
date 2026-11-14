# OpenFolio AI Agent Instructions

## Tech Stack & Architecture
- **Language**: Kotlin (100%)
- **UI**: Jetpack Compose (Material 3)
- **Architecture**: MVVM + Clean Architecture (Data, Domain, UI layers)
- **DI**: Hilt
- **Database**: Room + SQLCipher (for local encryption)
- **Navigation**: Jetpack Navigation Compose

## Strict Coding Rules
1. **No XML layouts.** UI is 100% Compose.
2. **No hardcoded colors.** Always use `LocalOpenFolioColors.current` from `core/ui/theme`.
3. **Typography**: Use `MaterialTheme.typography`. All financial numbers MUST use tabular figures.
4. **State**: Use `StateFlow` and `collectAsStateWithLifecycle()`.
5. **Dependencies**: Do not add new Gradle dependencies without updating `libs.versions.toml`.
6. **Coroutines**: Use `viewModelScope` for UI logic. Use `Dispatchers.IO` for DB/File operations.

## Design System (Teal Anchor)
- Background: `#FAFAF7` (Light), `#0E1113` (Dark)
- Surface: `#FFFFFF` (Light), `#171B1E` (Dark)
- Primary Brand: `#0F6E5C` (Teal)
- Gain text: Neutral `#1A1D1F`, Gain glyph: `#1F7A4D`
- Loss text: Neutral `#1A1D1F`, Loss glyph: `#A93226`

## Data Model Rules
- Wealth consists of `Asset` (Stock, MF, FD, etc.) and `Transaction` (Buy, Sell, Dividend, etc.).
- Never use `Double` for money. Use `BigDecimal` or `Long` (minor units).
- Transactions require provenance (document ID, confidence, manual/AI flag).

## Testing
- Write unit tests for all ViewModels.
- Write unit tests for cost-basis / FIFO calculations.