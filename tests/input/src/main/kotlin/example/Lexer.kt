package example.lexer

/* A block comment /* with a nested comment */ fun hiddenInComment() */

// fun hiddenInLineComment()

val number = 1_000_000L + 0x1F + 1.5e3.toLong()

val template = "value: ${listOf("a", "b").joinToString { "[$it]" }} and $number"

val raw = """
    fun hiddenInRawString() {
    ${ "}" } still inside the template
"""

val quotes = """ends with a quote""""

val chars = listOf('{', '}', '"', '\'', 'A')

class AfterStrings {
    fun visible() = "}"
}
