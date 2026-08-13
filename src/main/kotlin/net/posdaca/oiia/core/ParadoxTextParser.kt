package net.posdaca.oiia.core

/**
 * Small, lossless-enough parser for the subset of Paradox script used by previews.
 *
 * It deliberately keeps repeated keys and bare values. Preview data frequently uses both
 * (`trait = { ... }`, `categories = { infantry }`), while mapping-based parsers lose one of them.
 */
internal object ParadoxTextParser {
    fun parse(content: String): List<ParadoxTextEntry> = Parser(tokenize(content)).parseEntries()

    private fun tokenize(content: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var line = 1
        var index = 0
        while (index < content.length) {
            val char = content[index]
            when {
                char == '\n' -> {
                    line++
                    index++
                }

                char.isWhitespace() || char == '\uFEFF' -> index++
                char == '#' -> {
                    while (index < content.length && content[index] != '\n') index++
                }

                char == '{' || char == '}' || char == '=' -> {
                    tokens.add(Token(char.toString(), line))
                    index++
                }

                char == '"' -> {
                    val startLine = line
                    index++
                    val value = StringBuilder()
                    var escaped = false
                    while (index < content.length) {
                        val current = content[index]
                        if (current == '\n') line++
                        when {
                            escaped -> {
                                value.append(current)
                                escaped = false
                            }

                            current == '\\' -> escaped = true
                            current == '"' -> {
                                index++
                                break
                            }

                            else -> value.append(current)
                        }
                        index++
                    }
                    tokens.add(Token(value.toString(), startLine))
                }

                else -> {
                    val start = index
                    val startLine = line
                    while (
                        index < content.length &&
                        !content[index].isWhitespace() &&
                        content[index] !in charArrayOf('{', '}', '=', '#')
                    ) {
                        index++
                    }
                    tokens.add(Token(content.substring(start, index), startLine))
                }
            }
        }
        return tokens
    }

    private data class Token(val text: String, val line: Int)

    private class Parser(private val tokens: List<Token>) {
        private var index = 0

        fun parseEntries(stopOnBrace: Boolean = false): List<ParadoxTextEntry> {
            val entries = mutableListOf<ParadoxTextEntry>()
            while (index < tokens.size) {
                val token = tokens[index]
                when (token.text) {
                    "}" -> {
                        index++
                        if (stopOnBrace) return entries
                    }

                    "{", "=" -> index++
                    else -> {
                        index++
                        val value = if (peek() == "=") {
                            index++
                            parseValue()
                        } else {
                            null
                        }
                        entries.add(ParadoxTextEntry(token.text, value, token.line))
                    }
                }
            }
            return entries
        }

        private fun parseValue(): ParadoxTextValue? {
            val token = tokens.getOrNull(index) ?: return null
            index++
            return when (token.text) {
                "{" -> ParadoxTextValue.Block(parseEntries(stopOnBrace = true))
                "}" -> null
                else -> ParadoxTextValue.Atom(token.text)
            }
        }

        private fun peek(): String? = tokens.getOrNull(index)?.text
    }
}

internal data class ParadoxTextEntry(
    val key: String,
    val value: ParadoxTextValue?,
    val lineNumber: Int
) {
    fun atomValue(): String? = (value as? ParadoxTextValue.Atom)?.value

    fun blockEntries(): List<ParadoxTextEntry> = (value as? ParadoxTextValue.Block)?.entries.orEmpty()

    fun scalarOrKey(): String? = atomValue() ?: key.takeIf { value == null }
}

internal sealed interface ParadoxTextValue {
    data class Atom(val value: String) : ParadoxTextValue
    data class Block(val entries: List<ParadoxTextEntry>) : ParadoxTextValue
}

internal fun List<ParadoxTextEntry>.firstEntry(key: String): ParadoxTextEntry? =
    firstOrNull { it.key.equals(key, ignoreCase = true) }

internal fun List<ParadoxTextEntry>.entries(key: String): List<ParadoxTextEntry> =
    filter { it.key.equals(key, ignoreCase = true) }

internal fun List<ParadoxTextEntry>.firstAtom(key: String): String? = firstEntry(key)?.atomValue()

internal fun ParadoxTextEntry.enumValues(): List<String> {
    atomValue()?.let { return listOf(it) }
    return blockEntries().mapNotNull { it.scalarOrKey() }
}
