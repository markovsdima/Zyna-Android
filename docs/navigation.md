# Navigation

Zyna uses a custom view-based navigation layer. The goal is to keep full
control over transitions, stack lifetime, view reuse, and rendering cost
without depending on AndroidX Navigation, including Fragment or Compose
navigation.

## Core Rules

- `AppNavState` is the single source of truth for app navigation.
- `ZynaRootHostView` renders the current stack and must not own navigation
  state such as the selected tab or nested screen flags.
- A screen is represented by a typed `AppRoute`.
- Navigation changes go through reducer-like functions on `AppNavState` and
  view-model actions.
- Views may consume back only for internal UI state, such as an opened media
  viewer. App-level back navigation belongs to the app navigation state.

## Modes

`AppNavMode` separates authentication flow from the main app:

- `Login`
- `RecoveryKey`
- `Main`

Login and recovery routes replace the visible stack. Main app navigation keeps
fixed tab roots and per-tab stacks.

## Tabs

The main app has fixed roots:

- Contacts: `AppRoute.Contacts`
- Calls: `AppRoute.Calls`
- Chats: `AppRoute.Rooms`
- Profile: `AppRoute.Profile`

Each tab owns its own stack. Switching tabs changes `selectedTab` and preserves
the other tab stacks. Tapping the active tab pops that tab back to its root.
Tab selection is ignored while the tab bar is hidden.

Chats are special only because chat screens hide the tab bar. The current chat
is still part of the chats stack, not an external overlay.

## Back

Back handling is centralized in `AppViewModel.navigateBack()`.

Expected behavior:

- Pop the active tab stack when possible.
- Close chat through the same path that clears chat-specific state.
- Close modal-like app routes, such as the forward picker, by popping their
  route.
- When a non-chat tab is already at root, return to Chats.
- When Chats is already at root, let the system handle back.

`ZynaRootHostView.handleBack()` may first ask the top view whether it consumed
back locally. If not, it delegates to the view model.

## Adding A Screen

To add a screen:

1. Add a typed `AppRoute`.
2. Add an `AppNavState` reducer function for opening or closing it.
3. Expose a view-model action that applies the reducer.
4. Add a `ZynaRootHostView` entry mapping for the new route.
5. Keep screen-local state inside the screen only when it is not app
   navigation.

Avoid adding navigation flags directly to views. If a new screen should survive
render updates, back handling, or tab switching, it belongs in `AppNavState`.

## Current Shape

The current stack renderer is `ZynaNavigationStackView`. It receives typed
`ZynaScreenEntry` values from `ZynaRootHostView` based on
`AppUiState.navigationStack`.

This keeps the performance-sensitive rendering layer custom while making
navigation behavior explicit and testable.
