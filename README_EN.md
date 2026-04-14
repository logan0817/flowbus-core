Chinese document [中文文档](./README.md)

# flowbus-core

`flowbus-core` is the platform-neutral core module of FlowBus.

Built on Kotlin Coroutines and Flow, it provides:

- typed event dispatch
- sticky events for late subscribers
- root bus / scoped bus isolation
- explicit lifecycle control through `FlowBusScope`
- named event handles via `EventChannel<T>`
- concise Kotlin-style APIs such as `event.send()`

If you are not building an Android app, or if you want to manage bus instances, scope lifecycle, or multi-instance isolation yourself, this is the main entry point.

## Good fit

Start from `flowbus-core` directly if any of these is true:

1. You are not in an Android-first integration scenario.
2. You want to create `FlowBus()` yourself instead of relying on the default singleton.
3. You need multi-instance isolation, dependency injection ownership, or Session / Repository / Worker / Task-level scopes.
4. You want to build your own adapter layer on top of FlowBus.

If you are building an Android app, the Android adapter is usually the faster starting point:

- Android adapter docs: [library-android README](https://github.com/logan0817/FlowBus/blob/main/library-android/README_EN.md)

## Read These 7 Rules First

1. If you want zero setup, start with `DefaultFlowBus`.
2. If you need DI, multi-instance isolation, or explicit lifecycle ownership, create `FlowBus()` yourself.
3. The default event name is the fully qualified event type name, not the short class name; if one payload type needs multiple channels, use `eventChannel<T>("name")` or an explicit `eventName`.
4. `EventChannel` is better for stable reusable business channels, value-sugar APIs are better for shorter send calls, and plain `eventName` is fine for small local usage.
5. `scoped(...)` gives you a shared named scope view, while `openScope(...)` gives you a scope handle with explicit lifecycle.
6. `post*` / `send()` try immediately and return `Boolean`; if silent failure is not acceptable, switch to `emit*` / `awaitSend*`.
7. `close()`, `removeScope()`, and `removeSticky()` only affect the current store or handle, not old `Flow` references you already hold; `DefaultFlowBus.configure(...)` / `install(...)` must also happen before the first real use.

## Install
[![Maven Central](https://img.shields.io/maven-central/v/io.github.logan0817/flowbus-core.svg?label=Latest%20Release)](https://central.sonatype.com/artifact/io.github.logan0817/flowbus-core)

```gradle
implementation("io.github.logan0817:flowbus-core:1.0.4") // Replace with the latest version shown by the badge above
```

## Start with the shortest path

For a first pass, only remember these 4 patterns:

1. Send on the default singleton: `DefaultFlowBus.post(MyEvent(...))`
2. Subscribe on the default singleton: `DefaultFlowBus.flow<MyEvent>()`
3. If you prefer the “event sends itself” style, use `MyEvent(...).send()`
4. If silent send failure is not acceptable, switch from `post(...)` / `send()` to `emit(...)` / `awaitSend()`

Smallest usable example:

```kotlin
data class SyncFinishedEvent(val taskId: String)

DefaultFlowBus.post(SyncFinishedEvent(taskId = "task-1"))

scope.launch {
    DefaultFlowBus.flow<SyncFinishedEvent>().collect { event ->
        handle(event.taskId)
    }
}
```

Use `send()` if you prefer the “event sends itself” style, and `awaitSend()` if delivery must succeed before continuing. If you want to replace the default singleton configuration, call `DefaultFlowBus.configure(...)` or `install(...)` before the first real use.

## When to switch from `DefaultFlowBus` to `FlowBus()`

Create your own instance when:

- tests, tenants, features, or sessions must be isolated from each other
- lifecycle should be controlled through dependency injection
- you need different bus configurations
- you do not want a process-wide singleton

Example:

```kotlin
val bus = FlowBus()

bus.post(SyncFinishedEvent(taskId = "task-1"))

scope.launch {
    bus.flow<SyncFinishedEvent>().collect { event ->
        handle(event.taskId)
    }
}
```

In that case, all events stay inside this `bus` instance and never mix with `DefaultFlowBus`.

## Named event handles: `eventChannel(...)`

If you do not want raw `eventName` strings scattered across your code, or if the same payload type needs multiple semantic channels, promote the channel itself to a first-class object.

```kotlin
val toastChannel = eventChannel<String>("ui.toast")

toastChannel.post("Saved")

scope.launch {
    toastChannel.flow().collect { message ->
        showToast(message)
    }
}
```

If you created your own bus, you can also target it explicitly:

```kotlin
val bus = FlowBus()
val toastChannel = eventChannel<String>("ui.toast")

toastChannel.postOn(bus, "Saved")

scope.launch {
    toastChannel.flowOn(bus).collect { message ->
        showToast(message)
    }
}
```

### When to use `eventChannel` vs plain `eventName`

Prefer `eventChannel(...)` when:

- the channel is reused in many places
- you want business meaning declared in one place
- you do not want duplicated strings like `"ui.toast"`

Using `eventName` directly is still fine:

```kotlin
bus.post(value = "Saved", eventName = "ui.toast")
bus.flow<String>(eventName = "ui.toast")
```

But for public APIs or long-lived code, `eventChannel` is usually easier to read and harder to mistype.

## How to think about scopes: root, scoped, openScope

### root bus

Every `FlowBus()` already has a root bus. All `bus.post(...)` / `bus.flow<T>()` calls work on that root bus.

Good for:

- global broadcast inside that bus instance
- shared events within the same bus instance

### `scoped(...)`

`scoped("feature-a")` returns a shared named scope view.

Good for:

- isolating events under one scope name
- cases where lifecycle is managed externally
- multiple places that only need to reuse the same named scope

```kotlin
val featureBus = DefaultFlowBus.scoped("feature-a")

featureBus.post(FeatureRefreshEvent(reason = "manual"))

scope.launch {
    featureBus.flow<FeatureRefreshEvent>().collect { event ->
        refresh(event.reason)
    }
}
```

### `openScope(...)`

`openScope(...)` returns a `FlowBusScope`, which has an explicit open / close lifecycle.

Good for:

- Session
- Repository lifecycle
- Worker / Task chains
- business processes that should remove the current scope store and clear its cached values when finished

```kotlin
val syncScope = DefaultFlowBus.openScope("sync-task", closeWhen = scope)

syncScope.post(SyncProgress(percent = 10))

scope.launch {
    syncScope.flow<SyncProgress>().collect { progress ->
        render(progress)
    }
}
```

If you want to finish it manually:

```kotlin
val sessionScope = DefaultFlowBus.openScope("session-42")

sessionScope.post(SessionEvent.Started)
sessionScope.close()
```

Quick distinction:

- `scoped(...)`: shared named bus view, does not own close lifecycle
- `openScope(...)`: explicit lifecycle handle, can call `close()`

One more detail:

- `close()` waits for sends or flow lookups that already started on that scope, then invalidates the handle
- `close()` removes the current scope store
- old `Flow` references you already hold are still not actively cancelled

## Lower-level typed keys

Most of the time, `bus.post(value)` / `bus.flow<T>()` is enough.

If you want to hold a stable key explicitly, or you need Java / lower-level interop, use `EventKey<T>`:

```kotlin
val refreshKey = eventKey<FeatureRefreshEvent>("feature.refresh")
val bus = FlowBus()

bus.post(refreshKey, FeatureRefreshEvent(reason = "manual"))

scope.launch {
    bus.flow(refreshKey).collect { event ->
        refresh(event.reason)
    }
}
```

`EventChannel<T>` can always be converted back to `EventKey<T>`:

```kotlin
val channel = eventChannel<String>("ui.toast")
val key = channel.asEventKey()
```

## API selection matrix

| Need | Recommended API |
| --- | --- |
| Default singleton | `DefaultFlowBus` |
| Multi-instance isolation | `FlowBus()` |
| Shortest send | `post(...)` / `send()` |
| Need to know whether the current call was accepted | `post(...)` / `send()` return `Boolean` |
| Guarantee delivery | `emit(...)` / `awaitSend()` |
| Named channel | `eventChannel<T>("name")` |
| Named shared scope view | `scoped(...)` |
| Explicit lifecycle scope | `openScope(...)` |

## How to choose sending APIs

### Shortest and most convenient

```kotlin
DefaultFlowBus.post(MyEvent(...))
MyEvent(...).send()
```

### Send to a specific bus or scope

```kotlin
val bus = FlowBus()
MyEvent(...).sendOn(bus)

val sessionScope = DefaultFlowBus.openScope("session")
MyEvent(...).sendOn(sessionScope)
```

### Delivery must succeed

```kotlin
scope.launch {
    DefaultFlowBus.emit(MyEvent(...))
}
```

Or:

```kotlin
scope.launch {
    MyEvent(...).awaitSend()
}
```

## `EventChannel`, value-sugar APIs, or plain `eventName`?

If you are deciding between the 3 styles, use this rule of thumb:

| Situation | Better choice |
| --- | --- |
| The channel is reused in many places and you want one stable definition | `eventChannel<T>("name")` |
| You only want the shortest send call at one call site | `event.send()` / `event.sendOn(target)` |
| You already have a stable name locally and do not want another wrapper object | plain `eventName` |
| Public API or long-lived code | prefer `eventChannel<T>("name")` |
| Internal one-off broadcast | value-sugar APIs or direct `post(value)` are both fine |

You can think of them as 3 levels:

1. `eventChannel<T>("name")`: model the channel itself as a stable object. Best for reuse and public business meaning.
2. `event.send()` / `event.sendOn(target)`: shorten the sending call. Best for local call sites.
3. `eventName = "..."`: most direct, but also the easiest way to scatter strings.

Direct value-sugar mapping:

| API | Actual behavior |
| --- | --- |
| `event.send()` | same as `DefaultFlowBus.post(event)` |
| `event.awaitSend()` | same as `DefaultFlowBus.emit(event)` |
| `event.sendSticky()` | same as `DefaultFlowBus.postSticky(event)` |
| `event.awaitSendSticky()` | same as `DefaultFlowBus.emitSticky(event)` |
| `event.sendOn(target)` | same as calling the matching `post(...)` on that target |
| `event.awaitSendOn(target)` | same as calling the matching `emit(...)` on that target |

In short:

1. Value-sugar APIs are only shorter syntax, not a different dispatch model.
2. The default `eventName` is still the fully qualified event type name.
3. Custom `eventName` still must not be blank.
4. Sending to `FlowBus`, `ScopedFlowBus`, or `FlowBusScope` keeps the same behavior as the matching `post*` / `emit*` API.

## How to understand `post`, `emit`, `send`, and `awaitSend`

### `post(...)` / `send()`

Characteristics:

- non-suspending
- shortest to write
- best-effort
- returns `false` when the buffer cannot accept the value immediately

Good for:

- lightweight fire-and-forget notifications
- events where occasional dropping is acceptable

### `emit(...)` / `awaitSend()`

Characteristics:

- suspending
- respects backpressure
- does not silently drop because `tryEmit` failed

Good for:

- more important events
- flows where delivery must complete before continuing

## Sticky events

Sticky events are for “late subscribers should still see the latest value”.

Good examples:

- latest initialization result
- latest configuration snapshot
- latest sync progress

Usually not a good fit for:

- toast
- navigation
- one-time click actions

Also note:

- `clearSticky(...)`: clears replay cache but keeps the sticky flow
- `removeSticky(...)`: removes the current sticky entry from the store, clears its replay cache, and recreates the channel lazily on the next access

If you still hold an old sticky `Flow` reference, it is not actively cancelled. Only the replay cache is cleared.

## How subscriber failures are handled

`flowbus-core` does not silently swallow subscriber failures by default. It separates 3 cases:

1. The event value does not match the expected runtime type: it writes `logger.warn(...)`, then sends `FlowBusErrorPhase.ValueCast` to `errorHandler`.
2. The subscriber callback throws a normal exception: it writes `logger.warn(...)`, then sends `FlowBusErrorPhase.SubscriberCallback` to `errorHandler`.
3. The subscriber callback throws `CancellationException`: it is rethrown and is not swallowed by logging or `errorHandler`.

If you want to control that pipeline, the main extension points are:

- `FlowBusLogger`: decides whether warnings are logged.
- `FlowBusErrorHandler`: decides whether the failure is rethrown, ignored, or forwarded into your own handling logic.
- `FlowBusErrorContext`: tells you which event failed, in which phase, whether it was sticky, whether it came from a scope, and which dispatcher was used.

The two most common strategies are:

1. Default strategy: keep `FlowBusErrorHandler.Rethrow` so subscriber failures surface immediately.
2. Fallback strategy: provide a custom `errorHandler` to report, trace, or downgrade failures before deciding what to do next.

## Default singleton configuration

If you want to replace the default configuration before first use:

```kotlin
DefaultFlowBus.configure(
    FlowBusConfig(
        stickyReplay = 1,
        normalBufferCapacity = 32
    )
)
```

If you want to install your own prebuilt bus:

```kotlin
DefaultFlowBus.install(
    FlowBus(
        config = FlowBusConfig(normalBufferCapacity = 32)
    )
)
```

“Before first use” includes:

- `DefaultFlowBus.raw()`
- `DefaultFlowBus.post(...)` / `emit(...)`
- `DefaultFlowBus.flow(...)` / `stickyFlow(...)`
- `DefaultFlowBus.scoped(...)` / `openScope(...)`

If any of those have already run, later `configure(...)` or `install(...)` calls throw `IllegalStateException`.

## Common behavior boundaries

### Name validation

These names must not be blank:

- `eventName`
- `scopeName`
- `EventChannel` / `EventKey` names

Passing `"   "` throws `IllegalArgumentException` immediately.

### What happens after a scope is closed

`FlowBusScope.close()` does two things:

1. It waits for sends or flow lookups that already started on that scope, then invalidates that `FlowBusScope` handle.
2. It removes the current scope store and clears its cached values.

What it does not do:

1. It does not actively cancel old `Flow` references you already captured.
2. It does not prevent a new store from being created later with the same scope name.

### Error handling and logging

If you use FlowBus subscriber-side error handling, keep these 4 rules in mind:

1. Value type mismatch logs through `logger.warn(...)` first, then goes to `errorHandler`.
2. A normal exception thrown by your subscriber callback also logs first, then goes to `errorHandler`.
3. `CancellationException` is not swallowed. It is rethrown.
4. `FlowBusErrorContext.phase` tells you whether the failure happened during value casting or inside the subscriber callback.

Mapping:

- `ValueCast`: the received value does not match the expected type.
- `SubscriberCallback`: type conversion succeeded, but your callback threw.
- `logger`: records warnings only. It does not decide control flow.
- `errorHandler`: decides whether to rethrow, ignore, or handle the failure differently.

If you do not configure anything, the default is `FlowBusErrorHandler.Rethrow`, which means failures are thrown after logging/context collection.

## AI collaboration note

This project was prepared for its independent open source release and first formal publishing round with AI-assisted collaboration.

AI mainly helped with:

1. code review and edge-case analysis
2. test hardening, README / KDoc updates, and release document preparation
3. repository collaboration files such as issue / PR templates, `SECURITY.md`, and `SUPPORT.md`

This round mainly used GPT-5.4 for AI-assisted collaboration.

Final implementation decisions, validation, and release acceptance are still based on maintainer review and confirmation.

### `clearSticky` vs `removeSticky`

- `clearSticky(...)` only clears replay cache. The sticky channel stays alive.
- `removeSticky(...)` removes the sticky entry from the current store and clears replay cache. The channel is recreated lazily on the next access.
- old sticky `Flow` references you already captured are not actively cancelled

## Module boundary

`flowbus-core` focuses on platform-neutral event bus capabilities: event dispatch, sticky events, named scopes, lifecycle binding, logging, and error handling configuration.

If you need a platform-specific adapter layer, you can build it on top of this module.

If you only want quick Android integration without managing bus instances yourself, start with the Android adapter. If you need multi-instance isolation, dependency injection, or explicit lifecycle control, use raw `FlowBus`.

## Repository links

- current repository: [flowbus-core](https://github.com/logan0817/flowbus-core)
- FlowBus Android repository: [FlowBus](https://github.com/logan0817/FlowBus)
- Android adapter module: [library-android](https://github.com/logan0817/FlowBus/tree/main/library-android)
- demo module: [app](https://github.com/logan0817/FlowBus/tree/main/app)
- support entry: [SUPPORT.md](./SUPPORT.md)
- contributing guide: [CONTRIBUTING.md](./CONTRIBUTING.md)
- security policy: [SECURITY.md](./SECURITY.md)
- code of conduct: [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)
- changelog: [CHANGELOG.md](./CHANGELOG.md)
