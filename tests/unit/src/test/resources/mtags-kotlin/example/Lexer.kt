   package example.lexer
//         ^^^^^^^ reference example/
//                 ^^^^^ reference example/lexer/

   /* A block comment /* with a nested comment */ fun hiddenInComment() */

   // fun hiddenInLineComment()

   val template = "value: ${listOf("a", "b").joinToString { "[$it]" }} and $template"
//     ^^^^^^^^ definition example/lexer/template. METHOD(val)
//     ^^^^^^^^ definition example/lexer/getTemplate(). METHOD

   val raw = """
//     ^^^ definition example/lexer/raw. METHOD(val)
//     ^^^ definition example/lexer/getRaw(). METHOD
       fun hiddenInRawString() {
       ${ "}" } still inside the template
   """

   val quotes = """ends with a quote""""
//     ^^^^^^ definition example/lexer/quotes. METHOD(val)
//     ^^^^^^ definition example/lexer/getQuotes(). METHOD

   val chars = listOf('{', '}', '"', '\'', 'A')
//     ^^^^^ definition example/lexer/chars. METHOD(val)
//     ^^^^^ definition example/lexer/getChars(). METHOD

   val number = 1_000_000L + 0x1F + 1.5e3.toLong()
//     ^^^^^^ definition example/lexer/number. METHOD(val)
//     ^^^^^^ definition example/lexer/getNumber(). METHOD

   class AfterStrings {
//       ^^^^^^^^^^^^ definition example/lexer/AfterStrings# CLASS(final)
//       ^^^^^^^^^^^^ definition example/lexer/AfterStrings#`<init>`(). CONSTRUCTOR
       fun visible() = "}"
//         ^^^^^^^ definition example/lexer/AfterStrings#visible(). METHOD
   }