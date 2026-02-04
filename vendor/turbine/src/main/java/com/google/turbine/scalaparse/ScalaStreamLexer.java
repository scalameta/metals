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

import static com.google.turbine.parse.UnicodeEscapePreprocessor.ASCII_SUB;

import com.google.common.collect.ImmutableMap;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.parse.UnicodeEscapePreprocessor;
import java.util.ArrayDeque;
import java.util.Deque;
import org.jspecify.annotations.Nullable;

/** A {@link ScalaLexer} that streams input from a {@link UnicodeEscapePreprocessor}. */
public final class ScalaStreamLexer implements ScalaLexer {

  private static final ImmutableMap<String, ScalaToken> KEYWORDS = keywordMap();

  private final UnicodeEscapePreprocessor reader;

  /** The current input character. */
  private int ch;

  /** The start position of the current token. */
  private int position;

  /** The value of the current string or character literal token. */
  private String value;

  private @Nullable SavedToken trailingToken;

  private ScalaToken lastToken = ScalaToken.EOF;

  private final Deque<ScalaToken> sepRegions = new ArrayDeque<>();

  private final Deque<SavedToken> pending = new ArrayDeque<>();

  private int prevChar = ' ';

  public ScalaStreamLexer(UnicodeEscapePreprocessor reader) {
    this.reader = reader;
    eat();
  }

  @Override
  public ScalaToken next() {
    if (!pending.isEmpty()) {
      SavedToken saved = pending.removeFirst();
      applySaved(saved);
      return saved.token;
    }

    adjustSepRegions(lastToken);

    value = null;
    trailingToken = null;

    boolean sawNewline = false;
    boolean sawBlankLine = false;
    int newlineCount = 0;

    OUTER:
    while (true) {
      switch (ch) {
        case ' ', '\t', '\f' -> {
          eat();
          continue OUTER;
        }
        case '\r' -> {
          sawNewline = true;
          newlineCount++;
          eat();
          if (ch == '\n') {
            eat();
          }
          if (newlineCount >= 2) {
            sawBlankLine = true;
          }
          continue OUTER;
        }
        case '\n' -> {
          sawNewline = true;
          newlineCount++;
          eat();
          if (newlineCount >= 2) {
            sawBlankLine = true;
          }
          continue OUTER;
        }
        case '/' -> {
          int commentStart = reader.position();
          eat();
          if (ch == '/') {
            // line comment
            while (true) {
              eat();
              if (ch == '\n' || ch == '\r') {
                sawNewline = true;
                newlineCount++;
                if (newlineCount >= 2) {
                  sawBlankLine = true;
                }
                eat();
                continue OUTER;
              }
              if (ch == ASCII_SUB && reader.done()) {
                position = commentStart;
                lastToken = ScalaToken.EOF;
                return ScalaToken.EOF;
              }
            }
          }
          if (ch == '*') {
            // block comment, supports nesting
            eat();
            int depth = 1;
            while (depth > 0) {
              if (ch == ASCII_SUB && reader.done()) {
                throw error(commentStart, ErrorKind.UNCLOSED_COMMENT);
              }
              if (ch == '\r' || ch == '\n') {
                sawNewline = true;
                newlineCount++;
                if (newlineCount >= 2) {
                  sawBlankLine = true;
                }
                eat();
                continue;
              }
              if (ch == '/') {
                eat();
                if (ch == '*') {
                  depth++;
                  eat();
                  continue;
                }
                continue;
              }
              if (ch == '*') {
                eat();
                if (ch == '/') {
                  depth--;
                  eat();
                  continue;
                }
                continue;
              }
              eat();
            }
            continue OUTER;
          }

          // Not a comment, treat as operator starting with '/'
          position = commentStart;
          return finishWithNewlineHandling(sawNewline, sawBlankLine, scanOperator('/'));
        }
        default -> {
          break OUTER;
        }
      }
    }

    position = reader.position();
    if (ch == ASCII_SUB && reader.done()) {
      lastToken = ScalaToken.EOF;
      return ScalaToken.EOF;
    }

    ScalaToken token = switch (ch) {
      case '(' -> singleCharToken(ScalaToken.LPAREN);
      case ')' -> singleCharToken(ScalaToken.RPAREN);
      case '[' -> singleCharToken(ScalaToken.LBRACK);
      case ']' -> singleCharToken(ScalaToken.RBRACK);
      case '{' -> singleCharToken(ScalaToken.LBRACE);
      case '}' -> singleCharToken(ScalaToken.RBRACE);
      case ',' -> singleCharToken(ScalaToken.COMMA);
      case ';' -> singleCharToken(ScalaToken.SEMI);
      case '.' -> singleCharToken(ScalaToken.DOT);
      case '`' -> backquotedIdent();
      case '\'' -> charOrSymbolLiteral();
      case '"' -> stringLiteral();
      case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> numberLiteral();
      case '_' -> underscoreToken();
      default -> {
        if (isIdentifierStart(ch)) {
          yield identifier();
        }
        if (isOperatorPart(ch)) {
          yield operator();
        }
        throw error(position, ErrorKind.UNEXPECTED_INPUT, String.format("0x%x", ch));
      }
    };

    return finishWithNewlineHandling(sawNewline, sawBlankLine, token);
  }

