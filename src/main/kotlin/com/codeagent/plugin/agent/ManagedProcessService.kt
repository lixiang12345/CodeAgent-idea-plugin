package com.codeagent.plugin.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.isExecutable

internal data class ManagedProcessSnapshot(
    val id: String,
    val name: String,
    val command: String,
    val workingDirectory: String,
    val pid: Long,
    val startedAt: Instant,
    val state: String,
    val exitCode: Int?,
    val waitingForInput: Boolean,
    val outputStartOffset: Long,
    val outputEndOffset: Long,
)

internal data class ManagedProcessRead(
    val process: ManagedProcessSnapshot,
    val output: String,
    val nextOffset: Long,
    val truncatedBeforeOffset: Boolean,
)

internal class ManagedProcessRegistry(
    private val root: Path,
    private val executor: Executor = AppExecutorUtil.getAppExecutorService(),
    private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    private val inputPromptIdleMillis: Long = INPUT_PROMPT_IDLE_MILLIS,
) : AutoCloseable {
    private val processes = ConcurrentHashMap<String, ManagedProcessEntry>()
    private val sequence = AtomicLong()
    private val lifecycleLock = Any()

    fun launch(command: String, name: String? = null, cwd: String? = null): ManagedProcessSnapshot {
        val normalizedCommand = command.trim()
        require(normalizedCommand.isNotEmpty()) { "command is required" }
        require(normalizedCommand.length <= MAX_COMMAND_CHARS) { "command exceeds $MAX_COMMAND_CHARS characters" }
        require(Files.isDirectory(root)) { "Project root is unavailable" }
        val workingDirectory = resolveWorkingDirectory(cwd)
        val entry = synchronized(lifecycleLock) {
            cleanupCompletedLocked()
            require(processes.values.count { it.process.isAlive } < MAX_RUNNING_PROCESSES) {
                "At most $MAX_RUNNING_PROCESSES managed processes may run at once"
            }

            val id = "process-${sequence.incrementAndGet()}"
            val label = name?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_NAME_CHARS) ?: id
            val process = ProcessBuilder(shellCommand(normalizedCommand))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start()
            ManagedProcessEntry(
                id,
                label,
                normalizedCommand,
                workingDirectory,
                process,
                maxOutputChars,
                inputPromptIdleMillis,
            ).also {
                processes[id] = it
            }
        }
        executor.execute { entry.captureOutput() }
        return entry.snapshot()
    }

    fun list(): List<ManagedProcessSnapshot> =
        processes.values.map(ManagedProcessEntry::snapshot).sortedByDescending(ManagedProcessSnapshot::startedAt)

    fun read(id: String, offset: Long = 0, maxChars: Int = DEFAULT_READ_CHARS): ManagedProcessRead =
        entry(id).also { if (!it.process.isAlive) it.awaitOutput() }
            .read(offset.coerceAtLeast(0), maxChars.coerceIn(1, MAX_READ_CHARS))

    fun write(id: String, input: String, appendNewline: Boolean = true): ManagedProcessSnapshot {
        require(input.length <= MAX_INPUT_CHARS) { "input exceeds $MAX_INPUT_CHARS characters" }
        val entry = entry(id)
        require(entry.process.isAlive) { "Managed process '$id' is not running" }
        val payload = if (appendNewline) "$input\n" else input
        synchronized(entry.writeLock) {
            entry.resetWaitingForInput()
            entry.process.outputStream.write(payload.toByteArray(StandardCharsets.UTF_8))
            entry.process.outputStream.flush()
        }
        return entry.snapshot()
    }

    fun waitFor(id: String, timeoutSeconds: Int): ManagedProcessSnapshot {
        val entry = entry(id)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.coerceIn(1, MAX_WAIT_SECONDS).toLong())
        while (entry.process.isAlive && !entry.checkForInputPrompt() && System.nanoTime() < deadline) {
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0))
            entry.process.waitFor(remainingMillis.coerceIn(1, PROCESS_WAIT_POLL_MILLIS), TimeUnit.MILLISECONDS)
        }
        if (!entry.process.isAlive) entry.awaitOutput()
        return entry.snapshot()
    }

    fun kill(id: String, force: Boolean = false): ManagedProcessSnapshot {
        val entry = entry(id)
        if (entry.process.isAlive) {
            entry.killRequested.set(true)
            terminateProcessTree(entry.process, force)
        }
        if (!entry.process.isAlive) entry.awaitOutput()
        return entry.snapshot()
    }

    override fun close() {
        val entries = synchronized(lifecycleLock) {
            processes.values.toList().also { processes.clear() }
        }
        entries.forEach { entry ->
            runCatching {
                if (entry.process.isAlive) {
                    entry.killRequested.set(true)
                    terminateProcessTree(entry.process, force = true)
                }
                entry.awaitOutput()
                entry.closeStreams()
            }
        }
    }

    private fun entry(id: String): ManagedProcessEntry =
        processes[id.trim()] ?: error("Unknown managed process: $id")

    private fun resolveWorkingDirectory(cwd: String?): Path {
        val projectRoot = root.toRealPath()
        val requested = cwd?.trim()?.takeIf(String::isNotEmpty) ?: return projectRoot
        require(requested.length <= MAX_CWD_CHARS) { "cwd exceeds $MAX_CWD_CHARS characters" }
        val requestedPath = Path.of(requested)
        val candidate = (if (requestedPath.isAbsolute) requestedPath else projectRoot.resolve(requestedPath)).normalize()
        require(Files.isDirectory(candidate)) { "Working directory does not exist: $requested" }
        val real = candidate.toRealPath()
        require(real == projectRoot || real.startsWith(projectRoot)) { "Working directory must stay inside the project" }
        return real
    }

    private fun cleanupCompletedLocked() {
        if (processes.size < MAX_RETAINED_PROCESSES) return
        processes.values
            .filterNot { it.process.isAlive }
            .sortedBy { it.startedAt }
            .take((processes.size - MAX_RETAINED_PROCESSES + 1).coerceAtLeast(1))
            .forEach { entry ->
                if (processes.remove(entry.id, entry)) entry.closeStreams()
            }
    }

    private fun terminateProcessTree(process: Process, force: Boolean) {
        val rootHandle = process.toHandle()
        val handles = rootHandle.descendants().toList().asReversed() + rootHandle
        signal(handles, force)
        if (!force && !awaitTermination(handles, GRACEFUL_KILL_SECONDS)) signal(handles, force = true)
        awaitTermination(handles, FORCED_KILL_SECONDS)
    }

    private fun signal(handles: List<ProcessHandle>, force: Boolean) {
        handles.filter(ProcessHandle::isAlive).forEach { handle ->
            runCatching {
                if (force) handle.destroyForcibly() else handle.destroy()
            }
        }
    }

    private fun awaitTermination(handles: List<ProcessHandle>, timeoutSeconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(PROCESS_POLL_MILLIS)
        }
        return handles.none(ProcessHandle::isAlive)
    }

    private fun shellCommand(command: String): List<String> {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return listOf("cmd.exe", "/c", command)
        }
        val configuredShell = System.getenv("SHELL")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf { Files.isRegularFile(it) && it.isExecutable() }
            ?.toString()
            ?: "/bin/sh"
        return listOf(configuredShell, "-lc", command)
    }

    private class ManagedProcessEntry(
        val id: String,
        val name: String,
        val command: String,
        val workingDirectory: Path,
        val process: Process,
        private val maxOutputChars: Int,
        private val inputPromptIdleMillis: Long,
    ) {
        val startedAt: Instant = Instant.now()
        val killRequested = AtomicBoolean(false)
        val writeLock = Any()
        private val outputLock = Any()
        private val outputComplete = CountDownLatch(1)
        private val output = StringBuilder()
        private val lastOutputTimeMillis = AtomicLong(System.currentTimeMillis())
        private val waitingForInput = AtomicBoolean(false)
        private var outputStartOffset = 0L

        fun captureOutput() {
            try {
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { reader ->
                    val buffer = CharArray(4_096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(outputLock) {
                            output.append(buffer, 0, count)
                            lastOutputTimeMillis.set(System.currentTimeMillis())
                            waitingForInput.set(false)
                            val overflow = output.length - maxOutputChars
                            if (overflow > 0) {
                                output.delete(0, overflow)
                                outputStartOffset += overflow
                            }
                        }
                    }
                }
            } finally {
                outputComplete.countDown()
            }
        }

        fun awaitOutput() {
            outputComplete.await(OUTPUT_DRAIN_SECONDS, TimeUnit.SECONDS)
        }

        fun closeStreams() {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }

        fun resetWaitingForInput() {
            waitingForInput.set(false)
            lastOutputTimeMillis.set(System.currentTimeMillis())
        }

        fun checkForInputPrompt(): Boolean {
            if (!process.isAlive) return false
            if (waitingForInput.get()) return true
            if (System.currentTimeMillis() - lastOutputTimeMillis.get() < inputPromptIdleMillis) return false
            val tail = synchronized(outputLock) { output.takeLast(MAX_PROMPT_TAIL_CHARS).toString() }
            return outputEndsWithPrompt(tail).also { waitingForInput.set(it) }
        }

        fun snapshot(): ManagedProcessSnapshot {
            val running = process.isAlive
            val exitCode = if (running) null else runCatching(process::exitValue).getOrNull()
            val offsets = synchronized(outputLock) { outputStartOffset to outputStartOffset + output.length }
            val state = when {
                running -> "running"
                killRequested.get() -> "killed"
                exitCode == 0 -> "completed"
                else -> "failed"
            }
            return ManagedProcessSnapshot(
                id = id,
                name = name,
                command = command,
                workingDirectory = workingDirectory.toString(),
                pid = process.pid(),
                startedAt = startedAt,
                state = state,
                exitCode = exitCode,
                waitingForInput = checkForInputPrompt(),
                outputStartOffset = offsets.first,
                outputEndOffset = offsets.second,
            )
        }

        fun read(offset: Long, maxChars: Int): ManagedProcessRead = synchronized(outputLock) {
            val actualOffset = offset.coerceAtLeast(outputStartOffset)
            val start = (actualOffset - outputStartOffset).coerceAtMost(output.length.toLong()).toInt()
            val end = (start + maxChars).coerceAtMost(output.length)
            val text = output.substring(start, end)
            ManagedProcessRead(
                process = snapshot(),
                output = text,
                nextOffset = actualOffset + text.length,
                truncatedBeforeOffset = offset < outputStartOffset,
            )
        }
    }

    companion object {
        private const val MAX_COMMAND_CHARS = 20_000
        private const val MAX_CWD_CHARS = 2_000
        private const val MAX_NAME_CHARS = 120
        private const val MAX_INPUT_CHARS = 20_000
        private const val MAX_RUNNING_PROCESSES = 16
        private const val MAX_RETAINED_PROCESSES = 64
        private const val DEFAULT_MAX_OUTPUT_CHARS = 100_000
        private const val DEFAULT_READ_CHARS = 20_000
        private const val MAX_READ_CHARS = 40_000
        private const val MAX_WAIT_SECONDS = 60
        private const val GRACEFUL_KILL_SECONDS = 2L
        private const val FORCED_KILL_SECONDS = 2L
        private const val OUTPUT_DRAIN_SECONDS = 2L
        private const val PROCESS_POLL_MILLIS = 25L
        private const val PROCESS_WAIT_POLL_MILLIS = 100L
        private const val INPUT_PROMPT_IDLE_MILLIS = 3_000L
        private const val MAX_PROMPT_TAIL_CHARS = 4_096
    }
}

