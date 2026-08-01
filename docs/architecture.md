# Architecture

This document defines the default architecture for new Zyna Android features.
It describes ownership and dependency rules rather than every current class, so
it should remain useful as the product grows.

## Architectural Style

Zyna uses a single-activity, MVVM-style shell with:

- a state-based router;
- feature stores and unidirectional data flow;
- coordinators for asynchronous orchestration;
- repositories and services at the data boundary.

A concise name for the approach is **MVVM + Router + Feature Stores**.

It is not strict Redux or MVI: there is no global action type, reducer, or
single application state. It is also not strict Clean Architecture: domain
use-cases are introduced only when reusable business rules justify them.

## Data Flow

The normal feature flow is:

```text
User action
    |
    v
View / Screen
    |
    v
Feature Store ---> Driver ---> Repository / Matrix SDK / Room
    ^                                      |
    |                                      |
    +--------------- StateFlow <-----------+
```

Views send actions and render immutable state. They do not own feature-data
subscriptions or call the Matrix SDK and repositories to produce screen state.
Narrow media and playback controllers may be passed to views for rendering or
direct user interaction, but they must not become competing state sources.

`AppViewModel` sits beside this flow. It owns application routing and connects
features, but it should not absorb their internal state or coroutine lifecycle.

At the render boundary, root actions are grouped by feature rather than kept in
one flat callback list. Large features may split their group further by
responsibility, as chat does for navigation, timeline, composer, and message
actions. Runtime rendering dependencies such as media and playback controllers
stay separate from user actions. `ZynaRootHostView` adapts these root groups to
the narrower `*ScreenViewActions` required by each screen.

## Main Components

### Views

`*ScreenView`, Compose screens, and `ZynaRootHostView` render state and emit
user actions.

Views may own ephemeral presentation details such as an active touch gesture,
layout measurements, or a transient animation. State that must survive a
render, route change, or asynchronous operation belongs outside the view.

### Feature Stores

A feature store is the presentation model for one cohesive feature. It usually
owns:

- an immutable `FeatureState` exposed as `StateFlow`;
- user intents such as `send`, `retry`, `select`, or `loadOlder`;
- active target or session identity;
- feature jobs, cancellation, and stale-result guards;
- loading and error state.

Stores are not Android `ViewModel` subclasses. Their lifetime is currently
owned by `AppViewModel`, which fits the custom single-activity navigation
model.

Current examples include `ChatTimelineStore`, `ChatComposerStore`,
`OwnProfileStore`, `ContactsStore`, and `RoomListStore`.

### Coordinators

A coordinator owns an asynchronous workflow that does not naturally represent
the full state of a screen. Typical examples are:

- read-receipt delivery;
- resolving a direct room before navigation;
- observing room call state;
- coalescing repeated snapshot requests.

Use a coordinator when the main problem is ordering, cancellation, ownership,
or delivery rather than rendering a complete feature state.

### AppViewModel

`AppViewModel` is the application-level ViewModel and coordinator. It may own:

- login, logout, and session transitions;
- security-gate integration;
- global navigation and deep links;
- activation and deactivation of feature stores;
- workflows that connect multiple features;
- thin forwarding methods used by the root action surface.

It should not own feature-specific item lists, loading flags, errors, jobs, or
generation counters.

The following additions are usually a signal that a feature store or
coordinator is needed:

```text
fooItems
fooLoading
fooError
fooJob
fooGeneration
fooActiveTarget
```

`AppUiState` is reserved for app-shell state such as navigation, session,
security, global presence, logout, and platform launches. Feature state must
not be mirrored into `AppUiState`.

### Router

`AppRoute` and immutable `AppNavState` are the source of truth for application
navigation. Navigation changes use reducer-like methods on `AppNavState` and
are applied by `AppViewModel`.

See [Navigation](navigation.md) for route, tab, stack, and back behavior.

### MainActivity

`MainActivity` is the Android platform host. It is the correct boundary for:

- runtime permissions;
- activity-result APIs and system pickers;
- intents and saved-instance-state delivery;
- window and overlay presentation;
- lifecycle callbacks that must reach platform controllers.

Feature business state should not be added to the activity. If a platform flow
becomes independently complex, extract a platform coordinator instead of
moving it into a feature store or `AppViewModel`.

### Data Layer

Repositories and services own database, SDK, network, media, and durable-work
details. Feature-state access reaches them through narrow store drivers where
practical. Views may receive focused rendering or playback dependencies such
as media loaders and audio controllers, but should not use them to own feature
data or asynchronous screen state.

A driver is a test seam, not a second repository. It exposes only the
operations required by one store and makes state-machine tests independent of
large SDK facades.

