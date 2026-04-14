package com.logan.flowbus.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * FlowBus 的核心入口。
 *
 * 它同时管理：
 * - 根总线（全局事件）
 * - 多个 scoped bus（局部事件）
 *
 * 默认情况下，事件按“事件类型”分发；如果同一个类型需要拆成多个通道，
 * 请显式传入 `eventName`，或改用 [EventChannel] / [EventKey]。
 */
class FlowBus(
    val config: FlowBusConfig = FlowBusConfig()
) {
    init {
        require(config.normalBufferCapacity >= 0) { "normalBufferCapacity must be >= 0" }
        require(config.stickyReplay >= 0) { "stickyReplay must be >= 0" }
        require(config.stickyExtraBufferCapacity >= 0) { "stickyExtraBufferCapacity must be >= 0" }
    }

    private val rootStore = FlowBusStore(config)
    private val scopedStores = ConcurrentHashMap<String, FlowBusStore>()

    /**
     * 尝试向根总线发送普通事件。
     *
     * 返回 `false` 表示当前共享流未能立刻接收该事件；如果你需要挂起直到发送成功，
     * 请改用 [emit]。
     */
    fun <T : Any> post(key: EventKey<T>, value: T): Boolean {
        return rootStore.post(key = key, value = value, isSticky = false)
    }

    /**
     * 挂起直到普通事件成功发送到根总线。
     */
    suspend fun <T : Any> emit(key: EventKey<T>, value: T) {
        rootStore.emit(key = key, value = value, isSticky = false)
    }

    /**
     * 尝试向根总线发送粘性事件。
     *
     * 返回 `false` 表示当前共享流未能立刻接收该事件；如果你需要挂起直到发送成功，
     * 请改用 [emitSticky]。
     */
    fun <T : Any> postSticky(key: EventKey<T>, value: T): Boolean {
        return rootStore.post(key = key, value = value, isSticky = true)
    }

    /**
     * 挂起直到粘性事件成功发送到根总线。
     */
    suspend fun <T : Any> emitSticky(key: EventKey<T>, value: T) {
        rootStore.emit(key = key, value = value, isSticky = true)
    }

    /** 返回根总线中的普通事件流。 */
    fun <T : Any> flow(key: EventKey<T>): Flow<T> {
        return rootStore.flow(key = key, isSticky = false)
    }

    /** 返回根总线中的粘性事件流。 */
    fun <T : Any> stickyFlow(key: EventKey<T>): Flow<T> {
        return rootStore.flow(key = key, isSticky = true)
    }

    /** 清空根总线中指定粘性事件的 replay 缓存。 */
    fun <T : Any> clearSticky(key: EventKey<T>) {
        rootStore.clearSticky(key)
    }

    /** 从根总线中移除指定粘性事件的当前 store 条目，并清空现有 replay 缓存。 */
    fun <T : Any> removeSticky(key: EventKey<T>) {
        rootStore.removeSticky(key)
    }

    /**
     * 返回指定名称对应的 scoped bus。
     *
     * 同名 [scopeName] 会复用同一个 scoped store，直到该 store 被 [removeScope] 或
     * [FlowBusScope.close] 移除。
     */
    fun scoped(scopeName: String): ScopedFlowBus {
        require(scopeName.isNotBlank()) { "scopeName must not be blank" }
        return ScopedFlowBus(
            scopeName = scopeName,
            storeProvider = ::getScopedStore,
            removeScopeAction = ::removeScope
        )
    }

    /**
     * 返回指定 [FlowBusOwner] 对应的 scoped bus。
     *
     * 这里只复用 [FlowBusOwner.busScopeName]，不会自动跟随 owner 的生命周期。
     */
    fun scoped(owner: FlowBusOwner): ScopedFlowBus {
        require(owner !is FlowBusScope || !owner.isClosed) {
            "FlowBusScope '${owner.busScopeName}' is already closed."
        }
        return scoped(owner.busScopeName)
    }

    /**
     * 打开一个需要显式关闭的 scope 句柄。
     *
     * 适合 Session、任务链路、Repository、后台 Worker 等需要显式控制生命周期的场景。
     * 关闭后会移除该 scope 对应的内部 store，并阻止该句柄继续被误用。
     * 已经拿到手的旧 [Flow] 引用不会被主动 cancel，但后续通过同名 scope 获取的通道会重新创建。
     */
    fun openScope(scopeName: String): FlowBusScope {
        require(scopeName.isNotBlank()) { "scopeName must not be blank" }
        return FlowBusScope(
            busScopeName = scopeName,
            scopedBus = scoped(scopeName),
            closeAction = ::removeScope
        )
    }

    /**
     * 打开一个会跟随 [Job] 生命周期自动关闭的 scope。
     */
    fun openScope(scopeName: String, closeWhen: Job): FlowBusScope {
        return openScope(scopeName).bindTo(closeWhen)
    }

    /**
     * 打开一个会跟随 [CoroutineScope] 生命周期自动关闭的 scope。
     */
    fun openScope(scopeName: String, closeWhen: CoroutineScope): FlowBusScope {
        return openScope(scopeName).bindTo(closeWhen)
    }

    /**
     * 基于已有 [FlowBusOwner] 的 [FlowBusOwner.busScopeName] 打开一个需要显式关闭的 scope 句柄。
     *
     * 这里只会复用名称，不会自动跟随 owner 的生命周期。
     */
    fun openScope(owner: FlowBusOwner): FlowBusScope {
        require(owner !is FlowBusScope || !owner.isClosed) {
            "FlowBusScope '${owner.busScopeName}' is already closed."
        }
        return openScope(owner.busScopeName)
    }

    /**
     * 基于已有 [FlowBusOwner] 的 [FlowBusOwner.busScopeName] 打开一个会跟随 [Job] 生命周期自动关闭的 scope。
     */
    fun openScope(owner: FlowBusOwner, closeWhen: Job): FlowBusScope {
        return openScope(owner).bindTo(closeWhen)
    }

    /**
     * 基于已有 [FlowBusOwner] 的 [FlowBusOwner.busScopeName] 打开一个会跟随 [CoroutineScope] 生命周期自动关闭的 scope。
     */
    fun openScope(owner: FlowBusOwner, closeWhen: CoroutineScope): FlowBusScope {
        return openScope(owner).bindTo(closeWhen)
    }

    /**
     * 移除指定名称对应 scope 的当前内部 store，并清空其中缓存。
     *
     * 后续再次访问同名 scope 时，会按需创建新的 store。
     * 已经拿到手的旧 [Flow] 引用不会被主动 cancel。
     */
    fun removeScope(scopeName: String) {
        require(scopeName.isNotBlank()) { "scopeName must not be blank" }
        scopedStores.remove(scopeName)?.clearAll()
    }

    /**
     * 移除指定 [FlowBusOwner] 对应 scope 的当前内部 store，并清空其中缓存。
     */
    fun removeScope(owner: FlowBusOwner) {
        removeScope(owner.busScopeName)
    }

    internal fun hasScope(scopeName: String): Boolean {
        return scopedStores.containsKey(scopeName)
    }

    internal fun hasEventFlow(eventName: String, isSticky: Boolean): Boolean {
        return rootStore.hasEventFlow(eventName = eventName, isSticky = isSticky)
    }

    internal fun stickyReplayCache(eventName: String): List<Any> {
        return rootStore.stickyReplayCache(eventName)
    }

    private fun getScopedStore(scopeName: String): FlowBusStore {
        require(scopeName.isNotBlank()) { "scopeName must not be blank" }
        return scopedStores.computeIfAbsent(scopeName) { FlowBusStore(config) }
    }
}
