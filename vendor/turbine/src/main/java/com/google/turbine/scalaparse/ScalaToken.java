/*
 * Copyright 2026 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.turbine.scalaparse;

/** Scala tokens, based on the Scala 2.13 scanner. */
public enum ScalaToken {
  EOF("<eof>"),

  IDENTIFIER("<identifier>"),
  BACKQUOTED_IDENT("<backquoted identifier>"),

  INT_LITERAL("<int literal>"),
  LONG_LITERAL("<long literal>"),
  FLOAT_LITERAL("<float literal>"),
  DOUBLE_LITERAL("<double literal>"),
  CHAR_LITERAL("<char literal>"),
  STRING_LITERAL("<string literal>"),
  SYMBOL_LITERAL("<symbol literal>"),

  // Keywords
  ABSTRACT("abstract"),
  CASE("case"),
  CATCH("catch"),
  CLASS("class"),
  DEF("def"),
  DO("do"),
  ELSE("else"),
  EXTENDS("extends"),
  FALSE("false"),
  FINAL("final"),
  FINALLY("finally"),
  FOR("for"),
  FORSOME("forSome"),
  IF("if"),
  IMPLICIT("implicit"),
  IMPORT("import"),
  LAZY("lazy"),
  MATCH("match"),
  NEW("new"),
  NULL("null"),
  OBJECT("object"),
  OVERRIDE("override"),
  PACKAGE("package"),
  PRIVATE("private"),
  PROTECTED("protected"),
  RETURN("return"),
  SEALED("sealed"),
  SUPER("super"),
  THIS("this"),
  THROW("throw"),
  TRAIT("trait"),
  TRY("try"),
  TRUE("true"),
  TYPE("type"),
  VAL("val"),
  VAR("var"),
  WHILE("while"),
  WITH("with"),
  YIELD("yield"),
  MACRO("macro"),

  // Punctuation
  LPAREN("("),
  RPAREN(")"),
  LBRACK("["),
  RBRACK("]"),
  LBRACE("{"),
  RBRACE("}"),
  COMMA(","),
  SEMI(";"),
  DOT("."),
  COLON(":"),
  EQUALS("="),
  AT("@"),
  HASH("#"),
  USCORE("_"),

  // Operators with special tokenization
  ARROW("=>"),
  LARROW("<-"),
  SUBTYPE("<:"),
  SUPERTYPE(">:"),
  VIEWBOUND("<%"),

  // Newline tokens (for semicolon inference)
  NEWLINE("<newline>"),
  NEWLINES("<newlines>");

  private final String value;

  ScalaToken(String value) {
    this.value = value;
  }

  public boolean isIdentifier() {
    return this == IDENTIFIER || this == BACKQUOTED_IDENT;
  }

  public boolean isLiteral() {
    return switch (this) {
      case INT_LITERAL,
          LONG_LITERAL,
          FLOAT_LITERAL,
          DOUBLE_LITERAL,
          CHAR_LITERAL,
          STRING_LITERAL,
          SYMBOL_LITERAL ->
          true;
      default -> false;
    };
  }

  @Override
  public String toString() {
    return value;
  }
}