internal fun outputEndsWithPrompt(output: String): Boolean {
    if (output.isEmpty() || output.endsWith('\n') || output.endsWith('\r')) return false
    val line = output.substringAfterLast('\n').substringAfterLast('\r').trim()
    if (line.length !in 2..80 || (!line.endsWith(':') && !line.endsWith('?'))) return false
    return NON_PROMPT_PATTERNS.none { it.containsMatchIn(line) }
}

private val NON_PROMPT_PATTERNS = listOf(
    Regex("[0-9]+%"),
    Regex("[0-9]+/[0-9]+"),
    Regex("://[^\\s'\"]*$"),
)

@Service(Service.Level.PROJECT)
class ManagedProcessService(project: Project) : Disposable {
    private val registry = ManagedProcessRegistry(Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize())

    internal fun launch(command: String, name: String?, cwd: String?): ManagedProcessSnapshot = registry.launch(command, name, cwd)

    internal fun list(): List<ManagedProcessSnapshot> = registry.list()

    internal fun read(id: String, offset: Long, maxChars: Int): ManagedProcessRead = registry.read(id, offset, maxChars)

    internal fun write(id: String, input: String, appendNewline: Boolean): ManagedProcessSnapshot =
        registry.write(id, input, appendNewline)

    internal fun waitFor(id: String, timeoutSeconds: Int): ManagedProcessSnapshot = registry.waitFor(id, timeoutSeconds)

    internal fun kill(id: String, force: Boolean): ManagedProcessSnapshot = registry.kill(id, force)

    override fun dispose() = registry.close()
}
