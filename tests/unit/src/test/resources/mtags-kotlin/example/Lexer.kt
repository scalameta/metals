   package example.lexer
//         ^^^^^^^ reference example/
//                 ^^^^^ reference example/lexer/

   /* A block comment /* with a nested comment */ fun hiddenInComment() */

   // fun hiddenInLineComment()

   val number = 1_000_000L + 0x1F + 1.5e3.toLong()
//     ^^^^^^ definition example/lexer/LexerKt# CLASS(final, static)
//     ^^^^^^ definition example/lexer/number. METHOD(val)
//     ^^^^^^ definition example/lexer/getNumber(). METHOD
//     ^^^^^^ definition example/lexer/LexerKt#number. METHOD(val, static)
//     ^^^^^^ definition example/lexer/LexerKt#getNumber(). METHOD(static)

   val template = "value: ${listOf("a", "b").joinToString { "[$it]" }} and $number"
//     ^^^^^^^^ definition example/lexer/template. METHOD(val)
//     ^^^^^^^^ definition example/lexer/getTemplate(). METHOD
//     ^^^^^^^^ definition example/lexer/LexerKt#template. METHOD(val, static)
//     ^^^^^^^^ definition example/lexer/LexerKt#getTemplate(). METHOD(static)

   val raw = """
//     ^^^ definition example/lexer/raw. METHOD(val)
//     ^^^ definition example/lexer/getRaw(). METHOD
//     ^^^ definition example/lexer/LexerKt#raw. METHOD(val, static)
//     ^^^ definition example/lexer/LexerKt#getRaw(). METHOD(static)
       fun hiddenInRawString() {
       ${ "}" } still inside the template
   """

   val quotes = """ends with a quote""""
//     ^^^^^^ definition example/lexer/quotes. METHOD(val)
//     ^^^^^^ definition example/lexer/getQuotes(). METHOD
//     ^^^^^^ definition example/lexer/LexerKt#quotes. METHOD(val, static)
//     ^^^^^^ definition example/lexer/LexerKt#getQuotes(). METHOD(static)

   val chars = listOf('{', '}', '"', '\'', 'A')
//     ^^^^^ definition example/lexer/chars. METHOD(val)
//     ^^^^^ definition example/lexer/getChars(). METHOD
//     ^^^^^ definition example/lexer/LexerKt#chars. METHOD(val, static)
//     ^^^^^ definition example/lexer/LexerKt#getChars(). METHOD(static)

   class AfterStrings {
//       ^^^^^^^^^^^^ definition example/lexer/AfterStrings# CLASS(final)
//       ^^^^^^^^^^^^ definition example/lexer/AfterStrings#`<init>`(). CONSTRUCTOR
       fun visible() = "}"
//         ^^^^^^^ definition example/lexer/AfterStrings#visible(). METHOD
   }