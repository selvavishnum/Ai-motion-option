# Paper — a minimalist component system

A paper-white Compose design system for Air Sensor, living in
`app/src/main/java/com/aimotion/handsfree/ui/paper/`.

See it running: **Air Sensor → Paper design system**. Every component and every state on that
screen is real and interactive — it is the reference, not a screenshot in a doc that goes stale.

---

## Design principles

These are encoded in tokens rather than left to reviewer discipline, so they hold by default.

| Principle | How it's enforced |
| --- | --- |
| **No pure white or black** | Paper is `#FAF9F5`, ink is `#1A1A18`. `#FFF`/`#000` glare on OLED and read as cheap. |
| **Hairlines, not shadows** | There is a `rule` colour and **no elevation token**. Depth comes from a 1dp line and a tone shift. |
| **One accent, used sparingly** | A single ink-blue carries every interactive affordance. Colour is information, not decoration. |
| **Closed spacing scale** | A 4dp scale with named steps. Stops the drift into 13/17/23dp that makes a UI look subtly untidy. |
| **Hierarchy by weight, not size** | Seven type styles, four sizes. Eight sizes reads as noise. |
| **Never colour alone** | Status pills always state the status in words, so they survive greyscale and colour blindness. |

---

## Architecture

Three layers. Each depends only on the one beneath it, so a palette change never reaches a
screen and a screen change never reaches a token.

```
┌─────────────────────────────────────────────┐
│ Screens        GestureMappingScreen,        │  domain-aware, owns state
│                PaperShowcaseScreen          │
├─────────────────────────────────────────────┤
│ Components     PaperButton  PaperCard       │  stateless, domain-free
│                PaperRow     PaperSwitchRow  │  props in, callbacks out
│                PaperStates  PaperStatusPill │
├─────────────────────────────────────────────┤
│ Tokens         PaperColors  PaperSpacing    │  values only, no Compose UI
│                PaperDimens  Typography      │
└─────────────────────────────────────────────┘
```

| File | Responsibility |
| --- | --- |
| `PaperTokens.kt` | Colour, spacing, dimension tokens + the `CompositionLocal`s that carry them. |
| `PaperTheme.kt` | Theme provider, typography, Material3 bridge, reduced-motion detection. |
| `PaperButton.kt` | Button: 4 variants × 2 sizes × loading/disabled. |
| `PaperContainers.kt` | `PaperScreen`, `PaperCard`, `PaperSectionHeader`, `PaperRule`. |
| `PaperRows.kt` | `PaperRow`, `PaperSwitchRow`, `PaperStatusPill`. |
| `PaperStates.kt` | `PaperLoadingState`, `PaperEmptyState`, `PaperErrorState`, `PaperSkeletonBar`. |
| `PaperShowcaseActivity.kt` | The live gallery. |

**Every component below the screen layer is stateless.** State is hoisted to the caller, which is
what lets the same `PaperSwitchRow` back a ViewModel-driven setting in the app and a
`remember { mutableStateOf(...) }` in a preview, with no branching inside the component.

---

## Props / API design

Conventions applied uniformly, because a system you have to look up is a system nobody uses:

1. **Required args first, `modifier` next, optional args after.** Standard Compose ordering;
   `modifier` always applies to the outermost node. The consequence: **every optional argument is
   passed by name.** `PaperSectionHeader("Rows", "A subtitle")` binds the second string to
   `modifier` and fails to compile — the same trap `Text(text, modifier, color, …)` has. Worth
   stating plainly, because the first CI run of this system was red for exactly this reason, in
   four places.
2. **Impossible states are unrepresentable.** `loading = true` on a button blocks input by itself —
   a caller cannot forget `enabled = false` and ship a double-submit bug.
3. **Slots over flags.** `PaperRow(trailing = { ... })` takes a composable, so a chevron, a value,
   a badge or a button all work without the component growing a parameter each time.
4. **Enums over booleans.** `variant = Danger` beats `isDanger = true`; adding a fifth variant
   doesn't mean a fifth boolean and 2⁵ undefined combinations.
5. **Accessibility is not a parameter.** Touch targets, roles and merged semantics are built in.
   There is no `accessible = true` to forget.
6. **Recovery is mandatory where it matters.** `PaperErrorState.onRetry` is required, not optional,
   so a dead-end error can't ship by accident.

### `PaperButton`

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `text` | `String` | — | Use a verb: "Save", not "OK". |
| `onClick` | `() -> Unit` | — | Never fires while loading or disabled. |
| `variant` | `PaperButtonVariant` | `Primary` | `Primary` / `Secondary` / `Ghost` / `Danger`. Max one `Primary` per screen. |
| `size` | `PaperButtonSize` | `Medium` | Affects visible padding only; touch target stays 48dp. |
| `enabled` | `Boolean` | `true` | |
| `loading` | `Boolean` | `false` | Implies disabled. |
| `loadingLabel` | `String` | `"Loading"` | Announced to screen readers while loading. |
| `fillWidth` | `Boolean` | `false` | |

### `PaperSwitchRow`

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `title` | `String` | — | What the setting controls. |
| `checked` | `Boolean` | — | Hoisted — the component owns no state. |
| `onCheckedChange` | `(Boolean) -> Unit` | — | |
| `description` | `String?` | `null` | Explain the *consequence*, not the mechanism. |
| `enabled` | `Boolean` | `true` | |

