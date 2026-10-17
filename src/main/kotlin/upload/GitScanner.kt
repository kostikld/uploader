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

    fun isValidCommit(workDir: File, commit: String): Boolean =
        runGit(workDir, "rev-parse", "--quiet", "--verify", commit) != null

    fun filesInCommit(workDir: File, commit: String): List<Path> {
        val output = runGit(workDir, "show", "--format=", "--name-status", "-z", commit, "--") ?: return emptyList()
        val root = workDir.toPath().toAbsolutePath().normalize()
        return parseNameStatus(output).map { root.resolve(it).normalize() }
     }

    fun parseNameStatus(output: String): List<Path> {
        val fields = output.split('\u0000').filter { it.isNotEmpty() }
        val result = mutableListOf<Path>()
        var index = 0
        while (index < fields.size) {
            val status = fields[index]
            index += 1
            if (index >= fields.size) break
            val flag = status[0]
            if (flag == 'D') {
                index += 1
                continue
             }
            if (flag == 'R' || flag == 'C') {
                if (index + 1 < fields.size) result.add(Paths.get(fields[index + 1]))
                index += 2
                continue
             }
            if (flag == 'M' || flag == 'A' || flag == 'T') result.add(Paths.get(fields[index]))
            index += 1
         }
        return result
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
