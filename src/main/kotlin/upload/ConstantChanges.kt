package org.kavo.uploader.upload

import java.util.regex.Pattern

/**
 * A field whose value the compiler copies into the classes that read it.
 *
 * Only `static final` fields of a primitive or `String` type initialized with a constant expression are
 * inlined - interface fields are `static final` implicitly - so their value ends up in the bytecode of
 * every class using them. When such a value changes, those classes have to be uploaded again even though
 * their own source files did not change.
 */
data class ConstantDeclaration(
    val name: String,
    val type: String,
    val initializer: String,
)

/**
 * A constant whose initializer differs between the earlier and the current version of a file.
 */
data class ChangedConstant(
    val name: String,
    val oldInitializer: String,
    val newInitializer: String,
)

/**
 * What one file's diff means for the compiled output of other classes: the constants whose value differs,
 * and a one line description of that change.
 *
 * The names also cover the constants of the same file that only read a changed constant: their own text
 * stayed, but the value they stand for moved, so the classes reading them changed as well.
 */
data class ConstantFileChange(
    val constantNames: List<String>,
    val summary: String,
)

fun ConstantFileChange.isEmpty(): Boolean = constantNames.isEmpty()

private val CONSTANT_TYPES =
    setOf("boolean", "byte", "short", "char", "int", "long", "float", "double", "String")

private val modifierPattern = "(?:public|protected|private|static|final|transient|volatile)[ \t]+"

/**
 * A declaration statement that starts at the beginning of the file or after `;`, `{` or `}`:
 * `modifiers type name = expression;`.
 *
 * The initializer stops at the first `;`, `{` or `}`, which is enough for a constant expression. A statement
 * declaring several constants at once is split afterwards.
 */
private val declaration = Pattern.compile(
    "(?m)(?:^|(?<=[;{}]))[ \t]*((?:$modifierPattern)*)" +
        "(?<type>[A-Za-z_\$][A-Za-z_\$.]*)[ \t]+(?<name>[A-Za-z_\$][A-Za-z_\$0-9]*)" +
        "[ \t]*=(?<initializer>[^;{}]*)[ \t]*;",
)


private val extraDeclarator = Pattern.compile("([A-Za-z_\$][A-Za-z_\$0-9]*)[ \t]*=[ \t]*(.*)")


/**
 * Declarations of primitive and `String` constants in a Java source text.
 *
 * Deliberately loose about modifiers: the caller checks every name against the fields of the file, so an
 * extra name only costs a failed lookup, while a missing name would hide a changed constant.
 */
fun constantDeclarations(sourceText: String): Map<String, ConstantDeclaration> {
    val declarations = linkedMapOf<String, ConstantDeclaration>()
    val matcher = declaration.matcher(withoutComments(sourceText))
    while (matcher.find()) {
        val type = matcher.group("type") ?: continue
        if (!isConstantType(type)) continue
        val name = matcher.group("name") ?: continue
        val initializer = (matcher.group("initializer") ?: "").trim()
        splitTopLevel(initializer).forEachIndexed { index, piece ->
            val trimmed = piece.trim()
            var pieceName = name
            var pieceInitializer = trimmed
            if (index > 0) {
                // `static final int A = 1, B = 2;` - the match swallowed the rest of the statement.
                val extra = extraDeclarator.matcher(trimmed)
                if (extra.matches()) {
                    pieceName = extra.group(1) ?: ""
                    pieceInitializer = extra.group(2)?.trim() ?: ""
                }
            }
            if (pieceName.isNotEmpty() && pieceInitializer.isNotEmpty() && looksConstant(pieceInitializer)) {
                declarations[pieceName] = ConstantDeclaration(pieceName, type, pieceInitializer)
            }
        }
    }
    return declarations
}


/**
 * The constants of `newText` whose value differs from `oldText`, plus the constants of the same file that
 * read them.
 *
 * A file without an earlier version - a new one - has no compiled readers to fix, so it reports nothing.
 */
