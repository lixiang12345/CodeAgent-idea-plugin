package com.codeagent.plugin.bridge

internal data class BrowserEvent(
    val sequence: Long,
    val script: String,
    val attempts: Int = 0,
)

internal class BrowserEventQueue {
    private val pending = ArrayDeque<BrowserEvent>()
    private var inFlight: BrowserEvent? = null

    @Synchronized
    fun enqueue(event: BrowserEvent) {
        pending.addLast(event)
    }

    @Synchronized
    fun nextForDispatch(): BrowserEvent? {
        if (inFlight != null) return null
        return pending.removeFirstOrNull()?.also { inFlight = it }
    }

    @Synchronized
    fun retry(sequence: Long): BrowserEvent? {
        val current = inFlight?.takeIf { it.sequence == sequence } ?: return null
        return current.copy(attempts = current.attempts + 1).also { inFlight = it }
    }

    @Synchronized
    fun acknowledge(sequence: Long): Boolean {
        if (inFlight?.sequence != sequence) return false
        inFlight = null
        return true
    }

    @Synchronized
    fun isCurrent(sequence: Long): Boolean = inFlight?.sequence == sequence

    @Synchronized
    fun hasDispatchableEvent(): Boolean = inFlight == null && pending.isNotEmpty()

    @Synchronized
    fun reset() {
        pending.clear()
        inFlight = null
    }
}
