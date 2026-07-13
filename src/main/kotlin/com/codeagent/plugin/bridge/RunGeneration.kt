package com.codeagent.plugin.bridge

import java.util.concurrent.atomic.AtomicLong

internal class RunGeneration {
    private val current = AtomicLong()

    fun next(): Long = current.incrementAndGet()

    fun invalidate() {
        current.incrementAndGet()
    }

    fun isCurrent(generation: Long): Boolean = current.get() == generation
}
