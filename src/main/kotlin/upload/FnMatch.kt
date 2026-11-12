package org.kavo.uploader.upload

import java.util.concurrent.ConcurrentHashMap

/**
 * fnmatch-style matching of project-relative paths.
 * `*` matches any characters (including `/`), `?` matches one character,
 * `[a-z]` matches a character class and `[!a-z]` a negated one. Everything else,
 * including a backslash, is literal. Matching is case-sensitive.
 */
object FnMatch {
    private val compiled = ConcurrentHashMap<String, java.util.regex.Pattern>()
    private val capturing = ConcurrentHashMap<String, java.util.regex.Pattern>()

    fun matches(pattern: String, path: String): Boolean =
        compiled.computeIfAbsent(pattern) { key -> regex(key) }.matcher(path).matches()

    /**
     * For a pattern whose last segment is a bare `**`, the part of the matched path that globstar
     * matched; a file keeps only this part below the remote directory, so the path starts below the
     * last directory the pattern spells out. Greedy matching picks the last occurrence of the
     * literal segments that precede the globstar. Null when the pattern has no trailing globstar
     * or the path does not match it.
     */
    fun trailingGlobstarRemainder(pattern: String, path: String): String? {
        if (globstarHead(pattern) == null) return null
        val matcher = capturing
            .computeIfAbsent(pattern) { key -> java.util.regex.Pattern.compile(translate(globstarHead(key)!!) + "(.*)") }
            .matcher(path)
        return if (matcher.matches()) matcher.group(1) else null
    }

    /** The pattern with its trailing `**` segment turned into the literal path it forces, or null without one. */
    private fun globstarHead(pattern: String): String? =
        when {
            pattern.endsWith("/**") -> pattern.removeSuffix("/**") + "/"
            pattern == "**" -> ""
            else -> null
        }

    /**
     * Leading path segments of the pattern that contain no wildcard. Files matching the pattern
     * keep everything below this prefix, the same way they keep the path below the root of a direct
     * mapping; a pattern whose last segment is a bare `**` keeps only the globstar remainder instead.
     */
    fun literalPrefix(pattern: String): String =
        pattern.split('/')
            .takeWhile { segment -> segment.none { char -> isWildcard(char) } }
            .joinToString("/")

    /**
     * Whether a configured local path selects files by pattern. A path with a wildcard token is
     * matched with fnmatch; one without it is a direct path prefix.
     */
    fun isPattern(path: String): Boolean =
        path.any { char -> isWildcard(char) }

    /** Number of wildcard tokens in the pattern; fewer wildcards means a more concrete pattern. */
    fun wildcardCount(pattern: String): Int {
        var count = 0
        var index = 0
        while (index < pattern.length) {
            when (pattern[index]) {
                '*', '?' -> {
                    count++
                    index++
                }

                '[' -> {
                    count++
                    val end = classEnd(pattern, index)
                    index = if (end < 0) index + 1 else end + 1
                }

                else -> index++
            }
        }
        return count
    }

    private fun regex(pattern: String): java.util.regex.Pattern =
        java.util.regex.Pattern.compile(translate(pattern))

    private fun translate(pattern: String): String {
        val regex = StringBuilder()
        var literals = StringBuilder()
        var index = 0

        fun flushLiterals() {
            if (literals.isEmpty()) return
            regex.append(java.util.regex.Pattern.quote(literals.toString()))
            literals = StringBuilder()
        }

        while (index < pattern.length) {
            val char = pattern[index]
            when {
                char == '*' -> {
                    flushLiterals()
                    regex.append(".*")
                    index++
                }

                char == '?' -> {
                    flushLiterals()
                    regex.append('.')
                    index++
                }

                char == '[' -> {
                    val end = classEnd(pattern, index)
                    when {
                        end < 0 -> {
                            literals.append('[')
                            index++
                        }
                        else -> {
                            flushLiterals()
                            regex.append(charClass(pattern.substring(index + 1, end)))
                            index = end + 1
                        }
                    }
                }

                else -> {
                    literals.append(char)
                    index++
                }
            }
        }
        flushLiterals()
        return regex.toString()
    }

    private fun isWildcard(char: Char) = char == '*' || char == '?' || char == '['

    /** Index of the `]` closing the class that starts at `from`, or -1 when that `[` is literal. */
    private fun classEnd(pattern: String, from: Int): Int {
        var index = from + 1
        if (pattern.getOrNull(index) == '!') index++
        if (pattern.getOrNull(index) == ']') index++
        while (index < pattern.length && pattern[index] != ']') index++
        return if (index < pattern.length) index else -1
    }

    private fun charClass(body: String): String {
        val negated = body.startsWith("!")
        val content = if (negated) body.removePrefix("!") else body
        val builder = StringBuilder("[")
        if (negated) builder.append('^')
        content.forEach { char ->
            if (char == '\\' || char == '^' || char == ']') builder.append('\\')
            builder.append(char)
        }
        return builder.append(']').toString()
    }
}
