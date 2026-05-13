package com.termx.app.power

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.*
import java.util.*

/**
 * Process Monitor API for TermX — htop-like process viewer from terminal.
 *
 * Shell usage:
 *   termx-ps list                     List all processes
 *   termx-ps top                      Show top processes (CPU usage)
 *   termx-ps info <pid>               Detailed process info
 *   termx-ps kill <pid> [signal]      Kill a process
 *   termx-ps tree                     Process tree
 *   termx-ps threads <pid>            List threads of a process
 *   termx-ps mem                      Memory usage summary
 *   termx-ps cpu                      CPU usage summary
 *   termx-ps watch [interval]         Continuous monitoring
 *   termx-ps find <name>              Find process by name
 *   termx-ps children <pid>           List child processes
 */
object ProcessMonitorApi {

    private const val TAG = "ProcessMonitorApi"
    private val PROC_DIR = File("/proc")

    /** Previous CPU times snapshot for calculating usage. */
    private var prevCpuTime: Long = 0
    private var prevIdleTime: Long = 0
    private var prevProcessTimes = mutableMapOf<Int, ProcessCpuSnapshot>()
    private var lastSnapshotTime: Long = 0

    /**
     * Holds a CPU usage snapshot for a process.
     */
    data class ProcessCpuSnapshot(
        val pid: Int,
        val utime: Long,
        val stime: Long,
        val timestamp: Long
    )

