/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.flow

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.internal.*
import kotlin.coroutines.*
import kotlin.jvm.*
import kotlin.native.concurrent.*

public interface SharedFlow<out T> : Flow<T> {
    public val replayCache: List<T>
}

public interface MutableSharedFlow<T> : SharedFlow<T>, FlowCollector<T> {
    public fun tryEmit(value: T): Boolean
    public val subscriptionCount: StateFlow<Int>
    public fun resetBuffer()
}

/**
 * Creates a [MutableSharedFlow] with the given configuration parameters.
 */
@Suppress("FunctionName", "UNCHECKED_CAST")
@ExperimentalCoroutinesApi
public fun <T> MutableSharedFlow(
    bufferCapacity: Int,
    replayCapacity: Int = bufferCapacity,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    initialValue: T = NO_VALUE as T
): MutableSharedFlow<T> =
    SharedFlowImpl(
        bufferCapacity,
        replayCapacity,
        onBufferOverflow,
        initialValue
    )

// ------------------------------------ Implementation ------------------------------------

private class SharedFlowSlot : AbstractSharedFlowSlot<SharedFlowImpl<*>>() {
    @JvmField
    var index = -1L // current "to-be-emitted" index, -1 means the slot is free now

    @JvmField
    var cont: Continuation<Unit>? = null // collector waiting for new value

    override fun allocateLocked(flow: SharedFlowImpl<*>): Boolean {
        if (index >= 0) return false // not free
        index = flow.updateNewCollectorIndexLocked()
        return true
    }

    override fun freeLocked(flow: SharedFlowImpl<*>): List<Continuation<Unit>>? {
        assert { index >= 0 }
        val oldIndex = index
        index = -1L
        cont = null // cleanup continuation reference
        return flow.updateCollectorIndexLocked(oldIndex)
    }
}