### `PaperEmptyState` / `PaperErrorState`

`title` and `body` are both required. An empty state must answer *why is this empty* and *what
do I do now*; making both mandatory is how the API refuses to let a dead end ship.

---

## Handling the hard parts

**Loading** — a skeleton, not a centred spinner. The layout is preserved, so content lands where
its placeholder already sat instead of the page jumping. The whole block collapses to one
accessibility node: a screen reader has nothing useful to say about eight grey rectangles.

**Empty** — distinguishes *nothing yet* from *nothing matching your filter*, and always offers the
next action.

**Error** — a polite live region, announced on appearance rather than waiting to be found, with a
mandatory retry.

**Edge cases handled in-component:**
- Long labels ellipsise; they never wrap into a broken layout or truncate to nothing.
- Disabled and loading are visually distinct — a greyed button and a spinning one mean different
  things to a waiting user.
- Reduced-motion is honoured: skeletons render flat when the user has disabled animations.
  Looping motion is genuinely unpleasant with a vestibular disorder, so that setting is an
  instruction, not a preference to design around.
- System settings reads are wrapped in `runCatching` — a blocked setting can't take a screen down.

**Responsive** — `PaperScreen` caps content at 600dp and centres it, so phone layouts don't
stretch across tablets and unfolded foldables. It scrolls by default: a screen that fits today
stops fitting at a 1.5× font scale or in split-screen, and a clipped settings page is a support
ticket. All type is in `sp`, so it scales with the reader's setting.

**Accessibility**
- Touch targets are floored at 48dp inside the components.
- Contrast against paper: ink ≈ 16.5:1, `inkMuted` ≈ 5.4:1, accent ≈ 8.1:1 — AA or better.
  `inkFaint` (≈3.1:1) deliberately does *not* reach AA and is restricted to text that duplicates
  information available elsewhere.
- Switch rows merge into a single node — "title, description, on" — instead of three stops.
- Section headers are marked `heading()`, so readers can jump between groups.
- Decorative glyphs are cleared from the tree; an emoji read as "raised hand sign" is noise.

---

## Usage

```kotlin
setContent {
    PaperTheme {                       // follows the system theme by default
        PaperScreen {                  // paints paper, caps width, scrolls
            PaperSectionHeader("Detection", "Which sensors are running.")

            PaperCard {
                PaperSwitchRow(
                    title = "Air gestures",
                    checked = uiState.handEnabled,
                    onCheckedChange = viewModel::setHandEnabled,
                    description = "Hand poses control the screen.",
                )
                PaperRule()
                PaperRow(
                    title = "Accessibility",
                    subtitle = "Required to control other apps",
                    trailing = { PaperStatusPill("Off", PaperTone.Critical) },
                )
            }

            PaperButton("Save changes", onClick = viewModel::save, fillWidth = true)
        }
    }
}
```

Driving the three states from one sealed UI state:

```kotlin
when (val state = uiState) {
    is UiState.Loading -> PaperLoadingState("Loading gestures")
    is UiState.Error   -> PaperErrorState(
        title = "Couldn't load your settings",
        message = state.message,
        onRetry = viewModel::load,
    )
    is UiState.Content -> if (state.items.isEmpty()) {
        PaperEmptyState(
            title = "No gestures configured",
            body = "Add one to start controlling your phone hands-free.",
            action = { PaperButton("Add gesture", ::addGesture, size = PaperButtonSize.Small) },
        )
    } else {
        state.items.forEach { PaperRow(title = it.label, subtitle = it.action) }
    }
}
```

A sealed state is what makes "loading **and** populated" unrepresentable, rather than something
caught in review.

---

## Best practices

**Do**
- Read every value from `PaperTheme.colors` / `PaperTheme.spacing`.
- Hoist state; keep components below the screen layer stateless.
- Give each screen exactly one `Primary` button.
- Add a `@Preview` per component state, plus a `fontScale = 1.5f` preview for anything with a
  layout worth protecting.
- Write empty-state copy in the user's terms, and always offer the next step.

**Don't**
- Hard-code a `Color(0xFF…)` or a raw `dp` in a screen. That is the single change that starts the
  drift away from the system.
- Add an elevation or shadow. Not part of this system — use `PaperRule` or a tone shift.
- Put domain types (`Gesture`, `FaceGesture`) in a component signature. Map to a UI model at the
  screen layer, which is what lets one row render both hand and face triggers.
- Use `inkFaint` for anything a user must be able to read.
- Ship a state you haven't drawn. Loading, empty and error are features.

---

## Relationship to `ui/mapping/`

`ui/mapping/` is the existing gesture-mapping component system (see `COMPONENTS.md`). Its
architecture — a `MappingUiState` sealed interface, a domain-free `MappingItem`, stateless
components — is the pattern Paper follows, and its `MappingTheme` is the dark predecessor to
`PaperTheme`.

Paper is the token and primitive layer those screens were missing; `ui/mapping/` can migrate onto
it by swapping `MappingTheme` for `PaperTheme` and replacing its local Material components. That
migration is deliberately **not** bundled into the same change as introducing the system, so the
new layer can be reviewed and seen on-device before anything already working is touched.