    /**
     * Parsed process information from /proc/[pid]/stat.
     */
    data class ProcessInfo(
        val pid: Int,
        val comm: String,
        val state: Char,
        val ppid: Int,
        val pgrp: Int,
        val session: Int,
        val ttyNr: Int,
        val tpgid: Int,
        val flags: Long,
        val minflt: Long,
        val cminflt: Long,
        val majflt: Long,
        val cmajflt: Long,
        val utime: Long,
        val stime: Long,
        val cutime: Long,
        val cstime: Long,
        val priority: Long,
        val nice: Long,
        val numThreads: Long,
        val itrealvalue: Long,
        val starttime: Long,
        val vsize: Long,
        val rss: Long
    ) {
        /** Total CPU time (user + system) in clock ticks. */
        val totalTime: Long get() = utime + stime

        /** Resident Set Size in bytes. */
        val rssBytes: Long get() = rss * PAGE_SIZE

        /** Virtual memory size in bytes. */
        val vsizeBytes: Long get() = vsize

        /** State as human-readable string. */
        val stateString: String get() = stateToText(state)

        companion object {
            val PAGE_SIZE = 4096L

            /**
             * Parse /proc/[pid]/stat content into a ProcessInfo object.
             */
            fun fromStat(statLine: String): ProcessInfo? {
                return try {
                    // The comm field is in parentheses and may contain spaces
                    val commStart = statLine.indexOf('(')
                    val commEnd = statLine.lastIndexOf(')')
                    if (commStart < 0 || commEnd < 0) return null

                    val pid = statLine.substring(0, commStart).trim().toInt()
                    val comm = statLine.substring(commStart + 1, commEnd)

                    val fields = statLine.substring(commEnd + 2).split(" ")
                    if (fields.size < 21) return null

                    ProcessInfo(
                        pid = pid,
                        comm = comm,
                        state = fields[0][0],
                        ppid = fields[1].toIntOrNull() ?: 0,
                        pgrp = fields[2].toIntOrNull() ?: 0,
                        session = fields[3].toIntOrNull() ?: 0,
                        ttyNr = fields[4].toIntOrNull() ?: 0,
                        tpgid = fields[5].toIntOrNull() ?: 0,
                        flags = fields[6].toLongOrNull() ?: 0,
                        minflt = fields[7].toLongOrNull() ?: 0,
                        cminflt = fields[8].toLongOrNull() ?: 0,
                        majflt = fields[9].toLongOrNull() ?: 0,
                        cmajflt = fields[10].toLongOrNull() ?: 0,
                        utime = fields[11].toLongOrNull() ?: 0,
                        stime = fields[12].toLongOrNull() ?: 0,
                        cutime = fields[13].toLongOrNull() ?: 0,
                        cstime = fields[14].toLongOrNull() ?: 0,
                        priority = fields[15].toLongOrNull() ?: 0,
                        nice = fields[16].toLongOrNull() ?: 0,
                        numThreads = fields[17].toLongOrNull() ?: 0,
                        itrealvalue = fields[18].toLongOrNull() ?: 0,
                        starttime = fields[19].toLongOrNull() ?: 0,
                        vsize = fields[20].toLongOrNull() ?: 0,
                        rss = fields[21].toLongOrNull() ?: 0
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Detailed process status from /proc/[pid]/status.
     */
    data class ProcessStatus(
        val pid: Int,
        val name: String,
        val state: String,
        val tgid: Int,
        val ppid: Int,
        val tracerPid: Int,
        val uid: Int,
        val gid: Int,
        val fdSize: Int,
        val vmPeak: Long,
        val vmSize: Long,
        val vmRSS: Long,
        val vmData: Long,
        val vmStk: Long,
        val threads: Int,
        val voluntaryCtxtSwitches: Long,
        val nonVoluntaryCtxtSwitches: Long
    )

    // ---- List processes ----

    /**
     * List all running processes.
     */
    fun list(): String {
        return try {
            val processes = getAllProcesses()
            if (processes.isEmpty()) return "No processes found (may need root for full access)"

            buildString {
                appendLine("=== Processes (${processes.size}) ===")
                appendLine(String.format("%-7s %-6s %-6s %-5s %-8s %-8s %-6s %s",
                    "USER", "PID", "PPID", "S", "RSS", "VSZ", "THR", "COMMAND"))
                processes.sortedBy { it.pid }.forEach { proc ->
                    val uid = getUidForPid(proc.pid)
                    val user = uidToName(uid)
                    appendLine(String.format("%-7s %-6d %-6d %-5s %-8s %-8s %-6d %s",
                        user,
                        proc.pid,
                        proc.ppid,
                        proc.state,
                        formatMemory(proc.rssBytes),
                        formatMemory(proc.vsizeBytes),
                        proc.numThreads,
                        proc.comm
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list processes", e)
            "Error: ${e.message}"
        }
    }

    // ---- Top processes ----

    /**
     * Show top processes by CPU usage.
     */
    fun top(count: Int = 20): String {
        return try {
            // Take two snapshots to calculate CPU usage
            val snapshot1 = getAllProcessCpuSnapshots()
            Thread.sleep(1000)
            val snapshot2 = getAllProcessCpuSnapshots()

            val totalDelta = (snapshot2.values.sumOf { it.utime + it.stime } -
                snapshot1.values.sumOf { it.utime + it.stime }).coerceAtLeast(1)

            val cpuUsages = mutableMapOf<Int, Double>()
            for ((pid, snap2) in snapshot2) {
                val snap1 = snapshot1[pid]
                if (snap1 != null) {
                    val processDelta = (snap2.utime + snap2.stime) - (snap1.utime + snap1.stime)
                    cpuUsages[pid] = (processDelta.toDouble() / totalDelta.toDouble()) * 100.0
                }
            }

            // Get process info for top processes
            val topPids = cpuUsages.entries.sortedByDescending { it.value }.take(count)

            buildString {
                appendLine("=== Top $count Processes by CPU ===")
                appendLine(String.format("%-6s %-7s %-8s %-8s %-6s %-6s %s",
                    "PID", "USER", "CPU%", "MEM", "RSS", "THR", "COMMAND"))

                for ((pid, cpu) in topPids) {
                    val proc = getProcessInfo(pid)
                    if (proc != null) {
                        val uid = getUidForPid(pid)
                        val user = uidToName(uid)
                        appendLine(String.format("%-6d %-7s %-8.1f %-8s %-6s %-6d %s",
                            pid, user, cpu, formatMemory(proc.rssBytes),
                            formatMemoryShort(proc.rssBytes), proc.numThreads, proc.comm))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get top processes", e)
            "Error: ${e.message}"
        }
    }

    // ---- Process info ----

    /**
     * Show detailed information about a specific process.
     */
    fun info(pid: Int): String {
        return try {
            val procDir = File("/proc/$pid")
            if (!procDir.exists()) return "Error: Process $pid not found"

            val stat = getProcessInfo(pid)
                ?: return "Error: Could not read stat for process $pid"

            val status = getProcessStatus(pid)
            val cmdline = getCmdline(pid)
            val environ = getEnviron(pid)
            val cwd = readProcFile("/proc/$pid/cwd")
            val exe = readProcLink("/proc/$pid/exe")
            val fdCount = countFileDescriptors(pid)
            val cpuPercent = estimateCpuUsage(pid, stat)

            buildString {
                appendLine("=== Process $pid Info ===")
                appendLine("Name:                ${stat.comm}")
                appendLine("PID:                 ${stat.pid}")
                appendLine("PPID:                ${stat.ppid}")
                appendLine("State:               ${stat.stateString}")
                appendLine("Command:             ${cmdline ?: stat.comm}")
                appendLine("Executable:          ${exe ?: "N/A"}")
                appendLine("Working Dir:         ${cwd ?: "N/A"}")
                appendLine("Threads:             ${stat.numThreads}")
                appendLine("Priority:            ${stat.priority}")
                appendLine("Nice:                ${stat.nice}")
                appendLine("CPU Usage:           ${"%.1f".format(cpuPercent)}%")

                if (status != null) {
                    appendLine("UID:                 ${status.uid} (${uidToName(status.uid)})")
                    appendLine("GID:                 ${status.gid}")
                    appendLine("TGID:                ${status.tgid}")
                    appendLine("Tracer PID:          ${if (status.tracerPid == 0) "None" else status.tracerPid.toString()}")
                    appendLine("FD Size:             ${status.fdSize}")
                    appendLine("File Descriptors:    $fdCount")
                    appendLine("VM Peak:             ${formatMemory(status.vmPeak)}")
                    appendLine("VM Size:             ${formatMemory(status.vmSize)}")
                    appendLine("VM RSS:              ${formatMemory(status.vmRSS)}")
                    appendLine("VM Data:             ${formatMemory(status.vmData)}")
                    appendLine("VM Stack:            ${formatMemory(status.vmStk)}")
                    appendLine("Voluntary Switches:  ${status.voluntaryCtxtSwitches}")
                    appendLine("Involuntary Switches:${status.nonVoluntaryCtxtSwitches}")
                } else {
                    appendLine("RSS:                 ${formatMemory(stat.rssBytes)}")
                    appendLine("VSZ:                 ${formatMemory(stat.vsizeBytes)}")
                }

                appendLine("Page Faults (minor): ${stat.minflt}")
                appendLine("Page Faults (major): ${stat.majflt}")
                appendLine("User Time:           ${stat.utime} ticks")
                appendLine("System Time:         ${stat.stime} ticks")

                // Show environment variables (truncated)
                if (environ.isNotEmpty()) {
                    appendLine()
                    appendLine("Environment (${environ.size} vars):")
                    environ.take(20).forEach { appendLine("  $it") }
                    if (environ.size > 20) {
                        appendLine("  ... and ${environ.size - 20} more")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get info for pid $pid", e)
            "Error: ${e.message}"
        }
    }

    // ---- Kill process ----

    /**
     * Send a signal to a process.
     * @param pid Process ID
     * @param signal Signal number (default: SIGTERM=15)
     */
    fun kill(pid: Int, signal: Int = 15): String {
        return try {
            val procDir = File("/proc/$pid")
            if (!procDir.exists()) return "Error: Process $pid not found"

            val proc = getProcessInfo(pid)
            val name = proc?.comm ?: "pid-$pid"

            val result = android.os.Process.sendSignal(pid, signal)

            val signalName = signalToName(signal)
            Log.i(TAG, "Sent $signalName to $name ($pid)")

            "Sent $signalName ($signal) to $name ($pid)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill pid $pid", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Kill all processes matching a name.
     */
    fun killAll(name: String, signal: Int = 15): String {
        return try {
            val processes = getAllProcesses().filter {
                it.comm.contains(name, ignoreCase = true)
            }

            if (processes.isEmpty()) return "No processes found matching '$name'"

            val signalName = signalToName(signal)
            for (proc in processes) {
                try {
                    android.os.Process.sendSignal(proc.pid, signal)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to signal ${proc.pid}", e)
                }
            }

            "Sent $signalName to ${processes.size} process(es) matching '$name'"
        } catch (e: Exception) {
            Log.e(TAG, "killall failed for $name", e)
            "Error: ${e.message}"
        }
    }

    // ---- Process tree ----

    /**
     * Display the process tree.
     */
    fun tree(pid: Int? = null, maxDepth: Int = 10): String {
        return try {
            val processes = getAllProcesses()
            if (processes.isEmpty()) return "No processes found"

            val byPpid = processes.groupBy { it.ppid }
            val rootPid = pid ?: 1

            val output = StringBuilder()
            output.appendLine("=== Process Tree (root: $rootPid) ===")
            printTree(output, rootPid, byPpid, "", true, 0, maxDepth)
            output.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Process tree failed", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Recursively print the process tree.
     */
    private fun printTree(
        output: StringBuilder,
        pid: Int,
        byPpid: Map<Int, List<ProcessInfo>>,
        prefix: String,
        isLast: Boolean,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return

        val proc = getProcessInfo(pid)
        if (proc == null) {
            output.appendLine("$prefix[pid=$pid ???]")
            return
        }

        val connector = if (isLast) "└── " else "├── "
        val uid = getUidForPid(pid)
        val user = uidToName(uid)
        val cpuEst = String.format("%.1f%%", estimateCpuUsage(pid, proc))

        if (depth == 0) {
            output.appendLine("${proc.comm} ($pid) [${proc.state}] $cpuEst")
        } else {
            output.appendLine("$prefix$connector${proc.comm} ($pid) [${proc.state}] $cpuEst")
        }

        val children = byPpid[pid] ?: emptyList()
        val childPrefix = if (depth == 0) prefix else {
            if (isLast) "$prefix    " else "$prefix│   "
        }

        for ((index, child) in children.sortedBy { it.comm }.withIndex()) {
            val childIsLast = index == children.size - 1
            printTree(output, child.pid, byPpid, childPrefix, childIsLast, depth + 1, maxDepth)
        }
    }

    // ---- Threads ----

    /**
     * List threads of a process.
     */
    fun threads(pid: Int): String {
        return try {
            val taskDir = File("/proc/$pid/task")
            if (!taskDir.exists()) return "Error: Cannot access threads for process $pid"

            val threadDirs = taskDir.listFiles()?.filter { it.isDirectory }?.sortedBy {
                it.name.toIntOrNull() ?: 0
            } ?: return "Error: No threads found for process $pid"

            val proc = getProcessInfo(pid)
            val procName = proc?.comm ?: "pid-$pid"

            buildString {
                appendLine("=== Threads of $procName ($pid) — ${threadDirs.size} threads ===")
                appendLine(String.format("%-8s %-8s %-5s %-8s %-8s %s",
                    "TID", "PID", "S", "CPU%", "RSS", "COMM"))

                for (threadDir in threadDirs) {
                    val tid = threadDir.name.toIntOrNull() ?: continue
                    val threadStat = getProcessInfo(pid, tid)
                    if (threadStat != null) {
                        val cpuEst = String.format("%.1f%%", estimateCpuUsage(tid, threadStat))
                        appendLine(String.format("%-8d %-8d %-5s %-8s %-8s %s",
                            tid, pid, threadStat.state, cpuEst,
                            formatMemoryShort(threadStat.rssBytes), threadStat.comm))
                    } else {
                        appendLine(String.format("%-8d %-8d %-5s %-8s %-8s %s",
                            tid, pid, "?", "?", "?", "?"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list threads for pid $pid", e)
            "Error: ${e.message}"
        }
    }

    // ---- Memory summary ----

    /**
     * Show system memory usage summary.
     */
    fun mem(): String {
        return try {
            val memInfo = readMemInfo()
            if (memInfo.isEmpty()) return "Error: Could not read /proc/meminfo"

            val total = memInfo["MemTotal"] ?: 0L
            val free = memInfo["MemFree"] ?: 0L
            val available = memInfo["MemAvailable"] ?: 0L
            val buffers = memInfo["Buffers"] ?: 0L
            val cached = memInfo["Cached"] ?: 0L
            val swapTotal = memInfo["SwapTotal"] ?: 0L
            val swapFree = memInfo["SwapFree"] ?: 0L
            val swapCached = memInfo["SwapCached"] ?: 0L
            val shmem = memInfo["Shmem"] ?: 0L
            val slab = memInfo["Slab"] ?: 0L
            val used = total - free - buffers - cached
            val swapUsed = swapTotal - swapFree

            val topMemProcesses = getAllProcesses()
                .sortedByDescending { it.rssBytes }
                .take(15)

            buildString {
                appendLine("=== Memory Usage ===")
                appendLine("Total:       ${formatMemoryKB(total)}")
                appendLine("Used:        ${formatMemoryKB(used)}  (${(used * 100 / total)}%)")
                appendLine("Free:        ${formatMemoryKB(free)}")
                appendLine("Available:   ${formatMemoryKB(available)}")
                appendLine("Buffers:     ${formatMemoryKB(buffers)}")
                appendLine("Cached:      ${formatMemoryKB(cached)}")
                appendLine("Shared:      ${formatMemoryKB(shmem)}")
                appendLine("Slab:        ${formatMemoryKB(slab)}")
                appendLine()
                if (swapTotal > 0) {
                    appendLine("Swap Total:  ${formatMemoryKB(swapTotal)}")
                    appendLine("Swap Used:   ${formatMemoryKB(swapUsed)}  (${if (swapTotal > 0) (swapUsed * 100 / swapTotal) else 0}%)")
                    appendLine("Swap Free:   ${formatMemoryKB(swapFree)}")
                    appendLine("Swap Cached: ${formatMemoryKB(swapCached)}")
                } else {
                    appendLine("Swap:        None")
                }
                appendLine()
                appendLine("=== Top Memory Consumers ===")
                appendLine(String.format("%-7s %-6s %-10s %s", "USER", "PID", "RSS", "COMMAND"))
                topMemProcesses.forEach { proc ->
                    val uid = getUidForPid(proc.pid)
                    val user = uidToName(uid)
                    appendLine(String.format("%-7s %-6d %-10s %s",
                        user, proc.pid, formatMemory(proc.rssBytes), proc.comm))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Memory summary failed", e)
            "Error: ${e.message}"
        }
    }

    // ---- CPU summary ----

    /**
     * Show CPU usage summary.
     */
    fun cpu(): String {
        return try {
            val cpuInfo1 = readCpuInfo()
            Thread.sleep(1000)
            val cpuInfo2 = readCpuInfo()

            val totalDelta = cpuInfo2.total - cpuInfo1.total
            val idleDelta = cpuInfo2.idle - cpuInfo1.idle
            val usagePercent = if (totalDelta > 0) {
                ((totalDelta - idleDelta) * 100.0 / totalDelta)
            } else 0.0

            // Per-core usage
            val coreUsages = mutableListOf<Double>()
            for (i in cpuInfo1.cores.indices) {
                val core1 = cpuInfo1.cores.getOrNull(i)
                val core2 = cpuInfo2.cores.getOrNull(i)
                if (core1 != null && core2 != null) {
                    val coreTotal = core2.total - core1.total
                    val coreIdle = core2.idle - core1.idle
                    coreUsages.add(if (coreTotal > 0) ((coreTotal - coreIdle) * 100.0 / coreTotal) else 0.0)
                }
            }

            // Load averages
            val loadAvg = readLoadAvg()

            // CPU frequencies
            val frequencies = readCpuFrequencies()

            // Uptime
            val uptime = readUptime()

            buildString {
                appendLine("=== CPU Usage ===")
                appendLine("Overall:     ${"%.1f".format(usagePercent)}%")
                appendLine("Cores:       ${cpuInfo1.cores.size}")

                if (coreUsages.isNotEmpty()) {
                    appendLine()
                    appendLine("Per-Core Usage:")
                    coreUsages.forEachIndexed { i, usage ->
                        val freq = frequencies.getOrNull(i)
                        val freqStr = if (freq != null) " @ ${freq}MHz" else ""
                        val bar = buildBar(usage, 20)
                        appendLine("  CPU$i: ${"%.1f".format(usage)}%$freqStr $bar")
                    }
                }

                if (loadAvg != null) {
                    appendLine()
                    appendLine("Load Average:  ${loadAvg.joinToString("  ") { "%.2f".format(it) }}")
                }

                appendLine()
                appendLine("Uptime:       $uptime")
            }
        } catch (e: Exception) {
            Log.e(TAG, "CPU summary failed", e)
            "Error: ${e.message}"
        }
    }

    // ---- Find process ----

    /**
     * Find processes by name pattern.
     */
    fun find(name: String): String {
        return try {
            val processes = getAllProcesses().filter {
                it.comm.contains(name, ignoreCase = true) ||
                getCmdline(it.pid)?.contains(name, ignoreCase = true) == true
            }

            if (processes.isEmpty()) return "No processes found matching '$name'"

            buildString {
                appendLine("=== Processes matching '$name' (${processes.size}) ===")
                appendLine(String.format("%-7s %-6s %-6s %-5s %-8s %-6s %s",
                    "USER", "PID", "PPID", "S", "RSS", "THR", "COMMAND"))
                processes.forEach { proc ->
                    val uid = getUidForPid(proc.pid)
                    val user = uidToName(uid)
                    val cmdline = getCmdline(proc.pid) ?: proc.comm
                    appendLine(String.format("%-7s %-6d %-6d %-5s %-8s %-6d %s",
                        user, proc.pid, proc.ppid, proc.state,
                        formatMemory(proc.rssBytes), proc.numThreads, cmdline))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Find process failed", e)
            "Error: ${e.message}"
        }
    }

    // ---- Children ----

    /**
     * List child processes of a given PID.
     */
    fun children(pid: Int): String {
        return try {
            val processes = getAllProcesses().filter { it.ppid == pid }

            if (processes.isEmpty()) return "Process $pid has no child processes"

            val proc = getProcessInfo(pid)
            val name = proc?.comm ?: "pid-$pid"

            buildString {
                appendLine("=== Children of $name ($pid) — ${processes.size} children ===")
                appendLine(String.format("%-7s %-6s %-5s %-8s %-6s %s",
                    "USER", "PID", "S", "RSS", "THR", "COMMAND"))
                processes.forEach { child ->
                    val uid = getUidForPid(child.pid)
                    val user = uidToName(uid)
                    appendLine(String.format("%-7s %-6d %-5s %-8s %-6d %s",
                        user, child.pid, child.state,
                        formatMemory(child.rssBytes), child.numThreads, child.comm))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Children lookup failed for pid $pid", e)
            "Error: ${e.message}"
        }
    }

    // ---- Watch ----

    /**
     * Continuous monitoring — returns a single snapshot.
     * For actual continuous output, call this in a loop from the shell.
     * @param sort Sort method: "cpu" (default), "mem", "pid"
     */
    fun watch(sort: String = "cpu"): String {
        return try {
            val processes = getAllProcesses()

            val memInfo = readMemInfo()
            val totalMem = memInfo["MemTotal"] ?: 0L
            val usedMem = totalMem - (memInfo["MemFree"] ?: 0L) - (memInfo["Buffers"] ?: 0L) - (memInfo["Cached"] ?: 0L)
            val memPercent = if (totalMem > 0) (usedMem * 100 / totalMem) else 0

            val sorted = when (sort.lowercase()) {
                "mem" -> processes.sortedByDescending { it.rssBytes }
                "pid" -> processes.sortedBy { it.pid }
                else -> processes.sortedByDescending { it.totalTime }
            }.take(25)

            val loadAvg = readLoadAvg()
            val uptime = readUptime()

            buildString {
                appendLine("═══ TermX Process Monitor ═══  ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}")
                appendLine("Mem: ${formatMemoryKB(usedMem)}/${formatMemoryKB(totalMem)} ($memPercent%)  " +
                    "Tasks: ${processes.size}  Uptime: $uptime" +
                    (loadAvg?.let { "  Load: ${"%.2f".format(it[0])}" } ?: ""))
                appendLine(String.format("%-7s %-6s %-6s %-5s %-8s %-6s %-6s %s",
                    "USER", "PID", "PPID", "S", "RSS", "VSZ", "THR", "COMMAND"))

                sorted.forEach { proc ->
                    val uid = getUidForPid(proc.pid)
                    val user = uidToName(uid)
                    appendLine(String.format("%-7s %-6d %-6d %-5s %-8s %-6s %-6d %s",
                        user, proc.pid, proc.ppid, proc.state,
                        formatMemoryShort(proc.rssBytes),
                        formatMemoryShort(proc.vsizeBytes),
                        proc.numThreads, proc.comm))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watch failed", e)
            "Error: ${e.message}"
        }
    }

    // ---- /proc reading helpers ----

    /**
     * Get all process info objects from /proc.
     */
    private fun getAllProcesses(): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        val dirs = PROC_DIR.listFiles { file -> file.isDirectory && file.name.matches(Regex("\\d+")) }
            ?: return emptyList()

        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            getProcessInfo(pid)?.let { processes.add(it) }
        }
        return processes
    }

    /**
     * Read and parse /proc/[pid]/stat for a process.
     */
    private fun getProcessInfo(pid: Int, tid: Int? = null): ProcessInfo? {
        val statFile = if (tid != null && tid != pid) {
            File("/proc/$pid/task/$tid/stat")
        } else {
            File("/proc/$pid/stat")
        }
        val line = readProcFile(statFile.absolutePath) ?: return null
        return ProcessInfo.fromStat(line)
    }

    /**
     * Read and parse /proc/[pid]/status for detailed info.
     */
    private fun getProcessStatus(pid: Int): ProcessStatus? {
        return try {
            val content = readProcFile("/proc/$pid/status") ?: return null
            val fields = mutableMapOf<String, String>()
            content.lines().forEach { line ->
                val colon = line.indexOf(':')
                if (colon > 0) {
                    fields[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
                }
            }

            ProcessStatus(
                pid = fields["Pid"]?.toIntOrNull() ?: pid,
                name = fields["Name"] ?: "unknown",
                state = fields["State"] ?: "?",
                tgid = fields["Tgid"]?.toIntOrNull() ?: pid,
                ppid = fields["PPid"]?.toIntOrNull() ?: 0,
                tracerPid = fields["TracerPid"]?.split("\\s".toRegex())?.firstOrNull()?.toIntOrNull() ?: 0,
                uid = fields["Uid"]?.split("\\s".toRegex())?.firstOrNull()?.toIntOrNull() ?: 0,
                gid = fields["Gid"]?.split("\\s".toRegex())?.firstOrNull()?.toIntOrNull() ?: 0,
                fdSize = fields["FDSize"]?.toIntOrNull() ?: 0,
                vmPeak = parseMemValue(fields["VmPeak"]),
                vmSize = parseMemValue(fields["VmSize"]),
                vmRSS = parseMemValue(fields["VmRSS"]),
                vmData = parseMemValue(fields["VmData"]),
                vmStk = parseMemValue(fields["VmStk"]),
                threads = fields["Threads"]?.toIntOrNull() ?: 0,
                voluntaryCtxtSwitches = fields["voluntary_ctxt_switches"]?.toLongOrNull() ?: 0,
                nonVoluntaryCtxtSwitches = fields["nonvoluntary_ctxt_switches"]?.toLongOrNull() ?: 0
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get process command line from /proc/[pid]/cmdline.
     */
    private fun getCmdline(pid: Int): String? {
        val raw = readProcFileBytes("/proc/$pid/cmdline") ?: return null
        if (raw.isEmpty()) return null
        return raw.joinToString(" ") { if (it == 0.toByte()) ' ' else it.toInt().toChar() }.trim()
    }

    /**
     * Get environment variables from /proc/[pid]/environ.
     */
    private fun getEnviron(pid: Int): List<String> {
        val raw = readProcFileBytes("/proc/$pid/environ") ?: return emptyList()
        return raw.joinToString("") { if (it == 0.toByte()) '\n' else it.toInt().toChar() }
            .split("\n").filter { it.isNotBlank() }
    }

    /**
     * Count file descriptors for a process.
     */
    private fun countFileDescriptors(pid: Int): Int {
        return try {
            val fdDir = File("/proc/$pid/fd")
            fdDir.listFiles()?.size ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Get UID for a process.
     */
    private fun getUidForPid(pid: Int): Int {
        return try {
            val status = readProcFile("/proc/$pid/status")
            status?.let {
                val uidLine = it.lines().find { l -> l.startsWith("Uid:") }
                uidLine?.substring(4)?.trim()?.split("\\s+".toRegex())?.firstOrNull()?.toIntOrNull() ?: -1
            } ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Read /proc/meminfo as a map.
     */
    private fun readMemInfo(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        try {
            val content = readProcFile("/proc/meminfo") ?: return emptyMap()
            content.lines().forEach { line ->
                val parts = line.split(":")
                if (parts.size >= 2) {
                    val key = parts[0].trim()
                    val valueStr = parts[1].trim().split("\\s+".toRegex()).firstOrNull() ?: "0"
                    result[key] = valueStr.toLongOrNull() ?: 0L
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to read meminfo", e)
        }
        return result
    }

    /**
     * Read CPU info from /proc/stat.
     */
    private fun readCpuInfo(): CpuAggregateInfo {
        return try {
            val content = readProcFile("/proc/stat") ?: return CpuAggregateInfo(0, 0, emptyList())
            val lines = content.lines()
            val cores = mutableListOf<CpuAggregateInfo>()

            var total = 0L
            var idle = 0L

            for (line in lines) {
                val parts = line.split("\\s+".toRegex())
                if (parts.isEmpty()) continue

                if (parts[0] == "cpu") {
                    // Aggregate: cpu  user nice system idle iowait irq softirq steal guest guest_nice
                    val values = parts.drop(1).map { it.toLongOrNull() ?: 0L }
                    total = values.sum()
                    idle = values.getOrElse(3) { 0L }
                } else if (parts[0].startsWith("cpu")) {
                    val values = parts.drop(1).map { it.toLongOrNull() ?: 0L }
                    cores.add(CpuAggregateInfo(values.sum(), values.getOrElse(3) { 0L }, emptyList()))
                }
            }

            CpuAggregateInfo(total, idle, cores)
        } catch (e: Exception) {
            CpuAggregateInfo(0, 0, emptyList())
        }
    }

    data class CpuAggregateInfo(val total: Long, val idle: Long, val cores: List<CpuAggregateInfo>)

    /**
     * Read load average from /proc/loadavg.
     */
    private fun readLoadAvg(): DoubleArray? {
        return try {
            val content = readProcFile("/proc/loadavg") ?: return null
            val parts = content.split("\\s+".toRegex())
            doubleArrayOf(
                parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
                parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
                parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Read CPU frequencies from sysfs.
     */
    private fun readCpuFrequencies(): List<Int> {
        val freqs = mutableListOf<Int>()
        var i = 0
        while (true) {
            val freqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            if (!freqFile.exists()) break
            try {
                val freq = freqFile.readText().trim().toIntOrNull()
                freqs.add((freq?.div(1000)) ?: 0)  // Convert kHz to MHz
            } catch (e: Exception) {
                freqs.add(0)
            }
            i++
        }
        return freqs
    }

    /**
     * Read system uptime.
     */
    private fun readUptime(): String {
        return try {
            val content = readProcFile("/proc/uptime") ?: return "unknown"
            val seconds = content.split("\\s+".toRegex()).firstOrNull()?.toDoubleOrNull() ?: return "unknown"
            val hrs = (seconds / 3600).toInt()
            val mins = ((seconds % 3600) / 60).toInt()
            val secs = (seconds % 60).toInt()
            when {
                hrs > 24 -> "${hrs / 24}d ${hrs % 24}h"
                hrs > 0 -> "${hrs}h ${mins}m"
                mins > 0 -> "${mins}m ${secs}s"
                else -> "${secs}s"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Get CPU snapshots for all processes.
     */
    private fun getAllProcessCpuSnapshots(): Map<Int, ProcessCpuSnapshot> {
        val snapshots = mutableMapOf<Int, ProcessCpuSnapshot>()
        val now = System.currentTimeMillis()
        val dirs = PROC_DIR.listFiles { file -> file.isDirectory && file.name.matches(Regex("\\d+")) }
            ?: return emptyMap()

        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val stat = getProcessInfo(pid) ?: continue
            snapshots[pid] = ProcessCpuSnapshot(pid, stat.utime, stat.stime, now)
        }
        return snapshots
    }

    /**
     * Estimate CPU usage for a process (rough approximation).
     */
    private fun estimateCpuUsage(pid: Int, stat: ProcessInfo): Double {
        return try {
            val uptimeContent = readProcFile("/proc/uptime")
            val uptimeSec = uptimeContent?.split("\\s+".toRegex())?.firstOrNull()?.toDoubleOrNull() ?: 0.0
            if (uptimeSec <= 0.0) return 0.0

            val clockTicksPerSec = sysconfClkTck()
            val totalTimeSec = (stat.utime + stat.stime).toDouble() / clockTicksPerSec
            val elapsedSec = uptimeSec - (stat.starttime.toDouble() / clockTicksPerSec)

            if (elapsedSec > 0) (totalTimeSec / elapsedSec) * 100.0 else 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    /**
     * Get the system clock ticks per second (typically 100 on Android/Linux).
     */
    private fun sysconfClkTck(): Long {
        return try {
            val content = readProcFile("/proc/stat") ?: return 100L
            // On Linux/Android, USER_HZ is typically 100
            100L
        } catch (e: Exception) {
            100L
        }
    }

    /**
     * Read a file from the /proc filesystem.
     */
    private fun readProcFile(path: String): String? {
        return try {
            File(path).readText().trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Read a file from /proc as raw bytes.
     */
    private fun readProcFileBytes(path: String): ByteArray? {
        return try {
            File(path).readBytes()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Read a symbolic link from /proc.
     */
    private fun readProcLink(path: String): String? {
        return try {
            val target = File(path).canonicalPath
            if (target != path) target else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a memory value like "12345 kB" to bytes.
     */
    private fun parseMemValue(value: String?): Long {
        if (value == null) return 0L
        val parts = value.split("\\s+".toRegex())
        val num = parts.getOrNull(0)?.toLongOrNull() ?: return 0L
        val unit = parts.getOrNull(1) ?: "kB"
        return when (unit.lowercase()) {
            "kb", "kib" -> num * 1024
            "mb", "mib" -> num * 1024 * 1024
            "gb", "gib" -> num * 1024 * 1024 * 1024
            "b" -> num
            else -> num * 1024  // Default to kB
        }
    }

    // ---- Formatting helpers ----

    private fun stateToText(state: Char): String = when (state) {
        'R' -> "Running"
        'S' -> "Sleeping"
        'D' -> "Disk Sleep"
        'Z' -> "Zombie"
        'T' -> "Stopped"
        't' -> "Tracing Stop"
        'X' -> "Dead"
        'I' -> "Idle"
        'P' -> "Parked"
        else -> "Unknown($state)"
    }

    private fun signalToName(signal: Int): String = when (signal) {
        1 -> "SIGHUP"
        2 -> "SIGINT"
        3 -> "SIGQUIT"
        4 -> "SIGILL"
        5 -> "SIGTRAP"
        6 -> "SIGABRT"
        7 -> "SIGBUS"
        8 -> "SIGFPE"
        9 -> "SIGKILL"
        10 -> "SIGUSR1"
        11 -> "SIGSEGV"
        12 -> "SIGUSR2"
        13 -> "SIGPIPE"
        14 -> "SIGALRM"
        15 -> "SIGTERM"
        16 -> "SIGSTKFLT"
        17 -> "SIGCHLD"
        18 -> "SIGCONT"
        19 -> "SIGSTOP"
        20 -> "SIGTSTP"
        21 -> "SIGTTIN"
        22 -> "SIGTTOU"
        23 -> "SIGURG"
        24 -> "SIGXCPU"
        25 -> "SIGXFSZ"
        26 -> "SIGVTALRM"
        27 -> "SIGPROF"
        28 -> "SIGWINCH"
        29 -> "SIGIO"
        else -> "SIG#$signal"
    }

    private fun uidToName(uid: Int): String = when (uid) {
        0 -> "root"
        1000 -> "system"
        in 10000..19999 -> "u0_a${uid - 10000}"
        2000 -> "shell"
        1013 -> "media"
        1002 -> "bluetooth"
        9997 -> "everybody"
        -1 -> "?"
        else -> "uid_$uid"
    }

    private fun formatMemory(bytes: Long): String = when {
        bytes < 0 -> "N/A"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }

    private fun formatMemoryShort(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 * 1024 -> "${bytes / 1024}K"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}M"
        else -> "${bytes / (1024 * 1024 * 1024)}G"
    }

    private fun formatMemoryKB(kb: Long): String = when {
        kb < 1024 -> "${kb}K"
        kb < 1024 * 1024 -> "${"%.1f".format(kb / 1024.0)} MB"
        else -> "${"%.1f".format(kb / (1024.0 * 1024))} GB"
    }

    /**
     * Build a visual bar for CPU usage display.
     */
    private fun buildBar(percent: Double, width: Int): String {
        val filled = (percent / 100.0 * width).toInt().coerceIn(0, width)
        return "[" + "█".repeat(filled) + "░".repeat(width - filled) + "]"
    }
}