  @Override
  public String stringValue() {
    return value;
  }

  @Override
  public int position() {
    return position;
  }

  @Override
  public SourceFile source() {
    return reader.source();
  }

  private ScalaToken finishWithNewlineHandling(
      boolean sawNewline, boolean sawBlankLine, ScalaToken token) {
    if (sawNewline
        && inLastOfStat(lastToken)
        && inFirstOfStat(token)
        && (sepRegions.isEmpty() || sepRegions.peek() == ScalaToken.RBRACE)) {
      pending.addLast(new SavedToken(token, value, position));
      if (trailingToken != null) {
        pending.addLast(trailingToken);
        trailingToken = null;
      }
      value = null;
      ScalaToken nl = sawBlankLine ? ScalaToken.NEWLINES : ScalaToken.NEWLINE;
      lastToken = nl;
      return nl;
    }
    lastToken = token;
    if (trailingToken != null) {
      pending.addLast(trailingToken);
      trailingToken = null;
    }
    return token;
  }

  private ScalaToken singleCharToken(ScalaToken token) {
    eat();
    return token;
  }

  private ScalaToken underscoreToken() {
    StringBuilder sb = new StringBuilder();
    sb.append('_');
    eat();
    if (isIdentifierPart(ch)) {
      appendIdentifierRest(sb);
      return finishNamed(sb);
    }
    if (isOperatorPart(ch)) {
      appendOperatorRest(sb);
      return finishNamed(sb);
    }
    value = "_";
    return ScalaToken.USCORE;
  }

  private ScalaToken identifier() {
    StringBuilder sb = new StringBuilder();
    appendIdentifierStart(sb);
    appendIdentifierRest(sb);
    return finishNamed(sb);
  }

