package com.codeagent.plugin.agent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class InlineCompletionCache(
    private val maxEntries: Int = 48,
    private val ttlMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: String): String? {
        val entry = entries[key] ?: return null
        if (clock() - entry.createdAt > ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.completion
    }

    @Synchronized
    fun put(key: String, completion: String) {
        entries[key] = Entry(completion, clock())
    }

    companion object {
        fun key(path: String, language: String, prefix: String, suffix: String, backendUrl: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(backendUrl, path, language, prefix, suffix).forEach { value ->
                digest.update(value.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private data class Entry(val completion: String, val createdAt: Long)
}
