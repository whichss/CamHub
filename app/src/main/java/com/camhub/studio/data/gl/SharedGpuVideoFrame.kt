package com.camhub.studio.data.gl

import android.opengl.GLES30

/** A leased decoder texture. Every consumer must release it after its GPU read is submitted. */
class SharedGpuVideoFrame internal constructor(
    val textureId: Int,
    val width: Int,
    val height: Int,
    val frameSequence: Long,
    val presentationTimeUs: Long,
    val decodedAtElapsedMs: Long,
    private val waitForProducer: () -> Unit,
    private val retainSlot: () -> Unit,
    private val releaseSlot: (Long) -> Unit
) : AutoCloseable {
    private val lock = Any()
    private var references = 1

    /** Adds one consumer reference. The caller must release exactly that reference. */
    fun retain(): SharedGpuVideoFrame = synchronized(lock) {
        check(references > 0) { "Cannot retain a released GPU frame" }
        retainSlot()
        references++
        this
    }

    /** Queues a GPU-side wait for the decoder's published texture writes. */
    fun waitForProducerCommands() = waitForProducer()

    /** Releases after GL commands in the current shared context have sampled the texture. */
    fun releaseAfterCurrentGlCommands() {
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (fence != 0L) GLES30.glFlush() else GLES30.glFinish()
        releaseReference(fence)
    }

    /** Releases a reference that was never submitted to GL. */
    override fun close() = releaseReference(0L)

    private fun releaseReference(fence: Long) {
        synchronized(lock) {
            check(references > 0) { "GPU frame reference released more than once" }
            references--
        }
        releaseSlot(fence)
    }
}

/** Decoder-thread-owned ring. A slot is not overwritten while any output holds a lease. */
internal class SharedGpuTexturePool(private val textureIds: IntArray) {
    internal class Slot(val textureId: Int) {
        var activeReferences = 0
        var deleted = false
        var producerFence = 0L
        val consumerFences = ArrayList<Long>()
    }

    private val lock = Any()
    private val slots = textureIds.map(::Slot)
    private var closed = false

    /** Must run on a shared GL context. Fence waits are queued on the GPU, not the CPU. */
    fun acquireForWrite(): Slot? {
        val selected = synchronized(lock) {
            if (closed) return null
            val slot = slots.firstOrNull { !it.deleted && it.activeReferences == 0 } ?: return null
            slot.activeReferences = 1 // writer ownership becomes the published-frame reference
            val fences = slot.consumerFences.toList()
            slot.consumerFences.clear()
            slot to fences
        }
        selected.second.forEach { fence ->
            GLES30.glWaitSync(fence, 0, GLES30.GL_TIMEOUT_IGNORED)
            GLES30.glDeleteSync(fence)
        }
        val producerFence = synchronized(lock) {
            selected.first.producerFence.also { selected.first.producerFence = 0L }
        }
        if (producerFence != 0L) GLES30.glDeleteSync(producerFence)
        return selected.first
    }

    fun publish(
        slot: Slot,
        width: Int,
        height: Int,
        frameSequence: Long,
        presentationTimeUs: Long,
        decodedAtElapsedMs: Long
    ): SharedGpuVideoFrame {
        slot.producerFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (slot.producerFence != 0L) GLES30.glFlush() else GLES30.glFinish()
        return SharedGpuVideoFrame(
            textureId = slot.textureId,
            width = width,
            height = height,
            frameSequence = frameSequence,
            presentationTimeUs = presentationTimeUs,
            decodedAtElapsedMs = decodedAtElapsedMs,
            waitForProducer = {
                val fence = synchronized(lock) { slot.producerFence }
                if (fence != 0L) GLES30.glWaitSync(fence, 0, GLES30.GL_TIMEOUT_IGNORED)
            },
            retainSlot = { retain(slot) },
            releaseSlot = { fence -> release(slot, fence) }
        )
    }

    fun abandonWrite(slot: Slot) = release(slot, 0L)

    fun close() {
        val deleteNow = synchronized(lock) {
            closed = true
            slots.filter { it.activeReferences == 0 && !it.deleted }.onEach { it.deleted = true }
        }
        deleteNow.forEach(::deleteSlot)
    }

    private fun retain(slot: Slot) = synchronized(lock) {
        check(!closed && !slot.deleted && slot.activeReferences > 0) {
            "Cannot retain a GPU texture after its pool has closed"
        }
        slot.activeReferences++
    }

    private fun release(slot: Slot, fence: Long) {
        val deleteTexture = synchronized(lock) {
            if (fence != 0L) slot.consumerFences += fence
            check(slot.activeReferences > 0) { "GPU texture slot released without an active reference" }
            slot.activeReferences--
            if (closed && slot.activeReferences == 0 && !slot.deleted) {
                slot.deleted = true
                true
            } else false
        }
        if (deleteTexture) deleteSlot(slot)
    }

    private fun deleteSlot(slot: Slot) {
        if (slot.producerFence != 0L) {
            GLES30.glDeleteSync(slot.producerFence)
            slot.producerFence = 0L
        }
        slot.consumerFences.forEach(GLES30::glDeleteSync)
        slot.consumerFences.clear()
        GLES30.glDeleteTextures(1, intArrayOf(slot.textureId), 0)
    }
}
