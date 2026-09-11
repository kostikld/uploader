package org.kavo.uploader.upload

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths

object GitScanner {
    fun isGitRepository(workDir: File): Boolean =
        runGit(workDir, "rev-parse", "--is-inside-work-tree")?.trim() == "true"

    fun changedFiles(workDir: File): List<Path> {
        val output = runGit(workDir, "status", "--porcelain=v1", "-z") ?: return emptyList()
        val root = workDir.toPath().toAbsolutePath().normalize()
        return parsePorcelain(output).map { root.resolve(it).normalize() }
     }

    fun parsePorcelain(output: String): List<Path> {
        val result = mutableListOf<Path>()
        for (record in output.split('\u0000')) {
            for (line in record.split('\n')) {
                val trimmed = line.trimEnd('\r')
                if (trimmed.isEmpty()) continue
                if (trimmed.length < 3) continue
                val indexState = trimmed[0]
                val worktreeState = trimmed[1]
                if (!keep(indexState, worktreeState)) continue
                val path = extractPath(trimmed, indexState, worktreeState)
                if (path.isNotEmpty()) result.add(Paths.get(path))
                 }
               }
        return result
          }

    private fun keep(indexState: Char, worktreeState: Char): Boolean {
        val states = indexState.toString() + worktreeState
        if (states.contains('?') || states.contains('!') || states.contains('U')) return false
        if (states.contains('D')) return false
        return states.contains('M') ||
             states.contains('A') ||
             states.contains('R') ||
             states.contains('C') ||
             states.contains('T')
     }

    private fun extractPath(record: String, indexState: Char, worktreeState: Char): String {
        val body = record.substring(3)
        val isRenameOrCopy =
             indexState == 'R' || worktreeState == 'R' ||
             indexState == 'C' || worktreeState == 'C'
        if (!isRenameOrCopy) return body
        val separator = " -> "
        val index = body.indexOf(separator)
        return if (index >= 0) body.substring(index + separator.length) else body
     }

    private fun runGit(workDir: File, vararg args: String): String? {
        val builder = ProcessBuilder(listOf("git", "-C", workDir.absolutePath) + args.toList())
        val process = builder.start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return if (process.waitFor() == 0) output else null
     }
}