For durable outgoing work, persistence is the source of truth and WorkManager
provides eventual background delivery. UI coroutine scope alone must not be
used for work that must survive process death.

## State Ownership

Every mutable concept must have one owner.

- Navigation is owned by `AppNavState`.
- Feature rendering state is owned by its store.
- Durable outgoing state is owned by the database/outbox.
- Cached reactive data is owned by the relevant repository and exposed through
  a feature store.
- Ephemeral gesture and layout state may be owned by a view.

Do not keep a store state and a mirrored copy in `AppUiState`. Independent
flows should be combined at the render boundary, as chat, profile, contacts,
and room-list state are today.

Closely related states should be grouped into immutable render inputs such as
`ChatFeatureState`, `ProfileFeatureState`, or `ContactsFeatureState`. When the
number of flows grows, nest `combine` operations around feature groups instead
of flattening or copying their fields into `AppUiState`.

## Reactive Cached Data

For data such as the room list, the preferred flow is:

```text
Matrix signal -> authoritative snapshot -> local cache -> Flow -> Store state
```

The UI renders the cached flow. SDK synchronization writes the cache instead
of publishing a competing in-memory list. This preserves one source of truth
and supports fast startup.

When the persisted session identity is known before the Matrix client has
finished restoring, activate the cached projection during that restoration.
Route activation may reveal a cache-backed screen only after its first cache
emission; otherwise a durable cache still produces an avoidable empty frame.
The inverse transition has the opposite ordering: publish a terminal or login
route before awaiting feature teardown. Cancellation or closure of an SDK
operation must not delay the shell transition that already hides its data.

When an asynchronous database read would otherwise leave a route's first
frame empty, the repository may keep a bounded in-memory mirror of durable
snapshots. The mirror must be populated only from cache reads or successful
cache writes, be keyed by session identity, and be cleared with the durable
cache. Once seeded, only a successful repository write may replace an entry;
an asynchronous database emission must not roll it back. Wall-clock timestamps
are metadata, not an ordering mechanism. The mirror is a synchronous seed for
the same cached state, not a second live source.

Paginated SDK state is not authoritative merely because the first update has
arrived. A partial refresh must not erase a more complete cached snapshot.
Keep or merge the cached tail while pagination is incomplete, and allow a
terminal snapshot to replace it atomically, including a legitimate terminal
empty result.

For the main room list, the Matrix SDK's ordered dynamic entries are the
ordering authority. Apply their diffs serially and persist sparse order labels;
do not rebuild or alphabetically/timestamp-sort a second list in app code.
Preserve existing labels where their relative order is still valid so moving
one room does not rewrite every shifted database row. A failed positional diff
invalidates that SDK projection: stop publishing it and reopen a fresh dynamic
list rather than applying later indexes to an uncertain base. Reopening is
bounded by an exponential-backoff circuit breaker; a persistent SDK or mapping
failure must leave the durable cache visible instead of creating an infinite
retry loop. Explicitly known left or banned entries may be removed from a
partial cached list immediately;
absence alone remains authoritative only in a terminal snapshot.
An opened circuit is presentation state, not a log-only condition: keep cached
rooms visible and expose an explicit retry action.
The SDK's maximum count describes the unfiltered source, so pagination
completion must be based on consumed source positions even when left rooms are
omitted from the rendered projection. Subscribe only the visible rooms plus a
small prefetch window so their latest event and room info stay warm without
expanding every sync into per-room FFI work. Viewport ownership must be scoped
to a concrete screen instance: a hidden or detached screen may clear only its
own subscription and must not override a newly revealed room-list consumer.
List reordering follows the same ownership rule for scroll state: preserve an
explicit room anchor only after the user has scrolled, while a viewport already
at the top remains at position zero when the SDK moves rooms above old rows.

The room-list repository may keep a session-scoped in-memory mirror containing
a compact ordering projection. Seed it once from Room and update it only
after successful transactions; this avoids rereading and sorting every cached
room for each live diff without mirroring mutable room content. A rooms-table
writer outside the authoritative snapshot transaction invalidates this mirror
when it can add a row or change explicit ordering. Preview-only writes do not:
a warm mirror contains explicit labels for every row, so timestamp and name
fallback keys cannot affect its order. The SDK session tracks changed room IDs
until the corresponding cache revision is acknowledged,
so normal updates read and write only affected summaries. Provisional rooms
created by navigation workflows may temporarily lead a partial list, but their
retention must be time-bounded as well as reconciled by terminal snapshots.
Expiry may delete only a room explicitly written by the create-room workflow;
a room resolved by an existing-room or direct-message workflow merely loses
its temporary leading status, regardless of whether it was already cached.

