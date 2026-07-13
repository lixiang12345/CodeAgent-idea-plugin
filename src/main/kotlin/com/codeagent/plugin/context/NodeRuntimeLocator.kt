package com.codeagent.plugin.context

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

object NodeRuntimeLocator {
    private val minimumVersion = NodeVersion(22, 5, 0)

    fun find(configuredPath: String? = null): String {
        val candidates = buildList {
            configuredPath?.takeIf { it.isNotBlank() }?.let(::add)
            System.getProperty("codeagent.node.path")?.takeIf { it.isNotBlank() }?.let(::add)
            System.getenv("CODEAGENT_NODE")?.takeIf { it.isNotBlank() }?.let(::add)
            add("node")
            add("/opt/homebrew/bin/node")
            add("/usr/local/bin/node")
            add(Path.of(System.getProperty("user.home"), ".local", "bin", "node").toString())
        }.distinct()

        val checked = mutableListOf<String>()
        for (candidate in candidates) {
            if (candidate.contains('/') && !Files.isExecutable(Path.of(candidate))) continue
            val version = readVersion(candidate) ?: continue
            checked += "$candidate ($version)"
            if (version >= minimumVersion) return candidate
        }

        val details = checked.takeIf { it.isNotEmpty() }?.joinToString() ?: "no Node.js executable found"
        error("CodeAgent ContextEngine requires Node.js 22.5 or newer; checked $details")
    }

    internal fun parseVersion(value: String): NodeVersion? {
        val match = VERSION_REGEX.find(value.trim()) ?: return null
        return NodeVersion(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].toInt(),
            patch = match.groupValues[3].toInt(),
        )
    }

    private fun readVersion(executable: String): NodeVersion? = runCatching {
        val process = ProcessBuilder(executable, "--version").start()
        if (!process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        parseVersion(process.inputReader().readText())
    }.getOrNull()

    private val VERSION_REGEX = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)")
}

data class NodeVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<NodeVersion> {
    override fun compareTo(other: NodeVersion): Int =
        compareValuesBy(this, other, NodeVersion::major, NodeVersion::minor, NodeVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"
}
