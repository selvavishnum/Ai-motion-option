# Gesture Mapping UI — Component Architecture

A production-grade rewrite of the gesture→action mapping settings screen, built in Jetpack
Compose under `app/src/main/java/com/aimotion/handsfree/ui/mapping/`. Reachable from the main
screen via **"Gesture mapping (new UI, beta)"**.

## Why a rewrite, and why it's additive (not a replacement) for now

The original mapping list is a `RecyclerView` + XML `Spinner`-based screen wired directly into
`MainActivity`. It works, but isn't reusable, isn't easily testable/previewable in isolation,
and uses `Spinner` — one of the least accessible standard Android widgets (poor TalkBack focus
order, no easy way to show "why" a choice is disabled).

This rewrite ships as a **separate entry point** (`GestureMappingComposeActivity`), not a
replacement of the working screen, for one reason: this environment cannot compile-test Android
code locally (network policy blocks Google's Maven repo), so every previous native-code change
in this project has gone through a CI-catches-it fix cycle before being confirmed correct. The
existing screen is already verified working on a real device. Shipping the new one alongside it
— rather than deleting a known-good screen in favor of an unverified one — is the same
"gradual rollout" judgment call a production team makes with a feature flag. Once this build is
confirmed working on-device, `MainActivity` can link directly to it and the old screen retires.

## Component architecture

```
ui/mapping/
├── MappingModels.kt          — MappingItem, InstalledApp, MappingUiState (data, no UI)
├── GestureMappingViewModel.kt — loads/saves both mapping stores, publishes MappingUiState
├── MappingTheme.kt            — Material3 theme wrapper (dark now, light-ready)
├── MappingStateViews.kt       — LoadingState, EmptyState, ErrorState (generic, reusable)
├── ActionDropdown.kt          — accessible single-select dropdown for ActionType
├── MappingRow.kt              — one gesture's row: emoji + label + ActionDropdown + app button
├── MappingSection.kt          — LazyListScope extension: a titled group of MappingRows
├── AppPickerSheet.kt          — searchable bottom sheet for the "Launch app" action
└── GestureMappingScreen.kt    — composes everything; the only screen-level component
```

```
GestureMappingComposeActivity
  └── MappingTheme
        └── GestureMappingScreen (state: Loading | Error | Content)
              ├── LoadingState / ErrorState / EmptyState   (state != Content.notEmpty)
              └── LazyColumn                                (state == Content, non-empty)
                    ├── mappingSection("Hand gestures", …)
                    │     └── MappingRow × N
                    │           └── ActionDropdown
                    └── mappingSection("Face gestures", …)
                          └── MappingRow × N
              └── AppPickerSheet (shown conditionally, on top)
```

**Data flow is one-directional and unidirectional-state (MVVM):** the ViewModel is the single
source of truth (`StateFlow<MappingUiState>`); every composable below `GestureMappingScreen` is
stateless and driven entirely by parameters + callbacks. No component reads SharedPreferences,
touches `PackageManager`, or holds mapping data itself — that's what makes each one testable
and previewable (see the `@Preview` functions in `MappingStateViews.kt`, `ActionDropdown.kt`,
and `MappingRow.kt`) without a running app, ViewModel, or device.

## Props / API design

| Component | Key props | Design notes |
|---|---|---|
| `LoadingState(label)` | `label: String` | Generic — no gesture-domain knowledge, reusable by any screen. |
| `EmptyState(emoji, title, body, action?)` | data-only + optional trailing composable slot | The `action` slot lets a caller add a retry/CTA button without the component knowing what it does. |
| `ErrorState(message, onRetry)` | `message: String`, `onRetry: () -> Unit` | Always requires a retry path — an error state with no way forward is a dead end for the user. |
| `ActionDropdown(selected, options, onSelected)` | fully controlled | No internal "current value" state; the caller re-renders with the new `selected` after `onSelected` fires. This is what makes it safe to reuse across hand- and face-gesture rows without them getting out of sync. |
| `MappingRow(item, actionOptions, onActionSelected, onChooseApp)` | `item: MappingItem` (data), two callbacks | Takes the *generic* `MappingItem`, not `Gesture`/`FaceGesture` — this one component renders both mapping sections. |
| `mappingSection(title, subtitle, items, …)` | `LazyListScope` extension, not a `@Composable` | Sections share one `LazyColumn` (see below) instead of nesting scrollables. |
| `AppPickerSheet(apps, onDismiss, onAppSelected)` | `apps: List<InstalledApp>` (pre-fetched) | The sheet has zero Android-framework knowledge — it doesn't call `PackageManager` itself, keeping it trivially previewable with fake data. |

**Why `MappingItem` instead of passing `Gesture`/`FaceGesture` directly into the components:**
this is the crux of the reusability. Every visual component in this system knows about exactly
one shape — id/emoji/label/action — never about the two different domain enums. Adding a third
trigger modality later (e.g. a physical button, a voice command) means writing one mapper
function into `MappingItem`, not touching a single Compose file.

## Handling the required production states

- **Loading**: modeled as its own `MappingUiState.Loading`, not inferred from "data is null" —
  an explicit state, not an absence of one.
- **Empty**: `MappingUiState.Content.isEmpty` covers the case where gesture definitions are
  ever empty (e.g. behind a remote kill-switch) — the screen and the `AppPickerSheet` (search
  yields zero results, or the device somehow has zero launchable apps) both have dedicated empty
  states with distinct copy, not a shared generic "nothing here."
- **Error**: any failure loading the mapping stores surfaces as `MappingUiState.Error` with a
  message and a `Retry` button — errors are announced to screen readers via
  `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` so they aren't silently missed.
- **Edge cases handled explicitly**: picking a new action type drops any previously-chosen app
  package (`resolveNewAction` in `GestureMappingScreen.kt`) so switching away from and back to
  "Launch app" always re-prompts instead of silently reusing a stale, possibly-wrong app; the
  installed-apps list is only fetched once, lazily, the first time the picker opens (not on
  every screen load) via `LaunchedEffect` keyed on whether the picker is open.

## Responsive design & performance

- `LazyColumn` (not `Column` + `ScrollView`) for the trigger list — matters once the trigger
  count grows (more gesture/expression types, or a future "custom gesture" feature); each row
  is keyed by a stable `MappingItem.id`, so Compose can diff/recycle correctly instead of
  rebuilding the whole list on every recomposition.
- The app picker's list is genuinely large on some devices (100+ launchable apps) — also a
  `LazyColumn`, with local, debounce-free `String.contains` search filtering via `remember(apps,
  query)` so filtering only recomputes when either actually changes.
- No fixed pixel dimensions beyond icon/touch-target sizing; everything else uses `fillMaxWidth`
  / `wrapContentHeight`, so it adapts to any phone width without a tablet-specific layout pass
  being required later.

## Accessibility

- `ActionDropdown` uses Material3's `ExposedDropdownMenuBox` pattern — correct TalkBack focus
  order and expanded/collapsed state announcement, unlike the legacy `Spinner`.
- Decorative glyphs (the emoji in each row, app icons in the picker) are explicitly marked
  non-informative via `Modifier.clearAndSetSemantics {}` / `contentDescription = null` — a
  screen reader should announce "Open palm, mapped to Back", not "hand with palm facing up
  emoji, Open palm, mapped to Back".
- Every interactive control (`ActionDropdown`, `OutlinedButton`, `AppPickerRow`) uses Material3
  defaults, which meet the 48dp minimum touch-target size — never manually shrunk.
- `ErrorState`'s live region ensures failures are announced without the user needing to
  manually discover them.

## Usage example

```kotlin
// Anywhere a NavHost or Activity needs the screen:
GestureMappingScreen(
    onBack = { /* pop back stack / finish() */ },
    // viewModel: GestureMappingViewModel = viewModel() is the default — override only in tests/previews
)
```

```kotlin
// Reusing just the state components elsewhere in the app, with zero gesture-domain coupling:
when (someOtherScreenState) {
    is Loading -> LoadingState(label = "Loading your settings")
    is Error -> ErrorState(message = someOtherScreenState.message, onRetry = ::reload)
    is Empty -> EmptyState(emoji = "📭", title = "Nothing yet", body = "Add your first item.")
    ...
}
```

## Best practices this follows

1. **State hoisting, always.** No component below the screen level owns mutable business
   state — only `GestureMappingScreen` (search query in `AppPickerSheet` is the one exception:
   genuinely transient, UI-only, thrown away on dismiss, which is exactly what local
   `remember { mutableStateOf(...) }` is for).
2. **One ViewModel, one `StateFlow`, one `when` exhaustively handling every state** — the
   compiler enforces that `Loading`/`Error`/`Content` are all handled; forgetting a case is a
   build error, not a runtime bug.
3. **Previews for every visual component**, so a developer can iterate on a row's design
   without running the app, granting camera/accessibility permissions, or waiting for the
   camera pipeline to start.
4. **No premature abstraction beyond what two call sites (hand + face gestures) justify** — the
   generic `MappingItem`/`MappingRow` pattern exists because it already has two real
   consumers, not speculatively for a third that doesn't exist yet.
5. **Reads/writes go through `Dispatchers.IO`** even though today's storage (SharedPreferences)
   is fast enough not to need it — so the persistence layer can change without ever touching
   this UI code.