For a derived list, readiness belongs to its upstream source. In particular,
an early empty Spaces root update cannot remove cached roots until the SDK room
list reports `Loaded`; at that transition, read a fresh root snapshot instead
of promoting a pre-readiness update retroactively.

Request the first hierarchy page on activation, then paginate on viewport
demand. A failed page request keeps the live session and cached content owned
by the store and exposes an inline retry; it must not turn a populated screen
into a terminal stale state.

This is not a pull-to-refresh contract. Reactive SDK signals and session
activation drive synchronization.

Capabilities that require an SDK/FFI query should be resolved for the active
feature target, then written through to the cache. A broad list snapshot must
represent an unavailable capability as unknown and preserve a cached known
value; it must not perform one capability query per list item or turn missing
SDK data into a definitive denial.

## Coroutine and Lifecycle Rules

- A store or coordinator that starts a job owns its cancellation.
- `activate(target)` and `deactivate()` should define target lifetime when a
  feature observes long-lived flows.
- Route ownership may cover a typed route subtree rather than only the top
  route. A parent store should remain active while matching child routes need
  its live state, and deactivate as soon as navigation leaves that subtree.
  Derive this ownership in the router, include target identity in the check,
  and cover it with navigation tests instead of inferring it independently in
  each feature.
- Capture session, route, room, or user identity in every asynchronous request.
- Re-check ownership after suspension before committing state or navigation.
- Use a generation or request identity when cancellation alone cannot reject a
  late result.
- Treat `CancellationException` as control flow and rethrow it.
- Cache writes may be best-effort only when the product action remains correct
  without them. Document that decision explicitly.
- Public store methods and callbacks are main-thread confined unless the class
  documents and implements synchronization.

## Where New Code Goes

| Change | Default owner |
| --- | --- |
| Open or close an application route | `AppNavState` + `AppViewModel` |
| Screen content, loading, or error state | Feature store |
| Feature retry, debounce, job, or generation | Feature store/coordinator |
| Workflow connecting multiple features | `AppViewModel` or coordinator |
| Matrix SDK, Room, network, or media operation | Data service/repository |
| Narrow store dependency on data operations | Driver |
| Android permission, picker, intent, or window | `MainActivity`/platform coordinator |
| Gesture, animation, layout, or scroll behavior | View/controller |
| Pure decision or transformation | Policy/mapper with unit tests |
| Work that must survive process death | Persistent data layer + WorkManager |

## Adding a Feature

Use the smallest structure that gives the feature one clear owner.

For a stateful asynchronous screen, the normal steps are:

1. Add a feature package under `ui/`.
2. Define an immutable state data class.
3. Add a store exposing `StateFlow<State>` and intent methods.
4. Add a narrow driver for required data operations.
5. Add route state and reducer-like navigation methods if it is a new screen.
6. Wire the store into `AppViewModel` without copying its state there.
7. Combine the feature flow at the render boundary.
8. Test state transitions, cancellation, stale results, and error behavior.

A store is usually warranted immediately if the feature has any of the
following:

- independent loading/content/error state;
- a long-lived `Flow` subscription;
- retry, debounce, or more than one asynchronous operation;
- an active room, user, event, or session target;
- state that must survive rerendering or navigation transitions.

A small synchronous navigation action or a thin forwarding method may remain
in `AppViewModel`. Do not create a store, use-case, or interface solely to make
the layer diagram look complete.

## Testing Expectations

Prefer JVM unit tests for stores, coordinators, routers, policies, and mappers.
Tests should focus on contracts that are easy to regress:

- activation and deactivation;
- session or target replacement;
- cancellation-swallowing dependencies;
- late results;
- coalescing and retry behavior;
- loading and error transitions;
- one-time command consumption;
- navigation stack invariants.

UI tests are most valuable for behavior that depends on Android views, touch,
layout, permissions, or platform integration.

## Current Intentional Trade-offs

- `AppViewModel` has a broad public forwarding API because `MainActivity`
  assembles the feature-grouped root action surface. This facade is
  intentional; a new large feature should add its own action group instead of
  expanding unrelated groups.
- `MainActivity` and `ZynaRootHostView` are large platform/render boundaries.
  Split them when a subsystem gains independent lifecycle or test value, not
  only because of line count.
- `MatrixClientService` and `LocalCacheRepository` are broad data facades.
  Extract internal domain components incrementally when those areas change;
  avoid a large mechanical rewrite that only redistributes methods.
- Session orchestration remains in `AppViewModel`. Extract an
  `AppSessionCoordinator` if multi-account support or session/security rules
  make that workflow independently complex.

These trade-offs are not permission to add feature state to the app shell.
They identify stable boundaries where future extraction should be driven by a
real lifecycle, ownership, or testing need.