private class SharedFlowImpl<T>(
    private val bufferCapacity: Int,
    private val replayCapacity: Int,
    private val onBufferOverflow: BufferOverflow,
    private val initialValue: Any?
) : AbstractSharedFlow<SharedFlowSlot>(), MutableSharedFlow<T> {
    init {
        require(replayCapacity >= 0) {
            "replayCapacity($replayCapacity) cannot be negative"
        }
        require(bufferCapacity >= replayCapacity) {
            "bufferCapacity($bufferCapacity) cannot be smaller than replayCapacity($replayCapacity)"
        }
        require(replayCapacity > 0 || initialValue === NO_VALUE) {
            "replayCapacity($replayCapacity) must positive  with initialValue($initialValue)"
        }
        require(bufferCapacity > 0 || onBufferOverflow == BufferOverflow.SUSPEND) {
            "bufferCapacity($bufferCapacity) must positive with onBufferOverflow($onBufferOverflow)"
        }
    }

    /*
        Logical structure of the buffer

                  buffered values
             /-----------------------\
                          replayCache      queued emitters
                          /----------\/----------------------\
         +---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
         |   | 1 | 2 | 3 | 4 | 5 | 6 | E | E | E | E | E | E |   |   |   |
         +---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
               ^           ^           ^                      ^
               |           |           |                      |
              head         |      head + bufferCapacity  head + size
               |           |           |
     index of the slowest  |    index of the fastest
      possible collector   |     possible collector
               |           |
               |     replayIndex - new collector's index
               \---------------------- /
          range of possible minCollectorIndex
     */

    private var buffer: Array<Any?>? = null // allocated when needed, allocated size always power of two
    private var head = 0L // long, never overflows, used to track collectors
    private var size = 0
    private var minCollectorIndex = 0L

    init {
        if (initialValue !== NO_VALUE) enqueueLocked(initialValue)
    }

    override val replayCache: List<T>
        get() = synchronized(this) {
            val replaySize = replaySizeLocked
            if (replaySize == 0) return emptyList()
            val result = ArrayList<T>(replaySize)
            val buffer = buffer!! // must be allocated, because size > 0
            val replayIndex = replayIndexLocked
            @Suppress("UNCHECKED_CAST")
            for (i in 0 until replaySize) result += buffer.getBufferAt(replayIndex + i) as T
            result
        }


    private val replaySizeLocked: Int
        get() = minOf(replayCapacity, size)

    private val bufferSizeLocked: Int
        get() = minOf(bufferCapacity, size)

    private val replayIndexLocked: Long
        get() = head + bufferSizeLocked - replaySizeLocked

    @Suppress("UNCHECKED_CAST")
    override suspend fun collect(collector: FlowCollector<T>) {
        val slot = allocateSlot()
        try {
            if (collector is SubscribedFlowCollector) collector.onSubscription()
            val collectorJob = currentCoroutineContext()[Job]
            while (true) {
                var newValue: Any?
                while (true) {
                    newValue = tryTakeValue(slot) // attempt no-suspend fast path first
                    if (newValue !== NO_VALUE) break
                    awaitValue(slot) // await signal that the new value is available
                }
                collectorJob?.ensureActive()
                collector.emit(newValue as T)
            }
        } finally {
            freeSlot(slot)
        }
    }

    override fun tryEmit(value: T): Boolean {
        var resumeList: List<Continuation<Unit>>? = null
        val emitted = synchronized(this) {
            if (tryEmitLocked(value)) {
                resumeList = findSlotsToResumeLocked()
                true
            } else {
                false
            }
        }
        resumeList?.forEach { it.resume(Unit) }
        return emitted
    }

    override suspend fun emit(value: T) {
        if (tryEmit(value)) return // fast-path
        emitSuspend(value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryEmitLocked(value: T): Boolean {
        // Fast path without collectors
        if (nCollectors == 0) return tryEmitNoCollectorsLocked(value) // always returns true
        // With collectors we'll have to buffer
        assert { minCollectorIndex >= head }
        // cannot emit now if buffer is full && blocked by a slow collectors
        if (size >= bufferCapacity && minCollectorIndex == head) {
            when (onBufferOverflow) {
                BufferOverflow.SUSPEND -> return false // will suspend
                BufferOverflow.DROP_LATEST -> return true // just drop incoming
                BufferOverflow.KEEP_LATEST -> {} // force enqueue & drop oldest instead
            }
        }
        enqueueLocked(value)
        // drop oldest from the buffer if it became more than bufferCapacity
        if (size > bufferCapacity) dropOldestLocked()
        return true
    }

    private fun tryEmitNoCollectorsLocked(value: T): Boolean {
        assert { nCollectors == 0 }
        if (replayCapacity == 0) return true // no need to replay, just forget it now
        enqueueLocked(value) // enqueue to replayCache
        // drop oldest from the buffer if it became more than replayCapacity
        if (size > replayCapacity) dropOldestLocked()
        minCollectorIndex = head + size // a default value (max allowed)
        return true
    }

    private fun dropOldestLocked() {
        buffer!!.setBufferAt(head, null)
        head++
        size--
        if (head > minCollectorIndex) correctCollectorIndexesOnDropOldest()
    }

    private fun correctCollectorIndexesOnDropOldest() {
        assert { head > minCollectorIndex }
        forEachSlotLocked loop@{ slot ->
            if (slot.index !in 0 until head) return@loop
            slot.index = head // force move it up (this collector was too slow and missed the value at its index)
        }
        minCollectorIndex = head
    }

    private fun enqueueLocked(value: Any?) {
        val buffer = when (val curBuffer = buffer) {
            null -> growBuffer(null, 2)
            else -> if (curBuffer.size <= size) growBuffer(curBuffer, curBuffer.size * 2) else curBuffer
        }
        buffer.setBufferAt(head + size, value)
        size++
    }

    private fun growBuffer(curBuffer: Array<Any?>?, newSize: Int): Array<Any?> {
        check(newSize > 0) { "Buffer size overflow" }
        val newBuffer = arrayOfNulls<Any?>(newSize).also { buffer = it }
        if (curBuffer == null) return newBuffer
        for (i in 0 until size) {
            newBuffer.setBufferAt(head + i, curBuffer.getBufferAt(head + i))
        }
        return newBuffer
    }

    private suspend fun emitSuspend(value: T) = suspendCancellableCoroutine<Unit> sc@{ cont ->
        var resumeList: List<Continuation<Unit>>? = null
        val emitter = synchronized(this) lock@{
            // recheck buffer under lock again (make sure it is really full)
            if (tryEmitLocked(value)) {
                cont.resume(Unit)
                resumeList = findSlotsToResumeLocked()
                return@lock null
            }
            // add suspended emitter to the buffer
            Emitter(this, head + size, value, cont).also {
                enqueueLocked(it)
                // synchronous shared flow might rendezvous with waiting emitter
                if (bufferCapacity == 0) resumeList = findSlotsToResumeLocked()
            }
        }
        // outside of the lock: register dispose on cancellation
        emitter?.let { cont.disposeOnCancellation(it) }
        // outside of the lock: resume slots if needed
        resumeList?.forEach { it.resume(Unit) }
    }

    private fun cancelEmitter(emitter: Emitter) = synchronized(this) {
        if (emitter.index < head) return // already skipped past this index
        val buffer = buffer!!
        if (buffer.getBufferAt(emitter.index) !== emitter) return // already resumed
        buffer.setBufferAt(emitter.index, NO_VALUE)
        cleanupTailLocked()
    }

    internal fun updateNewCollectorIndexLocked(): Long {
        val index = replayIndexLocked
        if (index < minCollectorIndex) minCollectorIndex = index
        return index
    }

    // returns a list of continuation to resume after lock
    internal fun updateCollectorIndexLocked(oldIndex: Long): List<Continuation<Unit>>? {
        assert { oldIndex >= minCollectorIndex }
        if (oldIndex > minCollectorIndex) return null // nothing changes, it was not min
        // start computing new minimal index of active collectors
        val oldBufferSize = bufferSizeLocked
        var newMinIndex = head + oldBufferSize
        // take into account a special case of sync shared flow that can go past 1st queued emitter
        if (bufferCapacity == 0 && size > 0) newMinIndex++
        forEachSlotLocked { slot ->
            if (slot.index in 0 until newMinIndex) newMinIndex = slot.index
        }
        assert { newMinIndex >= minCollectorIndex } // can only grow
        if (newMinIndex <= minCollectorIndex) return null // nothing changes
        minCollectorIndex = newMinIndex
        // Compute new buffer size if we drop items we no longer need and no emitter is resumed:
        // We must keep all the items from newMinIndex to the end of buffer
        var curBufferEndIndex = head + oldBufferSize // var to grow when waiters are resumed
        val maxResumeCount = if (nCollectors > 0) {
            // If we have collectors we can resume up to maxResumeCount waiting emitters
            // a) size - oldBufferSize -> that's how many waiting emitters we have
            // b) bufferCapacity - newBufferSize0 -> that's how many we can afford to resume to add w/o exceeding bufferCapacity
            val newBufferSize0 = (curBufferEndIndex - newMinIndex).toInt()
            minOf(size - oldBufferSize, bufferCapacity - newBufferSize0)
        } else {
            // If we don't have collectors anymore we must resume all waiting emitters
            size - oldBufferSize // that's how many waiting emitters we have (at most)
        }
        var resumeList: ArrayList<Continuation<Unit>>? = null
        val buffer = buffer!!
        if (maxResumeCount > 0) { // collect emitters to resume if we have them
            resumeList = ArrayList(maxResumeCount)
            var curEmitterIndex = head + bufferCapacity // what emitter to wakeup
            while (resumeList.size < maxResumeCount) {
                val emitter = buffer.getBufferAt(curEmitterIndex)
                if (emitter !== NO_VALUE) {
                    emitter as Emitter // must have Emitter class
                    resumeList.add(emitter.cont)
                    buffer.setBufferAt(curEmitterIndex, NO_VALUE) // make as canceled if we moved ahead
                    buffer.setBufferAt(curBufferEndIndex, emitter.value)
                    curBufferEndIndex++
                }
                curEmitterIndex++
            }
        }
        // Compute new buffer size and new replay index
        val newBufferSize = (curBufferEndIndex - head).toInt() // how many values we now actually have
        val newReplayIndex = curBufferEndIndex - minOf(replayCapacity, newBufferSize)
        // now compute new head
        val newHead = if (nCollectors > 0) {
            // take slowest collector into account, and also keep replay cache for new collectors
            minOf(newMinIndex, newReplayIndex)
        } else {
            // no collectors -> patch minCollectorIndex to new replay index (drop the rest)
            minCollectorIndex = newReplayIndex
            newReplayIndex
        }
        assert { newHead >= head }
        // cleanup items we don't have to buffer anymore (because head moved)
        for (index in head until newHead) buffer.setBufferAt(index, null)
        // update buffer pointers
        size -= (newHead - head).toInt()
        head = newHead
        cleanupTailLocked() // just in case we've moved all buffered emitters and have NO_VALUE's at the tail now
        return resumeList
    }

    private fun cleanupTailLocked() {
        // Note: We leave the solve emitter's buffer index so that synchronous shared flow works correctly
        if (size <= bufferCapacity + 1) return // nothing to do, no queued emitters or at most one
        val buffer = buffer!!
        while (size > bufferCapacity && buffer.getBufferAt(head + size - 1) === NO_VALUE) {
            size--
            buffer.setBufferAt(head + size, null)
        }
    }

    // returns NO_VALUE if cannot take value without suspension
    private fun tryTakeValue(slot: SharedFlowSlot): Any? {
        var resumeList: List<Continuation<Unit>>? = null
        val value = synchronized(this) {
            val index = tryPeekLocked(slot)
            if (index < 0) {
                NO_VALUE
            } else {
                val oldIndex = slot.index
                val newValue = getPeekedValueLockedAt(index)
                slot.index = index + 1 // points to the next index after peeked one
                resumeList = updateCollectorIndexLocked(oldIndex)
                newValue
            }
        }
        resumeList?.forEach { it.resume(Unit) }
        return value
    }

    // returns -1 if cannot peek value without suspension
    private fun tryPeekLocked(slot: SharedFlowSlot): Long {
        // return buffered value is possible
        val index = slot.index
        if (index < head + bufferSizeLocked) return index
        if (bufferCapacity > 0) return -1L // if there's a buffer, never try to rendezvous with emitters
        // Synchronous shared flow (bufferSize == 0) tries to rendezvous
        if (index > head) return -1L // ... but only with the first emitter (never look forward)
        if (size == 0) return -1L // nothing there to rendezvous with
        return index // rendezvous with the first emitter
    }

    private fun getPeekedValueLockedAt(index: Long): Any? =
        when (val item = buffer!!.getBufferAt(index)) {
            is Emitter -> item.value
            else -> item
        }

    private suspend fun awaitValue(slot: SharedFlowSlot): Unit = suspendCancellableCoroutine { cont ->
        synchronized(this) lock@{
            val index = tryPeekLocked(slot) // recheck under this lock
            if (index < 0) {
                slot.cont = cont // Ok -- suspending
            } else {
                cont.resume(Unit) // has value, no need to suspend
                return@lock
            }
            slot.cont = cont // suspend, waiting
        }
    }

    private fun findSlotsToResumeLocked(): List<Continuation<Unit>>? {
        var result: ArrayList<Continuation<Unit>>? = null
        forEachSlotLocked loop@{ slot ->
            val cont = slot.cont ?: return@loop // only waiting slots
            if (tryPeekLocked(slot) < 0) return@loop // only slots that can peek a value
            val a = result ?: ArrayList<Continuation<Unit>>(2).also { result = it }
            a.add(cont)
            slot.cont = null // not waiting anymore
        }
        return result
    }

    override fun createSlot() = SharedFlowSlot()
    override fun createSlotArray(size: Int): Array<SharedFlowSlot?> = arrayOfNulls(size)

    override fun resetBuffer() {
        val resumeList= synchronized(this) {
            // resume all waiting emitters and drop their values
            val oldBufferSize = bufferSizeLocked
            val oldBufferEndIndex = head + oldBufferSize
            var resumeList: ArrayList<Continuation<Unit>>? = null
            if (size > oldBufferSize) {
                val buffer = buffer!!
                resumeList = ArrayList(size - oldBufferSize)
                for (emitterIndex in oldBufferEndIndex until head + size) {
                    val emitter = buffer.getBufferAt(emitterIndex)
                    if (emitter !== NO_VALUE) {
                        emitter as Emitter // must have Emitter class
                        resumeList.add(emitter.cont)
                    }
                    buffer.setBufferAt(emitterIndex, null) // clear this slot
                }
                size = oldBufferSize // dropped all emitters
            }
            // enqueue initial value at the end
            val hasInitialValue = initialValue !== NO_VALUE
            if (hasInitialValue) {
                assert { replayCapacity > 0 } // only supporting initial value with replay
                assert { size > 0 } // cannot have an empty buffer with initial value
                enqueueLocked(initialValue) // Enqueue it
            }
            // compute new index for all collectors and new min index among them
            val newReplayIndex = if (hasInitialValue) head + size - 1 else head + size
            // update all collectors indexes and wakeup for new initial value if needed
            forEachSlotLocked { slot ->
                assert { newReplayIndex >= slot.index } // index only grows forward
                slot.index = newReplayIndex // move index up to drop the rest of the buffer
                if (hasInitialValue) { // need to get initial value
                    val cont = slot.cont
                    if (cont != null) { // .. and it is suspended now
                        slot.cont = null // resume it to get initial value
                        val list = resumeList ?: ArrayList<Continuation<Unit>>(2).also {
                            resumeList = it
                        }
                        list.add(cont)
                    }
                }
            }
            // update min collector index
            minCollectorIndex = newReplayIndex
            // cleanup the rest of the buffer
            if (newReplayIndex > head) {
                val buffer = buffer!!
                for (index in head until newReplayIndex) buffer.setBufferAt(index, null)
            }
            // update buffer head and size
            head = newReplayIndex
            size = if (hasInitialValue) 1 else 0
            // update buffer head and size
            resumeList
        }
        resumeList?.forEach { it.resume(Unit) }
    }

    private class Emitter(
        @JvmField val flow: SharedFlowImpl<*>,
        @JvmField val index: Long,
        @JvmField val value: Any?,
        @JvmField val cont: Continuation<Unit>
    ) : DisposableHandle {
        override fun dispose() = flow.cancelEmitter(this)
    }
}

@SharedImmutable
@JvmField
internal val NO_VALUE = Symbol("NO_VALUE")

private fun Array<Any?>.getBufferAt(index: Long) = get(index.toInt() and (size - 1))
private fun Array<Any?>.setBufferAt(index: Long, value: Any?) = set(index.toInt() and (size - 1), value)

