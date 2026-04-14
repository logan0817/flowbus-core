英文文档 [English Document](./README_EN.md)

# flowbus-core

`flowbus-core` 是 FlowBus 的平台无关核心模块。

它基于 Kotlin Coroutines 与 Flow，提供：

- 类型安全事件分发
- sticky event（后订阅者也能读到最近一次值）
- root bus / scoped bus 隔离
- 显式生命周期控制的 `FlowBusScope`
- 命名事件句柄 `EventChannel<T>`
- 更简洁的 Kotlin 风格语法糖，如 `event.send()`

如果你不是 Android 项目，或者你需要自己管理 bus 实例、scope 生命周期、多实例隔离，这个模块就是主入口。

## 适合场景

如果你符合下面任意一种情况，就可以直接从 `flowbus-core` 开始：

- 你不是 Android 项目
- 你想自己创建 `FlowBus()`，而不是依赖默认单例
- 你需要多实例隔离，或者要把 bus 生命周期交给依赖注入
- 你需要 Session / Repository / Worker / Task 级别的 scope

如果你是 Android 项目，通常先看 Android 适配模块会更快：

- Android 模块文档：[library-android README](https://github.com/logan0817/FlowBus/blob/main/library-android/README.md)

## 先看这 7 句

1. 想开箱即用，就先用 `DefaultFlowBus`。
2. 需要依赖注入、多实例隔离或明确生命周期，就自己创建 `FlowBus()`。
3. 默认事件名不是类短名，而是事件类型完整类名；同一类型要拆多个通道时，用 `eventChannel<T>("name")` 或显式 `eventName`。
4. `EventChannel` 适合长期复用和公开语义，值糖 API 适合把发送调用写短，直接写 `eventName` 适合局部少量使用。
5. `scoped(...)` 是共享命名视图，`openScope(...)` 是带显式生命周期的 scope 句柄。
6. `post*` / `send()` 是立即尝试写入并返回 `Boolean`；不能接受静默失败时，改用 `emit*` / `awaitSend*`。
7. `close()`、`removeScope()`、`removeSticky()` 只影响当前 store 或当前句柄，不会主动 cancel 你已经拿到手的旧 `Flow`；`DefaultFlowBus.configure(...)` / `install(...)` 也必须在第一次真正使用前完成。

## 安装
[![Maven Central](https://img.shields.io/maven-central/v/io.github.logan0817/flowbus-core.svg?label=Latest%20Release)](https://central.sonatype.com/artifact/io.github.logan0817/flowbus-core)

```gradle
implementation("io.github.logan0817:flowbus-core:1.0.4")  // 替换为上方徽章显示的最新版本
```

## 先看最短路径

第一次使用时，只记 3 件事就够了：

1. 默认单例发送：`DefaultFlowBus.post(MyEvent(...))`
2. 默认单例订阅：`DefaultFlowBus.flow<MyEvent>()`
3. 不能接受静默失败时：把 `post(...)` / `send()` 换成 `emit(...)` / `awaitSend()`

最短可用示例：

```kotlin
data class SyncFinishedEvent(val taskId: String)

DefaultFlowBus.post(SyncFinishedEvent(taskId = "task-1"))

scope.launch {
    DefaultFlowBus.flow<SyncFinishedEvent>().collect { event ->
        handle(event.taskId)
    }
}
```

对象式发送就用 `send()`；必须保证写入成功就用 `awaitSend()`。如果你准备替换默认单例配置，记得先调用 `DefaultFlowBus.configure(...)` 或 `install(...)`，再第一次真正使用它。

## 什么时候该从 `DefaultFlowBus` 切到 `FlowBus()`

以下情况建议自己创建实例：

- 你希望测试、功能模块、租户、会话之间完全隔离
- 你想通过依赖注入控制 bus 生命周期
- 你需要不同配置的 bus
- 你不想使用默认全局单例

示例：

```kotlin
val bus = FlowBus()

bus.post(SyncFinishedEvent(taskId = "task-1"))

scope.launch {
    bus.flow<SyncFinishedEvent>().collect { event ->
        handle(event.taskId)
    }
}
```

这时所有事件都只在这个 `bus` 实例里流动，不会和 `DefaultFlowBus` 混在一起。

## 命名事件句柄：`eventChannel(...)`

当你不想把 `eventName` 字符串散落在业务代码里，或者同一个类型需要多个语义不同的通道时，推荐把通道本身抽成一个对象。

```kotlin
val toastChannel = eventChannel<String>("ui.toast")

toastChannel.post("Saved")

scope.launch {
    toastChannel.flow().collect { message ->
        showToast(message)
    }
}
```

如果你自己创建了 bus，也可以显式指定目标：

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

### 什么时候用 `eventChannel`，什么时候直接写 `eventName`

优先推荐 `eventChannel(...)` 的情况：

- 这个通道会被复用很多次
- 你想把业务语义集中定义
- 你不想在多个文件里复制 `"ui.toast"` 这样的字符串

直接传 `eventName` 也没问题：

```kotlin
bus.post(value = "Saved", eventName = "ui.toast")
bus.flow<String>(eventName = "ui.toast")
```

但如果这是公开 API 或长期维护代码，`eventChannel` 的可读性通常更好。

## 作用域怎么理解：root、scoped、openScope

### root bus

`FlowBus()` 默认就有一个根总线，所有 `bus.post(...)` / `bus.flow<T>()` 都是在 root bus 上工作。

适合：

- 全局广播
- 这个 bus 实例内部的公共事件

### `scoped(...)`

`scoped("feature-a")` 返回的是一个共享作用域视图。

适合：

- 你只想按名字把事件隔离到某个 scope
- 生命周期由外部统一管理
- 多处代码只需要拿同一个命名 scope 来收发

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

`openScope(...)` 返回的是 `FlowBusScope`，它有明确的“打开 / 关闭”生命周期。

适合：

- Session
- Repository 生命周期
- Worker / Task 链路
- 一段业务流程结束后要移除当前 scope 对应的内部 store，并清掉其中缓存

```kotlin
val syncScope = DefaultFlowBus.openScope("sync-task", closeWhen = scope)

syncScope.post(SyncProgress(percent = 10))

scope.launch {
    syncScope.flow<SyncProgress>().collect { progress ->
        render(progress)
    }
}
```

如果你想手动结束：

```kotlin
val sessionScope = DefaultFlowBus.openScope("session-42")

sessionScope.post(SessionEvent.Started)
sessionScope.close()
```

简单区分：

- `scoped(...)`：共享命名 bus 视图，不负责 close
- `openScope(...)`：带显式生命周期的 scope 句柄，可以 `close()`

补一句：

- `close()` 会等待当前 scope 上已经开始的发送 / 取流操作结束，再让句柄失效
- `close()` 会移除当前 scope 对应的内部 store
- 已经拿到手的旧 `Flow` 引用不会被主动 cancel；之后再通过同名 scope 访问时，会按需创建新的通道

## 如果你还想使用更底层的 typed key

大多数时候你可以直接用 `bus.post(value)` / `bus.flow<T>()`。

但如果你想显式持有一个稳定 key，或者要和 Java / 底层 API 互操作，可以用 `EventKey<T>`：

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

`EventChannel<T>` 可以随时转回 `EventKey<T>`：

```kotlin
val channel = eventChannel<String>("ui.toast")
val key = channel.asEventKey()
```

## API 选择矩阵

| 需求 | 推荐 API |
| --- | --- |
| 默认单例 | `DefaultFlowBus` |
| 多实例隔离 | `FlowBus()` |
| 想最短发送 | `post(...)` / `send()` |
| 需要返回是否接收 | `post(...)` / `send()` 的 `Boolean` |
| 保证写入成功 | `emit(...)` / `awaitSend()` |
| 命名通道 | `eventChannel<T>("name")` |
| 命名 scope 视图 | `scoped(...)` |
| 显式生命周期 scope | `openScope(...)` |

## 发送 API 怎么选

### 想最短、最顺手

```kotlin
DefaultFlowBus.post(MyEvent(...))
MyEvent(...).send()
```

### 想发到特定 bus / scope

```kotlin
val bus = FlowBus()
MyEvent(...).sendOn(bus)

val sessionScope = DefaultFlowBus.openScope("session")
MyEvent(...).sendOn(sessionScope)
```

### 必须保证写入成功

```kotlin
scope.launch {
    DefaultFlowBus.emit(MyEvent(...))
}
```

或者：

```kotlin
scope.launch {
    MyEvent(...).awaitSend()
}
```

## `EventChannel`、值糖 API、`eventName` 怎么选

如果你在这 3 种写法里犹豫，可以直接按下面的规则选：

| 场景 | 更推荐的写法 |
| --- | --- |
| 这个通道会被很多地方复用，想把名字集中定义 | `eventChannel<T>("name")` |
| 只是临时发一次，想写得最短 | `event.send()` / `event.sendOn(target)` |
| 已经有稳定名字，但不想单独建 channel 对象 | 直接传 `eventName` |
| 面向公开 API 或长期维护代码 | 优先 `eventChannel<T>("name")` |
| 只是内部简单广播 | 值糖 API 或直接 `post(value)` 都可以 |

可以把它们理解成 3 个层级：

1. `eventChannel<T>("name")`：把“通道”本身抽成一个稳定对象，最适合长期复用。
2. `event.send()` / `event.sendOn(target)`：把“发送动作”写短，最适合一次性调用点。
3. `eventName = "..."`：最直接，但也最容易把字符串散落在代码里。

值糖 API 的直接对应关系：

| 写法 | 实际语义 |
| --- | --- |
| `event.send()` | 等价于 `DefaultFlowBus.post(event)` |
| `event.awaitSend()` | 等价于 `DefaultFlowBus.emit(event)` |
| `event.sendSticky()` | 等价于 `DefaultFlowBus.postSticky(event)` |
| `event.awaitSendSticky()` | 等价于 `DefaultFlowBus.emitSticky(event)` |
| `event.sendOn(target)` | 等价于对这个目标调用对应的 `post(...)` |
| `event.awaitSendOn(target)` | 等价于对这个目标调用对应的 `emit(...)` |

补一句：

1. 这些值糖 API 只是更短的写法，不是另一套分发规则。
2. 默认 `eventName` 仍然是事件类型完整类名。
3. 自定义 `eventName` 仍然不能为空白字符串。
4. 发到 `FlowBus`、`ScopedFlowBus`、`FlowBusScope` 时，行为边界和对应的 `post*` / `emit*` 完全一致。

## `post`、`emit`、`send`、`awaitSend` 怎么理解

### `post(...)` / `send()`

特点：

- 非挂起
- 手感最轻
- best-effort
- 缓冲无法立刻接收时会返回 `false`

适合：

- 普通广播
- 可以接受极端情况下发送失败的事件

### `emit(...)` / `awaitSend()`

特点：

- 挂起函数
- 遵循背压
- 会等待直到成功写入

适合：

- 关键链路通知
- 不希望事件默默丢掉

## sticky event 什么时候该用

sticky event 会保存最近一次值，后来订阅的人也能立刻读到。

适合：

- 最近一次初始化结果
- 最近一次会话信息
- 页面恢复后仍然要拿到的最新配置

示例：

```kotlin
data class SessionReadyEvent(val userId: Long)

DefaultFlowBus.postSticky(SessionReadyEvent(userId = 42L))

scope.launch {
    DefaultFlowBus.stickyFlow<SessionReadyEvent>().collect { event ->
        bindSession(event.userId)
    }
}
```

清理方式：

```kotlin
DefaultFlowBus.clearSticky<SessionReadyEvent>()
DefaultFlowBus.removeSticky<SessionReadyEvent>()
```

简单理解：

- `clearSticky`：清 replay 缓存，但保留通道
- `removeSticky`：移除当前 store 中这个 sticky 条目，并清掉现有 replay 缓存；后续再次访问会按需新建通道

如果你手里还保留着旧的 sticky `Flow` 引用，它不会被主动 cancel；只是 replay 缓存会被清掉。

## 订阅异常怎么处理

`flowbus-core` 默认不会帮你把订阅错误“悄悄吃掉再当没事发生”。它区分 3 类情况：

1. 事件值和期望类型对不上：记一条 `logger.warn(...)`，然后把 `FlowBusErrorPhase.ValueCast` 交给 `errorHandler`。
2. 订阅回调自己抛普通异常：记一条 `logger.warn(...)`，然后把 `FlowBusErrorPhase.SubscriberCallback` 交给 `errorHandler`。
3. 订阅回调抛 `CancellationException`：继续向外抛，不会被日志或 `errorHandler` 吞掉。

如果你需要接管这条链路，重点看这 3 个入口：

- `FlowBusLogger`：决定是否记录告警日志。
- `FlowBusErrorHandler`：决定收到错误后是继续抛出、忽略，还是转成你自己的处理逻辑。
- `FlowBusErrorContext`：告诉你这是哪个事件、哪个阶段、是否 sticky、是否带 scope、当时用了哪个 dispatcher。

最常见的两种策略：

1. 默认策略：用 `FlowBusErrorHandler.Rethrow`，一旦订阅失败就直接暴露问题。
2. 兜底策略：自定义 `errorHandler`，把错误上报或打点，再决定是否继续。

## 事件类型怎么设计更清楚

推荐顺序：

1. 单个动作：`data class` / `data object`
2. 同一业务域多个动作：`sealed interface` / `sealed class`
3. 简单值类型只在语义已经足够明确时再使用

### 单个动作

```kotlin
data object RefreshEvent
data class ShowToastEvent(val message: String)
```

### 同一业务域多个动作

```kotlin
sealed interface SyncEvent {
    data object Start : SyncEvent
    data class Progress(val percent: Int) : SyncEvent
    data object Finish : SyncEvent
}
```

发送时要显式指定父类型，这样这些子事件才会进入同一个通道：

```kotlin
DefaultFlowBus.post<SyncEvent>(SyncEvent.Start)
DefaultFlowBus.post<SyncEvent>(SyncEvent.Progress(percent = 50))

scope.launch {
    DefaultFlowBus.flow<SyncEvent>().collect { event ->
        when (event) {
            SyncEvent.Start -> onStart()
            is SyncEvent.Progress -> onProgress(event.percent)
            SyncEvent.Finish -> onFinish()
        }
    }
}
```

## 默认单例如何配置

如果你想在首次使用前替换默认配置：

```kotlin
DefaultFlowBus.configure(
    FlowBusConfig(
        stickyReplay = 1,
        normalBufferCapacity = 32
    )
)
```

如果你想安装自己创建的 bus：

```kotlin
DefaultFlowBus.install(
    FlowBus(
        config = FlowBusConfig(normalBufferCapacity = 32)
    )
)
```

注意：配置或安装要在第一次真正使用 `DefaultFlowBus` 之前完成。

这里的“第一次真正使用”包括：

- `DefaultFlowBus.raw()`
- `DefaultFlowBus.post(...)` / `emit(...)`
- `DefaultFlowBus.flow(...)` / `stickyFlow(...)`
- `DefaultFlowBus.scoped(...)` / `openScope(...)`

如果这些入口已经调用过，再执行 `configure(...)` 或 `install(...)` 会抛 `IllegalStateException`。

## 如果你只想记最短用法，看这里

### 默认单例

```kotlin
DefaultFlowBus.post(MyEvent(...))
scope.launch { DefaultFlowBus.flow<MyEvent>().collect { handle(it) } }
```

### 对象式发送

```kotlin
MyEvent(...).send()
scope.launch { DefaultFlowBus.flow<MyEvent>().collect { handle(it) } }
```

### 命名 channel

```kotlin
val toastChannel = eventChannel<String>("ui.toast")
toastChannel.post("Saved")
scope.launch { toastChannel.flow().collect { showToast(it) } }
```

### 显式 scope

```kotlin
val taskScope = DefaultFlowBus.openScope("task", closeWhen = scope)
taskScope.post(TaskProgress(percent = 10))
scope.launch { taskScope.flow<TaskProgress>().collect { render(it) } }
```

## 常见边界说明

### 名称校验

以下名称都不能为空白字符串：

- `eventName`
- `scopeName`
- `EventChannel` / `EventKey` 的 `name`

如果你传入 `"   "` 这类值，API 会直接抛 `IllegalArgumentException`，不会帮你兜底成默认值。

### scope 关闭后会发生什么

`FlowBusScope.close()` 做的是两件事：

1. 等当前 scope 上已经开始的发送 / 取流操作结束，再让这个 `FlowBusScope` 句柄失效。
2. 移除当前 scope 对应的内部 store，并清掉其中缓存。

它不会做的事：

1. 不会主动 cancel 你之前已经拿到手的旧 `Flow` 引用。
2. 不会阻止你后续再用同名 scope 重新创建新的 store。

### `clearSticky` 和 `removeSticky` 的区别

- `clearSticky(...)`：只清 replay 缓存，sticky 通道本身还在。
- `removeSticky(...)`：移除当前 store 里的 sticky 条目，并清掉现有 replay 缓存；下次再访问时会按需新建。

如果你已经拿到了旧的 sticky `Flow` 引用，它不会被主动 cancel。

### 错误处理与日志

如果你使用订阅侧的错误处理能力，核心规则可以直接记这 4 句：

1. 类型不匹配会先记一条 `logger.warn(...)`，再走 `errorHandler`。
2. 订阅回调自己抛普通异常，也会先记日志，再走 `errorHandler`。
3. `CancellationException` 不会被吞掉，会继续往外抛。
4. `FlowBusErrorContext.phase` 用来区分到底是“值类型不匹配”还是“回调执行失败”。

对应关系：

- `ValueCast`：收到的值和期望类型对不上。
- `SubscriberCallback`：类型已经过了，但你的回调代码自己抛异常。
- `logger`：负责记录告警，不决定是否继续执行。
- `errorHandler`：负责决定异常是继续向外抛，还是改成忽略 / 自定义处理。

如果你什么都不配，默认行为是 `FlowBusErrorHandler.Rethrow`，也就是收到异常后继续抛出。

## AI 协作说明

本项目在这次独立开源整理和首发发布准备过程中，结合了 AI 辅助协作完成。

AI 主要参与了下面几类工作：

1. 代码缺陷排查与边界条件梳理。
2. 测试补强、README / KDoc 补充与发布文档整理。
3. 仓库级协作文件补齐，例如 issue / PR 模板、`SECURITY.md`、`SUPPORT.md`。

本轮整理主要结合 GPT-5.4 进行 AI 辅助协作。

最终实现、取舍判断、验证执行和发布内容收口，仍然以仓库维护者的人工审查与确认结果为准。

## 仓库链接

- 当前仓库主页：[flowbus-core](https://github.com/logan0817/flowbus-core)
- FlowBus Android 模块仓库：[FlowBus](https://github.com/logan0817/FlowBus)
- Android 模块文档：[library-android README](https://github.com/logan0817/FlowBus/blob/main/library-android/README.md)
- Demo 模块：[app](https://github.com/logan0817/FlowBus/tree/main/app)
- 使用与提问入口：[SUPPORT.md](./SUPPORT.md)
- 贡献指南：[CONTRIBUTING.md](./CONTRIBUTING.md)
- 安全策略：[SECURITY.md](./SECURITY.md)
- 行为准则：[CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)
- 变更记录：[CHANGELOG.md](./CHANGELOG.md)
