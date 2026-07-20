package com.codeagent.plugin.context

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.time.Duration
import java.util.concurrent.TimeUnit

object NodeRuntimeLocator {
    private val minimumVersion = NodeVersion(22, 5, 0)

    fun find(configuredPath: String? = null): String {
        val candidates = buildList {
            configuredPath?.takeIf { it.isNotBlank() }?.let(::add)
            System.getProperty("codeagent.node.path")?.takeIf { it.isNotBlank() }?.let(::add)
            System.getenv("CODEAGENT_NODE")?.takeIf { it.isNotBlank() }?.let(::add)
            System.getenv("NVM_BIN")?.takeIf { it.isNotBlank() }?.let { add(Path.of(it, "node").toString()) }
            System.getenv("FNM_BIN")?.takeIf { it.isNotBlank() }?.let { add(Path.of(it, "node").toString()) }
            System.getenv("MISE_BIN_PATH")?.takeIf { it.isNotBlank() }?.let { add(Path.of(it, "node").toString()) }
            shellNodePath()?.let(::add)
            managerNodePath("mise", "which", "node")?.let(::add)
            managerNodePath("asdf", "which", "node")?.let(::add)
            add("node")
            add("/opt/homebrew/bin/node")
            add("/opt/homebrew/opt/node@22/bin/node")
            add("/usr/local/bin/node")
            add("/usr/local/opt/node@22/bin/node")
            add("/usr/bin/node")
            add(Path.of(System.getProperty("user.home"), ".local", "bin", "node").toString())
            addAll(versionManagerCandidates())
        }.distinct()

        val checked = mutableListOf<String>()
        for (candidate in candidates) {
            if (candidate.contains('/') || candidate.contains('\\')) {
                if (!Files.isExecutable(Path.of(candidate))) continue
            }
            val version = readVersion(candidate) ?: continue
            checked += "$candidate ($version)"
            if (version >= minimumVersion) return candidate
        }

        val details = checked.takeIf { it.isNotEmpty() }?.joinToString() ?: "no Node.js executable found"
        error("CodeAgent ContextEngine requires Node.js 22.5 or newer; checked $details")
    }

    private fun shellNodePath(): String? = runCatching {
        val shell = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "cmd.exe" else "/bin/sh"
        val arguments = if (shell == "cmd.exe") listOf("/c", "where node") else listOf("-lc", "command -v node")
        commandOutput(shell, arguments)
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { it.isNotEmpty() }
    }.getOrNull()

    private fun managerNodePath(command: String, vararg arguments: String): String? = runCatching {
        commandOutput(command, arguments.toList())
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { it.isNotEmpty() && (it.contains('/') || it.contains('\\')) }
    }.getOrNull()

    private fun commandOutput(command: String, arguments: List<String>): String? {
        val process = ProcessBuilder(listOf(command) + arguments)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly()
            return null
        }
        return process.inputReader().readText()
    }

    private fun versionManagerCandidates(): List<String> {
        val home = Path.of(System.getProperty("user.home"))
        val roots = listOf(
            home.resolve(".nvm/versions/node"),
            home.resolve(".asdf/installs/nodejs"),
            home.resolve(".local/share/mise/installs/node"),
        )
        return roots.flatMap { root ->
            runCatching {
                if (!Files.isDirectory(root)) return@runCatching emptyList()
                Files.list(root).use { stream ->
                    stream
                        .filter(Files::isDirectory)
                        .sorted(Comparator.reverseOrder())
                        .map { it.resolve("bin/node").toString() }
                        .toList()
                }
            }.getOrDefault(emptyList())
        }
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