  private ScalaToken backquotedIdent() {
    eat();
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (ch == '`') {
        eat();
        break;
      }
      if (ch == ASCII_SUB && reader.done()) {
        throw error(position, ErrorKind.UNEXPECTED_EOF);
      }
      sb.appendCodePoint(ch);
      eat();
    }
    value = sb.toString();
    return ScalaToken.BACKQUOTED_IDENT;
  }

  private ScalaToken operator() {
    return scanOperator((char) ch);
  }

  private ScalaToken scanOperator(char first) {
    StringBuilder sb = new StringBuilder();
    int before = prevChar;
    sb.append(first);
    eat();
    if (first == '<' && looksLikeXmlStart(before, ch)) {
      throw error(position, ErrorKind.UNEXPECTED_INPUT, "XML literal");
    }
    appendOperatorRest(sb);
    return finishNamed(sb);
  }

  private ScalaToken finishNamed(StringBuilder sb) {
    value = sb.toString();
    ScalaToken keyword = KEYWORDS.get(value);
    if (keyword != null) {
      return keyword;
    }
    return ScalaToken.IDENTIFIER;
  }

  private ScalaToken charOrSymbolLiteral() {
    int start = position;
    eat();
    if (isIdentifierStart(ch)) {
      StringBuilder sb = new StringBuilder();
      appendIdentifierStart(sb);
      appendIdentifierRest(sb);
      if (ch == '\'') {
        // char literal like 'a'
        eat();
        value = sb.toString();
        return ScalaToken.CHAR_LITERAL;
      }
      value = sb.toString();
      return ScalaToken.SYMBOL_LITERAL;
    }
    if (isOperatorPart(ch) && ch != '\\') {
      StringBuilder sb = new StringBuilder();
      sb.appendCodePoint(ch);
      eat();
      appendOperatorRest(sb);
      if (ch == '\'') {
        eat();
        value = sb.toString();
        return ScalaToken.CHAR_LITERAL;
      }
      value = sb.toString();
      return ScalaToken.SYMBOL_LITERAL;
    }

    if (ch == '\'' ) {
      throw error(start, ErrorKind.EMPTY_CHARACTER_LITERAL);
    }

    StringBuilder sb = new StringBuilder();
    if (ch == '\\') {
      eat();
      sb.append(escapeChar());
    } else {
      sb.appendCodePoint(ch);
      eat();
    }
    if (ch != '\'') {
      throw error(start, ErrorKind.UNTERMINATED_CHARACTER_LITERAL);
    }
    eat();
    value = sb.toString();
    return ScalaToken.CHAR_LITERAL;
  }

  private ScalaToken stringLiteral() {
    int start = position;
    eat();
    if (ch == '"') {
      eat();
      if (ch == '"') {
        eat();
        StringBuilder sb = new StringBuilder();
        readTripleQuotedString(sb);
        value = sb.toString();
        return ScalaToken.STRING_LITERAL;
      }
      value = "";
      return ScalaToken.STRING_LITERAL;
    }
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (ch == '"') {
        eat();
        value = sb.toString();
        return ScalaToken.STRING_LITERAL;
      }
      if (ch == '\n' || ch == '\r') {
        throw error(start, ErrorKind.UNTERMINATED_STRING);
      }
      if (ch == ASCII_SUB && reader.done()) {
        throw error(start, ErrorKind.UNTERMINATED_STRING);
      }
      if (ch == '\\') {
        eat();
        sb.append(escapeChar());
      } else {
        sb.appendCodePoint(ch);
        eat();
      }
    }
  }

  private void readTripleQuotedString(StringBuilder sb) {
    int quoteCount = 0;
    while (true) {
      if (ch == ASCII_SUB && reader.done()) {
        throw error(position, ErrorKind.UNTERMINATED_STRING);
      }
      if (ch == '"') {
        quoteCount++;
        eat();
        if (quoteCount == 3) {
          return;
        }
        continue;
      }
      while (quoteCount > 0) {
        sb.append('"');
        quoteCount--;
      }
      sb.appendCodePoint(ch);
      eat();
    }
  }

  private char escapeChar() {
    return switch (ch) {
      case 'b' -> {
        eat();
        yield '\b';
      }
      case 't' -> {
        eat();
        yield '\t';
      }
      case 'n' -> {
        eat();
        yield '\n';
      }
      case 'f' -> {
        eat();
        yield '\f';
      }
      case 'r' -> {
        eat();
        yield '\r';
      }
      case '\\' -> {
        eat();
        yield '\\';
      }
      case '\'' -> {
        eat();
        yield '\'';
      }
      case '"' -> {
        eat();
        yield '"';
      }
      case '0' -> {
        eat();
        yield 0;
      }
      default -> {
        char result = (char) ch;
        eat();
        yield result;
      }
    };
  }

  private ScalaToken numberLiteral() {
    StringBuilder sb = new StringBuilder();
    boolean isFloat = false;
    int base = 10;
    if (ch == '0') {
      sb.append('0');
      eat();
      if (ch == 'x' || ch == 'X') {
        sb.appendCodePoint(ch);
        eat();
        base = 16;
      } else if (ch == 'b' || ch == 'B') {
        sb.appendCodePoint(ch);
        eat();
        base = 2;
      }
    }

    consumeDigits(sb, base);

    if (ch == '.') {
      eat();
      if (isDigitForBase(ch, 10)) {
        isFloat = true;
        sb.append('.');
        consumeDigits(sb, 10);
      } else {
        // keep '.' for the next token
        trailingToken = new SavedToken(ScalaToken.DOT, null, position + sb.length());
      }
    }

    if (ch == 'e' || ch == 'E') {
      isFloat = true;
      sb.appendCodePoint(ch);
      eat();
      if (ch == '+' || ch == '-') {
        sb.appendCodePoint(ch);
        eat();
      }
      consumeDigits(sb, 10);
    }

    if (ch == 'f' || ch == 'F') {
      sb.appendCodePoint(ch);
      eat();
      value = sb.toString();
      return ScalaToken.FLOAT_LITERAL;
    }
    if (ch == 'd' || ch == 'D') {
      sb.appendCodePoint(ch);
      eat();
      value = sb.toString();
      return ScalaToken.DOUBLE_LITERAL;
    }
    if (ch == 'l' || ch == 'L') {
      sb.appendCodePoint(ch);
      eat();
      value = sb.toString();
      return ScalaToken.LONG_LITERAL;
    }

    value = sb.toString();
    return isFloat ? ScalaToken.DOUBLE_LITERAL : ScalaToken.INT_LITERAL;
  }

  private void consumeDigits(StringBuilder sb, int base) {
    while (isNumberSeparator(ch) || isDigitForBase(ch, base)) {
      sb.appendCodePoint(ch);
      eat();
    }
  }

  private void appendIdentifierStart(StringBuilder sb) {
    sb.appendCodePoint(ch);
    eat();
  }

  private void appendIdentifierRest(StringBuilder sb) {
    while (isIdentifierPart(ch)) {
      sb.appendCodePoint(ch);
      eat();
    }
  }

  private void appendOperatorRest(StringBuilder sb) {
    while (isOperatorPart(ch)) {
      sb.appendCodePoint(ch);
      eat();
    }
  }

  private static boolean isIdentifierStart(int ch) {
    return ch == '_' || ch == '$' || Character.isUnicodeIdentifierStart(ch);
  }

  private static boolean isIdentifierPart(int ch) {
    return ch == '$' || Character.isUnicodeIdentifierPart(ch);
  }

  private static boolean isOperatorPart(int ch) {
    return switch (ch) {
      case '~', '!', '@', '#', '%', '^', '*', '+', '-', '<', '>', '?', ':', '=', '&', '|', '/',
          '\\' -> true;
      default -> isSpecial(ch);
    };
  }

  private static boolean isSpecial(int ch) {
    int kind = Character.getType(ch);
    return kind == Character.MATH_SYMBOL || kind == Character.OTHER_SYMBOL;
  }

  private static boolean isDigitForBase(int ch, int base) {
    if (ch == '_' || ch < 0) {
      return false;
    }
    int value = Character.digit(ch, base);
    return value >= 0;
  }

  private static boolean isNumberSeparator(int ch) {
    return ch == '_';
  }

  private boolean inFirstOfStat(ScalaToken token) {
    return switch (token) {
      case EOF,
          CATCH,
          ELSE,
          EXTENDS,
          FINALLY,
          FORSOME,
          MATCH,
          WITH,
          YIELD,
          COMMA,
          SEMI,
          NEWLINE,
          NEWLINES,
          DOT,
          COLON,
          EQUALS,
          ARROW,
          LARROW,
          SUBTYPE,
          VIEWBOUND,
          SUPERTYPE,
          HASH,
          RPAREN,
          RBRACK,
          RBRACE,
          LBRACK ->
          false;
      default -> true;
    };
  }

  private boolean inLastOfStat(ScalaToken token) {
    return switch (token) {
      case INT_LITERAL,
          LONG_LITERAL,
          FLOAT_LITERAL,
          DOUBLE_LITERAL,
          CHAR_LITERAL,
          STRING_LITERAL,
          SYMBOL_LITERAL,
          IDENTIFIER,
          BACKQUOTED_IDENT,
          THIS,
          NULL,
          TRUE,
          FALSE,
          RETURN,
          USCORE,
          TYPE,
          RPAREN,
          RBRACK,
          RBRACE ->
          true;
      default -> false;
    };
  }

  private void adjustSepRegions(ScalaToken last) {
    switch (last) {
      case LPAREN -> sepRegions.push(ScalaToken.RPAREN);
      case LBRACK -> sepRegions.push(ScalaToken.RBRACK);
      case LBRACE -> sepRegions.push(ScalaToken.RBRACE);
      case CASE -> sepRegions.push(ScalaToken.ARROW);
      case RBRACE -> {
        while (!sepRegions.isEmpty() && sepRegions.peek() != ScalaToken.RBRACE) {
          sepRegions.pop();
        }
        if (!sepRegions.isEmpty()) {
          sepRegions.pop();
        }
      }
      case RBRACK, RPAREN -> {
        if (!sepRegions.isEmpty() && sepRegions.peek() == last) {
          sepRegions.pop();
        }
      }
      case ARROW -> {
        if (!sepRegions.isEmpty() && sepRegions.peek() == last) {
          sepRegions.pop();
        }
      }
      default -> {}
    }
  }

  private void applySaved(SavedToken saved) {
    this.position = saved.position;
    this.value = saved.value;
    this.lastToken = saved.token;
  }

  private void eat() {
    prevChar = ch;
    ch = reader.next();
  }

  private static boolean looksLikeXmlStart(int previous, int next) {
    if (!isXmlPrefixChar(previous)) {
      return false;
    }
    return next == '!' || next == '?' || isXmlNameStart(next);
  }

  private static boolean isXmlPrefixChar(int ch) {
    return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '{' || ch == '('
        || ch == '>';
  }

  private static boolean isXmlNameStart(int ch) {
    return ch == '_' || Character.isLetter(ch);
  }

  private TurbineError error(int pos, ErrorKind kind, Object... args) {
    throw TurbineError.format(reader.source(), pos, kind, args);
  }

  private static ImmutableMap<String, ScalaToken> keywordMap() {
    ImmutableMap.Builder<String, ScalaToken> builder = ImmutableMap.builder();
    for (ScalaToken token : ScalaToken.values()) {
      String text = token.toString();
      if (text.startsWith("<") && text.endsWith(">")) {
        continue;
      }
      builder.put(text, token);
    }
    return builder.buildOrThrow();
  }

  private static final class SavedToken {
    final ScalaToken token;
    final @Nullable String value;
    final int position;

    SavedToken(ScalaToken token, @Nullable String value, int position) {
      this.token = token;
      this.value = value;
      this.position = position;
    }
  }
}
