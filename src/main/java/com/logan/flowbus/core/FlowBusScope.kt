package com.logan.flowbus.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一个带显式生命周期的 FlowBus scope 句柄。
 *
 * 它既是 [FlowBusOwner]，也直接提供 scoped bus 的收发能力，适合：
 * - session scope
 * - repository scope
 * - worker scope
 * - task scope
 *
 * 调用 [close] 后：
 * - 当前 scope 对应的内部 store 会被移除，并清空其中缓存
 * - 该句柄不允许继续使用
 * - 已经拿到手的旧 [Flow] 引用不会被主动 cancel
 *
 * 如果你只想复用同一个命名 scope，而不想自己管理 close，优先使用 [ScopedFlowBus]。
 */
class FlowBusScope internal constructor(
    override val busScopeName: String,
    private val scopedBus: ScopedFlowBus,
    private val closeAction: (String) -> Unit
) : FlowBusOwner, AutoCloseable {
    private val lifecycleBindings = CopyOnWriteArrayList<DisposableHandle>()
    private val operationLock = Object()
    private var isClosing: Boolean = false
    private var inFlightOperationCount: Int = 0

    @Volatile
    /** 当前 scope 是否已经关闭。 */
    var isClosed: Boolean = false
        private set

    /** 与 [FlowBusOwner.busScopeName] 相同的别名，便于阅读。 */
    val scopeName: String
        get() = busScopeName

    /** 尝试向当前 scope 发送普通事件。 */
    fun <T : Any> post(key: EventKey<T>, value: T): Boolean {
        return withOpenOperation {
            scopedBus.post(key, value)
        }
    }

    /** 挂起直到普通事件成功发送到当前 scope。 */
    suspend fun <T : Any> emit(key: EventKey<T>, value: T) {
        withOpenOperationSuspend {
            scopedBus.emit(key, value)
        }
    }

    /** 尝试向当前 scope 发送粘性事件。 */
    fun <T : Any> postSticky(key: EventKey<T>, value: T): Boolean {
        return withOpenOperation {
            scopedBus.postSticky(key, value)
        }
    }

    /** 挂起直到粘性事件成功发送到当前 scope。 */
    suspend fun <T : Any> emitSticky(key: EventKey<T>, value: T) {
        withOpenOperationSuspend {
            scopedBus.emitSticky(key, value)
        }
    }

    /** 返回当前 scope 中指定普通事件对应的 [Flow]。 */
    fun <T : Any> flow(key: EventKey<T>): Flow<T> {
        return withOpenOperation {
            scopedBus.flow(key)
        }
    }

    /** 返回当前 scope 中指定粘性事件对应的 [Flow]。 */
    fun <T : Any> stickyFlow(key: EventKey<T>): Flow<T> {
        return withOpenOperation {
            scopedBus.stickyFlow(key)
        }
    }

    /** 清空当前 scope 中指定粘性事件的 replay 缓存。 */
    fun <T : Any> clearSticky(key: EventKey<T>) {
        withOpenOperation {
            scopedBus.clearSticky(key)
        }
    }

    /** 从当前 scope 的当前 store 中移除指定粘性事件，并清空现有 replay 缓存。 */
    fun <T : Any> removeSticky(key: EventKey<T>) {
        withOpenOperation {
            scopedBus.removeSticky(key)
        }
    }

    /**
     * 将当前 scope 绑定到指定 [Job] 生命周期。
     *
     * 当 [Job] 完成或取消时，当前 scope 会自动 [close]。
     */
    fun bindTo(job: Job): FlowBusScope {
        ensureOpen()
        val handle = job.invokeOnCompletion {
            close()
        }
        lifecycleBindings += handle

        if (isClosed) {
            handle.dispose()
        }

        return this
    }

    /**
     * 将当前 scope 绑定到指定 [CoroutineScope] 的生命周期。
     *
     * 要求该 [CoroutineScope] 的上下文中存在 [Job]。
     */
    fun bindTo(scope: CoroutineScope): FlowBusScope {
        val job = requireNotNull(scope.coroutineContext[Job]) {
            "CoroutineScope must contain a Job to bind FlowBusScope '$scopeName'."
        }
        return bindTo(job)
    }

    /**
     * 关闭当前 scope，并清理该 scope 下的事件流与缓存。
     *
     * 关闭前会先等待当前句柄上已经开始的发送 / 订阅获取动作结束，避免 close 与在途操作互相打架。
     */
    override fun close() {
        synchronized(operationLock) {
            if (isClosing || isClosed) return
            isClosing = true
            while (inFlightOperationCount > 0) {
                operationLock.wait()
            }
            isClosed = true
        }
        lifecycleBindings.forEach { it.dispose() }
        lifecycleBindings.clear()
        closeAction(scopeName)
    }

    private fun ensureOpen() {
        synchronized(operationLock) {
            check(!isClosing && !isClosed) { "FlowBusScope '$scopeName' is already closed." }
        }
    }

    private inline fun <T> withOpenOperation(block: () -> T): T {
        beginOperation()
        return try {
            block()
        } finally {
            endOperation()
        }
    }

    private suspend inline fun <T> withOpenOperationSuspend(crossinline block: suspend () -> T): T {
        beginOperation()
        return try {
            block()
        } finally {
            endOperation()
        }
    }

    private fun beginOperation() {
        synchronized(operationLock) {
            check(!isClosing && !isClosed) { "FlowBusScope '$scopeName' is already closed." }
            inFlightOperationCount++
        }
    }

    private fun endOperation() {
        synchronized(operationLock) {
            inFlightOperationCount--
            if (inFlightOperationCount == 0) {
                operationLock.notifyAll()
            }
        }
    }
}
