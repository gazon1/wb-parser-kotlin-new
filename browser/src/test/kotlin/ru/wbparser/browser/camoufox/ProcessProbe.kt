package ru.wbparser.browser.camoufox

import java.nio.file.Files
import java.nio.file.Path

// ─────────────────────────────────────────────────────────────────────────────
// ProcessProbe
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The processes of this machine, as the operating system sees them.
 *
 * The smoke test asks a question about the machine and not about the objects of this
 * process: a browser that is gone from our references may still be a running process,
 * and a driver that is gone from our references may still own a browser. Linux writes
 * the command line of every process into `/proc`, so on that platform the question has
 * an answer that no double can fake.
 *
 * The probe reads `/proc` and nothing else: no process manager, no PID file, no
 * watchdog. Where `/proc` is not there, the check has no answer and the test that asks
 * it is skipped — a check that cannot see the machine proves nothing about it.
 */
internal object ProcessProbe {
    /** Where the operating system writes what it knows about its processes. */
    private val PROC = Path.of("/proc")

    /** How long a process may take to disappear after it was asked to. */
    private const val DISAPPEAR_TIMEOUT_MS = 15_000L

    /** How often the machine is asked while it waits. */
    private const val DISAPPEAR_POLL_MS = 250L

    /** One process of the machine. */
    data class Process(
        val pid: Long,
        val parentPid: Long,
        val commandLine: String,
    )

    /** Whether this machine can tell which processes are running. */
    fun available(): Boolean = Files.isDirectory(PROC)

    /**
     * The processes of this machine, by pid.
     *
     * A process that ends while the directory is read is not an error: it simply is
     * not in the answer.
     */
    fun processes(): Map<Long, Process> =
        runCatching {
            val found = mutableListOf<Process>()
            Files.newDirectoryStream(PROC).use { entries ->
                for (entry in entries) {
                    val pid = entry.fileName.toString().toLongOrNull() ?: continue
                    readProcess(pid)?.let { found.add(it) }
                }
            }
            found.associateBy { it.pid }
        }.getOrElse { emptyMap() }

    /**
     * The processes that descend from [root], the root itself included.
     *
     * The driver of Playwright is a child of this process, and the browser it starts
     * is a child of the driver: the whole run is one tree, and the tree is ours.
     */
    fun descendants(
        root: Long,
        processes: Map<Long, Process>,
    ): Set<Long> {
        val children = processes.values.groupBy { it.parentPid }
        val found = mutableSetOf(root)
        val queue = ArrayDeque<Long>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            children[pid].orEmpty().forEach { child ->
                if (found.add(child.pid)) {
                    queue.add(child.pid)
                }
            }
        }
        return found
    }

    /**
     * Waits until the processes of [pids] are gone.
     *
     * A process that was asked to end may take a moment to end, and the test is about
     * the machine and not about the speed of the machine.
     *
     * @return the processes that were still there when the waiting was over
     */
    fun awaitGone(pids: Set<Long>): Set<Long> {
        val deadline = System.currentTimeMillis() + DISAPPEAR_TIMEOUT_MS
        var left = pids.filter { it in processes() }
        while (left.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(DISAPPEAR_POLL_MS)
            left = left.filter { it in processes() }
        }
        return left.toSet()
    }

    /** What the operating system knows about one process. */
    private fun readProcess(pid: Long): Process? {
        val directory = PROC.resolve(pid.toString())
        val commandLine = runCatching { Files.readString(directory.resolve("cmdline")) }.getOrNull() ?: return null
        val stat = runCatching { Files.readString(directory.resolve("stat")) }.getOrNull() ?: return null
        return Process(pid = pid, parentPid = parentPidOf(stat), commandLine = commandLine.replace('\u0000', ' ').trim())
    }

    /**
     * The parent of a process, from the fields of `/proc/<pid>/stat`.
     *
     * The second field is the command of the process in parentheses, and it may hold
     * spaces of its own — so the fields after it are counted from the parenthesis that
     * closes it.
     */
    private fun parentPidOf(stat: String): Long {
        val afterName = stat.substringAfterLast(')').trim()
        return afterName.split(' ').getOrNull(1)?.toLongOrNull() ?: -1L
    }
}
