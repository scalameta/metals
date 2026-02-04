package com.google.turbine.scalaparse

import com.google.turbine.diag.SourceFile
import com.google.turbine.escape.SourceCodeEscapers
import com.google.turbine.parse.UnicodeEscapePreprocessor
import com.google.turbine.testing.TestResources
import munit.FunSuite
import scala.collection.mutable.ListBuffer

class ScalaLexerSuite extends FunSuite {
  private val cases = List("basic", "bounds", "symbols", "numbers")

  cases.foreach { name =>
    test(s"golden-$name") {
      val input = TestResources.getResource(getClass, s"testdata/lexer/$name.scala")
      val expected = TestResources.getResource(getClass, s"testdata/lexer/$name.tokens")
      assertEquals(lex(input), lines(expected))
    }
  }

  test("extends-without-body") {
    val tokens = lex("class C extends T\nobject O extends T\n")
    assertEquals(
      tokens,
      List(
        "CLASS",
        "IDENTIFIER(C)",
        "EXTENDS",
        "IDENTIFIER(T)",
        "NEWLINE",
        "OBJECT",
        "IDENTIFIER(O)",
        "EXTENDS",
        "IDENTIFIER(T)",
        "EOF"
      )
    )
  }

  test("case-class-separator") {
    val tokens = lex("case class A extends B\ncase class C extends B\n")
    assertEquals(
      tokens,
      List(
        "CASE",
        "CLASS",
        "IDENTIFIER(A)",
        "EXTENDS",
        "IDENTIFIER(B)",
        "NEWLINE",
        "CASE",
        "CLASS",
        "IDENTIFIER(C)",
        "EXTENDS",
        "IDENTIFIER(B)",
        "EOF"
      )
    )
  }

  private def lines(text: String): List[String] =
    text.split("\\R").toList

  private def lex(input: String): List[String] = {
    val lexer =
      new ScalaStreamLexer(new UnicodeEscapePreprocessor(new SourceFile(null, input)))
    val tokens = ListBuffer.empty[String]
    var token: ScalaToken = null
    do {
      token = lexer.next()
      val tokenString = token match {
        case ScalaToken.IDENTIFIER |
            ScalaToken.BACKQUOTED_IDENT |
            ScalaToken.INT_LITERAL |
            ScalaToken.LONG_LITERAL |
            ScalaToken.FLOAT_LITERAL |
            ScalaToken.DOUBLE_LITERAL |
            ScalaToken.SYMBOL_LITERAL =>
          s"${token.name()}(${lexer.stringValue()})"
        case ScalaToken.CHAR_LITERAL |
            ScalaToken.STRING_LITERAL =>
          val escaped = SourceCodeEscapers.javaCharEscaper().escape(lexer.stringValue())
          s"${token.name()}($escaped)"
        case _ => token.name()
      }
      tokens += tokenString
    } while (token != ScalaToken.EOF)
    tokens.toList
  }
}
