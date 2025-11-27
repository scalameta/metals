/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.turbine.parse;

/** Java tokens, defined by JLS §3.8 - §3.12. */
public enum Token {
  IDENT("<identifier>"),
  LPAREN("("),
  RPAREN(")"),
  LBRACE("{"),
  RBRACE("}"),
  LBRACK("["),
  RBRACK("]"),
  EOF("<eof>"),
  SEMI(";"),
  COMMA(","),
  DOT("."),
  TRUE("true"),
  FALSE("false"),
  NULL("null"),
  ELLIPSIS("..."),
  INT_LITERAL("<int literal>"),
  LONG_LITERAL("<long literal>"),
  FLOAT_LITERAL("<float literal>"),
  DOUBLE_LITERAL("<double literal>"),
  CHAR_LITERAL("<char literal>"),
  STRING_LITERAL("<string literal>"),
  AT("@"),
  EQ("=="),
  ASSIGN("="),
  GT(">"),
  GTE(">="),
  GTGT(">>"),
  GTGTE(">>="),
  GTGTGT(">>>"),
  GTGTGTE(">>>="),
  LTLT("<<"),
  LTLTE("<<="),
  LT("<"),
  LTE("<="),
  NOT("!"),
  NOTEQ("!="),
  TILDE("~"),
  COND("?"),
  COLON(":"),
  COLONCOLON("::"),
  MINUS("-"),
  DECR("--"),
  MINUSEQ("-="),
  ARROW("->"),
  ANDAND("&&"),
  ANDEQ("&="),
  AND("&"),
  OR("|"),
  OROR("||"),
  OREQ("|="),
  PLUS("+"),
  INCR("++"),
  PLUSEQ("+="),
  MULT("*"),
  MULTEQ("*"),
  DIV("/"),
  DIVEQ("/="),
  MOD("%"),
  MODEQ("%="),
  XOR("^"),
  XOREQ("^="),
  ABSTRACT("abstract"),
  ASSERT("assert"),
  BOOLEAN("boolean"),
  BREAK("break"),
  BYTE("byte"),
  CASE("case"),
  CATCH("catch"),
  CHAR("char"),
  CLASS("class"),
  CONST("const"),
  CONTINUE("continue"),
  DEFAULT("default"),
  DO("do"),
  DOUBLE("double"),
  ELSE("else"),
  ENUM("enum"),
  EXTENDS("extends"),
  FINAL("final"),
  FINALLY("finally"),
  FLOAT("float"),
  FOR("for"),
  GOTO("goto"),
  IF("if"),
  IMPLEMENTS("implements"),
  IMPORT("import"),
  INSTANCEOF("instanceof"),
  INT("int"),
  INTERFACE("interface"),
  LONG("long"),
  NATIVE("native"),
  NEW("new"),
  PACKAGE("package"),
  PRIVATE("private"),
  PROTECTED("protected"),
  PUBLIC("public"),
  RETURN("return"),
  SHORT("short"),
  STATIC("static"),
  STRICTFP("strictfp"),
  SUPER("super"),
  SWITCH("switch"),
  SYNCHRONIZED("synchronized"),
  THIS("this"),
  THROW("throw"),
  THROWS("throws"),
  TRANSIENT("transient"),
  TRY("try"),
  VOID("void"),
  VOLATILE("volatile"),
  WHILE("while");

  private final String value;

  Token(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return value;
  }
}