fun constantFileChange(oldText: String?, newText: String): ConstantFileChange {
    if (oldText == null) return ConstantFileChange(constantNames = emptyList(), summary = "")
    val previous = constantDeclarations(oldText)
    val current = constantDeclarations(newText)
    val changed = current.values.map { constant ->
        val before = previous[constant.name] ?: return@map null
        if (compareKey(before.initializer) == compareKey(constant.initializer)) null
        else ChangedConstant(constant.name, before.initializer, constant.initializer)
    }.filterNotNull()
    val names = linkedSetOf<String>()
    names.addAll(changed.map { it.name })
    var added = true
    while (added) {
        added = false
        current.values.forEach { candidate ->
            if (names.contains(candidate.name)) return@forEach
            if (names.any { name -> readsName(candidate.initializer, name) }) {
                names.add(candidate.name)
                added = true
            }
        }
    }
    return ConstantFileChange(
        constantNames = names.toList(),
        summary = changed.joinToString(", ") { "${it.name}: ${it.oldInitializer} -> ${it.newInitializer}" },
    )
}

private fun isConstantType(type: String): Boolean =
    CONSTANT_TYPES.contains(type.substringAfterLast('.').removeSuffix("[]"))

/**
 * A constant expression cannot contain a call or an object creation, which keeps fields such as
 * `static final String NAME = fetch()` out of the list: their value is read from the field at runtime, so
 * the classes reading them do not change.
 */
private fun looksConstant(initializer: String): Boolean =
    initializer.isNotEmpty() && !initializer.contains("(") && !wordPattern("new").findIn(initializer)

private fun readsName(initializer: String, name: String): Boolean =
    name.isNotEmpty() && wordPattern(name).findIn(initializer)

private fun wordPattern(word: String): Pattern = Pattern.compile("\\b${Pattern.quote(word)}\\b")

/**
 * Whether the pattern matches anywhere in the text.
 */
private fun Pattern.findIn(text: String): Boolean = matcher(text).find()


/**
 * Two initializers that differ only in spacing or line breaks still stand for the same compiled value.
 * Whitespace inside a literal is kept, since it belongs to that value.
 */
private fun compareKey(initializer: String): String {
    val builder = StringBuilder()
    var quote: Char? = null
    var escaped = false
    for (index in 0 until initializer.length) {
        val char = initializer[index]
        when {
            escaped -> {
                builder.append(char)
                escaped = false
            }
            quote != null && char == '\\' -> {
                builder.append(char)
                escaped = true
            }
            quote == null && (char == '"' || char == '\'') -> {
                builder.append(char)
                quote = char
            }
            quote != null && char == quote -> {
                builder.append(char)
                quote = null
            }
            quote == null && Character.isWhitespace(char) -> Unit
            else -> builder.append(char)
        }
    }
    return builder.toString()
}

/**
 * Splits an initializer on the commas that separate the constants of one statement.
 */
private fun splitTopLevel(initializer: String): List<String> {
    val pieces = mutableListOf<String>()
    var depth = 0
    var start = 0
    var quote: Char? = null
    var index = 0
    while (index < initializer.length) {
        val char = initializer[index]
        when {
            quote != null && char == quote -> quote = null
            quote != null -> Unit
            char == '"' || char == '\'' -> quote = char
            char == '(' || char == '[' -> depth += 1
            char == ')' || char == ']' -> depth -= 1
            char == ',' && depth == 0 -> {
                pieces.add(initializer.substring(start, index))
                start = index + 1
            }
        }
        index += 1
    }
    pieces.add(initializer.substring(start))
    return pieces
}

/**
 * The source text with comments replaced by a space, so a comment cannot be read as a declaration while
 * string literals are kept as they are.
 */
internal fun withoutComments(text: String): String {
    val builder = StringBuilder()
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val next = charAfter(text, index + 1)
        when {
            char == '/' && next == '/' -> {
                builder.append(' ')
                while (index < text.length && text[index] != '\n') index += 1
            }
            char == '/' && next == '*' -> {
                builder.append(' ')
                index += 2
                while (index < text.length) {
                    if (text[index] == '*' && charAfter(text, index + 1) == '/') {
                        index += 2
                        break
                    }
                    index += 1
                }
            }
            char == '"' || char == '\'' -> {
                builder.append(char)
                index += 1
                while (index < text.length) {
                    val current = text[index]
                    builder.append(current)
                    index += 1
                    if (current == '\\') {
                        if (index < text.length) builder.append(text[index])
                        index += 1
                    } else if (current == char) break
                }
            }
            else -> {
                builder.append(char)
                index += 1
            }
        }
    }
    return builder.toString()
}

private fun charAfter(text: String, index: Int): Char =
    if (index in 0 until text.length) text[index] else ' '
