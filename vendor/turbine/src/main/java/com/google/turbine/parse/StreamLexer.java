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

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.turbine.parse.UnicodeEscapePreprocessor.ASCII_SUB;
import static java.lang.Math.min;

import com.google.common.collect.ImmutableList;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import org.jspecify.annotations.Nullable;

/** A {@link Lexer} that streams input from a {@link UnicodeEscapePreprocessor}. */
public class StreamLexer implements Lexer {

  private final UnicodeEscapePreprocessor reader;

  /** The current input character. */
  private int ch;

  /** The start position of the current token. */
  private int position;

  /** The start position of the current numeric literal or identifier token. */
  private int readFrom;

  /** The value of the current string or character literal token. */
  private String value = null;

  /** A saved javadoc comment. */
  private String javadoc = null;

  public StreamLexer(UnicodeEscapePreprocessor reader) {
    this.reader = reader;
    eat();
  }

  /** Records the value of a literal. */
  private void saveValue(String value) {
    this.value = value;
  }

  /** Records the start position of a literal. */
  private void readFrom() {
    value = null;
    readFrom = reader.position();
  }

  /** Consumes an input character. */
  private void eat() {
    ch = reader.next();
  }

  @Override
  public @Nullable String javadoc() {
    String result = javadoc;
    javadoc = null;
    if (result == null) {
      return null;
    }
    verify(result.endsWith("*"), result);
    return result.substring(0, result.length() - "*".length());
  }

  @Override
  public String stringValue() {
    if (value != null) {
      return value;
    }
    return reader.readString(readFrom, reader.position());
  }

  @Override
  public int position() {
    return position;
  }

  @Override
  public SourceFile source() {
    return reader.source();
  }

  @Override
  public Token next() {
    OUTER:
    while (true) {
      position = reader.position();
      switch (ch) {
        case '\r':
        case '\n':
        case ' ':
        case '\t':
        case '\f':
          eat();
          continue OUTER;

        case '/':
          {
            eat();
            switch (ch) {
              case '/':
                while (true) {
                  eat();
                  switch (ch) {
                    case '\n':
                    case '\r':
                      eat();
                      continue OUTER;
                    case ASCII_SUB:
                      if (reader.done()) {
                        return Token.EOF;
                      }
                      eat();
                      break;
                    default: // fall out
                  }
                }
              case '*':
                eat();
                boolean sawStar = false;
                boolean isJavadoc = false;
                if (ch == '*') {
                  eat();
                  // handle empty non-javadoc comments: `/**/`
                  if (ch == '/') {
                    eat();
                    continue OUTER;
                  }
                  isJavadoc = true;
                  readFrom();
                }
                while (true) {
                  switch (ch) {
                    case '*':
                      eat();
                      sawStar = true;
                      break;
                    case '/':
                      if (sawStar) {
                        if (isJavadoc) {
                          // Save the comment, excluding the leading `/**` and including
                          // the trailing `/*`. The comment is trimmed and normalized later.
                          javadoc = stringValue();
                          verify(javadoc.endsWith("*"), javadoc);
                        }
                        eat();
                        continue OUTER;
                      }
                      sawStar = false;
                      eat();
                      break;
                    case ASCII_SUB:
                      if (reader.done()) {
                        throw TurbineError.format(
                            reader.source(), position, ErrorKind.UNCLOSED_COMMENT);
                      }
                      eat();
                      sawStar = false;
                      break;
                    default:
                      eat();
                      sawStar = false;
                      break;
                  }
                }
              default:
                if (ch == '=') {
                  eat();
                  return Token.DIVEQ;
                }
                return Token.DIV;
            }
          }

        case 'a':
        case 'b':
        case 'c':
        case 'd':
        case 'e':
        case 'f':
        case 'g':
        case 'h':
        case 'i':
        case 'j':
        case 'k':
        case 'l':
        case 'm':
        case 'n':
        case 'o':
        case 'p':
        case 'q':
        case 'r':
        case 's':
        case 't':
        case 'u':
        case 'v':
        case 'w':
        case 'x':
        case 'y':
        case 'z':
        case 'A':
        case 'B':
        case 'C':
        case 'D':
        case 'E':
        case 'F':
        case 'G':
        case 'H':
        case 'I':
        case 'J':
        case 'K':
        case 'L':
        case 'M':
        case 'N':
        case 'O':
        case 'P':
        case 'Q':
        case 'R':
        case 'S':
        case 'T':
        case 'U':
        case 'V':
        case 'W':
        case 'X':
        case 'Y':
        case 'Z':
        case '_':
        case '$':
          return identifier();

        case ASCII_SUB:
          if (!reader.done()) {
            throw error(ErrorKind.UNEXPECTED_EOF);
          }
          return Token.EOF;

        case '-':
        case '=':
        case '>':
        case '<':
        case '!':
        case '~':
        case '+':
        case '?':
        case ':':
        case '*':
        case '&':
        case '|':
        case '^':
        case '%':
          return operator();
        case '(':
          eat();
          return Token.LPAREN;
        case ')':
          eat();
          return Token.RPAREN;
        case '{':
          eat();
          return Token.LBRACE;
        case '}':
          eat();
          return Token.RBRACE;
        case '[':
          eat();
          return Token.LBRACK;
        case ']':
          eat();
          return Token.RBRACK;
        case ';':
          eat();
          return Token.SEMI;
        case ',':
          eat();
          return Token.COMMA;
        case '@':
          eat();
          return Token.AT; // what about frac, etc.?

        case '0':
          {
            readFrom();
            eat();
            switch (ch) {
              case 'x':
              case 'X':
                eat();
                return hexLiteral();
              case 'b':
              case 'B':
                eat();
                return boolLiteral();
              case '0':
              case '1':
              case '2':
              case '3':
              case '4':
              case '5':
              case '6':
              case '7':
              case '_':
                return octalLiteral();
              case '.':
                eat();
                return floatLiteral();
              case 'f':
              case 'F':
                eat();
                return Token.FLOAT_LITERAL;
              case 'd':
              case 'D':
                eat();
                return Token.DOUBLE_LITERAL;
              case 'l':
              case 'L':
                eat();
                return Token.LONG_LITERAL;
              default:
                return Token.INT_LITERAL;
            }
          }
        case '1':
        case '2':
        case '3':
        case '4':
        case '5':
        case '6':
        case '7':
        case '8':
        case '9':
          readFrom();
          return decimalLiteral();
        case '.':
          {
            readFrom();
            eat();
            switch (ch) {
              case '.':
                {
                  eat();
                  if (ch == '.') {
                    eat();
                    return Token.ELLIPSIS;
                  } else {
                    throw inputError();
                  }
                }
              case '0':
              case '1':
              case '2':
              case '3':
              case '4':
              case '5':
              case '6':
              case '7':
              case '8':
              case '9':
                return floatLiteral();
              default:
                return Token.DOT;
            }
          }

        case '\'':
          {
            eat();
            char value;
            switch (ch) {
              case '\\':
                eat();
                value = escape();
                break;
              case '\'':
                throw error(ErrorKind.EMPTY_CHARACTER_LITERAL);
              default:
                value = (char) ch;
                eat();
            }
            if (ch == '\'') {
              saveValue(String.valueOf(value));
              eat();
              return Token.CHAR_LITERAL;
            }
            throw error(ErrorKind.UNTERMINATED_CHARACTER_LITERAL);
          }

        case '"':
          {
            eat();
            if (ch == '"') {
              eat();
              if (ch != '"') {
                saveValue("");
                return Token.STRING_LITERAL;
              }
              eat();
              return textBlock();
            }
            readFrom();
            StringBuilder sb = new StringBuilder();
            STRING:
            while (true) {
              switch (ch) {
                case '\\':
                  eat();
                  sb.append(escape());
                  continue STRING;
                case '"':
                  saveValue(sb.toString());
                  eat();
                  return Token.STRING_LITERAL;
                case '\n':
                  throw error(ErrorKind.UNTERMINATED_STRING);
                case ASCII_SUB:
                  if (reader.done()) {
                    return Token.EOF;
                  }
                // falls through
                default:
                  sb.appendCodePoint(ch);
                  eat();
                  continue STRING;
              }
            }
          }
        default:
          if (Character.isJavaIdentifierStart(ch)) {
            // TODO(cushon): the style guide disallows non-ascii identifiers
            return identifier();
          }
          throw inputError();
      }
    }
  }

  private Token textBlock() {
    OUTER:
    while (true) {
      switch (ch) {
        case ' ':
        case '\r':
        case '\t':
          eat();
          break;
        default:
          break OUTER;
      }
    }
    switch (ch) {
      case '\r':
        eat();
        if (ch == '\n') {
          eat();
        }
        break;
      case '\n':
        eat();
        break;
      default:
        throw inputError();
    }
    readFrom();
    StringBuilder sb = new StringBuilder();
    while (true) {
      switch (ch) {
        case '"':
          eat();
          if (ch != '"') {
            sb.append("\"");
            continue;
          }
          eat();
          if (ch != '"') {
            sb.append("\"\"");
            continue;
          }
          eat();
          String value = sb.toString();
          value = stripIndent(value);
          value = translateEscapes(value);
          saveValue(value);
          return Token.STRING_LITERAL;
        case '\\':
          // Escapes are handled later (after stripping indentation), but we need to ensure
          // that \" escapes don't count towards the closing delimiter of the text block.
          sb.appendCodePoint(ch);
          eat();
          if (ch == ASCII_SUB && reader.done()) {
            return Token.EOF;
          }
          sb.appendCodePoint(ch);
          eat();
          continue;
        case ASCII_SUB:
          if (reader.done()) {
            return Token.EOF;
          }
        // falls through
        default:
          sb.appendCodePoint(ch);
          eat();
          continue;
      }
    }
  }

  static String stripIndent(String value) {
    if (value.isEmpty()) {
      return value;
    }
    ImmutableList<String> lines = value.lines().collect(toImmutableList());
    // the amount of whitespace to strip from the beginning of every line
    int strip = Integer.MAX_VALUE;
    char last = value.charAt(value.length() - 1);
    boolean trailingNewline = last == '\n' || last == '\r';
    if (trailingNewline) {
      // If the input contains a trailing newline, we have something like:
      //
      // |String s = """
      // |    foo
      // |""";
      //
      // Because the final """ is unindented, nothing should be stripped.
      strip = 0;
    } else {
      // find the longest common prefix of whitespace across all non-blank lines
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        int nonWhitespaceStart = nonWhitespaceStart(line);
        if (nonWhitespaceStart == line.length()) {
          continue;
        }
        strip = min(strip, nonWhitespaceStart);
      }
    }
    StringBuilder result = new StringBuilder();
    boolean first = true;
    for (String line : lines) {
      if (!first) {
        result.append('\n');
      }
      int end = trailingWhitespaceStart(line);
      if (strip <= end) {
        result.append(line, strip, end);
      }
      first = false;
    }
    if (trailingNewline) {
      result.append('\n');
    }
    return result.toString();
  }

  private static int nonWhitespaceStart(String value) {
    int i = 0;
    while (i < value.length() && Character.isWhitespace(value.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int trailingWhitespaceStart(String value) {
    int i = value.length() - 1;
    while (i >= 0 && Character.isWhitespace(value.charAt(i))) {
      i--;
    }
    return i + 1;
  }

  private String translateEscapes(String value) {
    StreamLexer lexer =
        new StreamLexer(new UnicodeEscapePreprocessor(new SourceFile(null, value + ASCII_SUB)));
    try {
      return lexer.translateEscapes();
    } catch (TurbineError e) {
      // Rethrow since the source positions above are relative to the text block, not the entire
      // file. This means that diagnostics for invalid escapes in text blocks will be emitted at the
      // delimiter.
      // TODO(cushon): consider merging this into stripIndent and tracking the real position
      throw new TurbineError(
          e.diagnostics().stream()
              .map(d -> d.withPosition(reader.source(), reader.position()))
              .collect(toImmutableList()));
    }
  }

  private String translateEscapes() {
    readFrom();
    StringBuilder sb = new StringBuilder();
    OUTER:
    while (true) {
      switch (ch) {
        case '\\':
          eat();
          switch (ch) {
            case '\r':
              eat();
              if (ch == '\n') {
                eat();
              }
              break;
            case '\n':
              eat();
              break;
            default:
              sb.append(escape());
              break;
          }
          continue;
        case ASCII_SUB:
          break OUTER;
        default:
          sb.appendCodePoint(ch);
          eat();
          continue;
      }
    }
    return sb.toString();
  }

  private char escape() {
    boolean zeroToThree = false;
    switch (ch) {
      case 'b':
        eat();
        return '\b';
      case 't':
        eat();
        return '\t';
      case 'n':
        eat();
        return '\n';
      case 'f':
        eat();
        return '\f';
      case 'r':
        eat();
        return '\r';
      case 's':
        eat();
        return ' ';
      case '"':
        eat();
        return '\"';
      case '\'':
        eat();
        return '\'';
      case '\\':
        eat();
        return '\\';
      case '0':
      case '1':
      case '2':
      case '3':
        zeroToThree = true;
      // falls through
      case '4':
      case '5':
      case '6':
      case '7':
        {
          char value = (char) (ch - '0');
          eat();
          switch (ch) {
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
              {
                value = (char) ((value << 3) | (ch - '0'));
                eat();
                if (zeroToThree) {
                  switch (ch) {
                    case '0':
                    case '1':
                    case '2':
                    case '3':
                    case '4':
                    case '5':
                    case '6':
                    case '7':
                      value = (char) ((value << 3) | (ch - '0'));
                      eat();
                      return value;
                    default:
                      return value;
                  }
                }
              }
            // fall through
            default:
              return value;
          }
        }
      default:
        throw inputError();
    }
  }

  private Token decimalLiteral() {
    readDigits();
    switch (ch) {
      case 'e':
      case 'E':
        return floatLiteral();
      case '.':
        eat();
        return floatLiteral();
      case 'f':
      case 'F':
        eat();
        return Token.FLOAT_LITERAL;
      case 'd':
      case 'D':
        eat();
        return Token.DOUBLE_LITERAL;
      case 'l':
      case 'L':
        eat();
        return Token.LONG_LITERAL;
      default:
        return Token.INT_LITERAL;
    }
  }

  private Token hexFloatLiteral() {
    readHexDigits();
    switch (ch) {
      case 'p':
      case 'P':
        eat();
        signedInteger();
        break;
      default: // fall out
    }
    return floatTypeSuffix();
  }

  private Token floatLiteral() {
    if ('0' <= ch && ch <= '9') {
      readDigits();
    }
    switch (ch) {
      case 'e':
      case 'E':
        eat();
        signedInteger();
        break;
      default: // fall out
    }
    return floatTypeSuffix();
  }

  private Token floatTypeSuffix() {
    switch (ch) {
      case 'd':
      case 'D':
        eat();
        return Token.DOUBLE_LITERAL;
      case 'f':
      case 'F':
        eat();
        return Token.FLOAT_LITERAL;
      default:
        return Token.DOUBLE_LITERAL;
    }
  }

  private void signedInteger() {
    switch (ch) {
      case '-':
      case '+':
        eat();
        break;
      default:
        break;
    }
    readDigits();
  }

  private void readHexDigits() {
    switch (ch) {
      case 'A':
      case 'B':
      case 'C':
      case 'D':
      case 'E':
      case 'F':
      case 'a':
      case 'b':
      case 'c':
      case 'd':
      case 'e':
      case 'f':
      case '0':
      case '1':
      case '2':
      case '3':
      case '4':
      case '5':
      case '6':
      case '7':
      case '8':
      case '9':
        eat();
        break;
      default:
        throw inputError();
    }
    OUTER:
    while (true) {
      switch (ch) {
        case '_':
          {
            do {
              eat();
            } while (ch == '_');
            switch (ch) {
              case 'A':
              case 'B':
              case 'C':
              case 'D':
              case 'E':
              case 'F':
              case 'a':
              case 'b':
              case 'c':
              case 'd':
              case 'e':
              case 'f':
              case '0':
              case '1':
              case '2':
              case '3':
              case '4':
              case '5':
              case '6':
              case '7':
              case '8':
              case '9':
                continue OUTER;
              default:
                throw inputError();
            }
          }
        case 'A':
        case 'B':
        case 'C':
        case 'D':
        case 'E':
        case 'F':
        case 'a':
        case 'b':
        case 'c':
        case 'd':
        case 'e':
        case 'f':
        case '0':
        case '1':
        case '2':
        case '3':
        case '4':
        case '5':
        case '6':
        case '7':
        case '8':
        case '9':
          eat();
          break;
        default:
          return;
      }
    }
  }

  private void readDigits() {
    if ('0' <= ch && ch <= '9') {
      eat();
    } else {
      throw inputError();
    }
    OUTER:
    while (true) {
      switch (ch) {
        case '_':
          do {
            eat();
          } while (ch == '_');
          if ('0' <= ch && ch <= '9') {
            continue OUTER;
          } else {
            throw inputError();
          }
        case '0':
        case '1':
        case '2':
        case '3':
        case '4':
        case '5':
        case '6':
        case '7':
        case '8':
        case '9':
          eat();
          continue OUTER;
        default:
          return;
      }
    }
  }

  private Token boolLiteral() {
    readBinaryDigits();
    switch (ch) {
      case 'l':
      case 'L':
        eat();
        return Token.LONG_LITERAL;
      default:
        return Token.INT_LITERAL;
    }
  }

  private void readBinaryDigits() {
    switch (ch) {
      case '0':
      case '1':
        eat();
        break;
      default:
        throw inputError();
    }
    OUTER:
    while (true) {
      switch (ch) {
        case '_':
          do {
            eat();
          } while (ch == '_');
          switch (ch) {
            case '0':
            case '1':
              continue OUTER;
            default:
              throw inputError();
          }
        case '0':
        case '1':
          eat();
          continue OUTER;
        default:
          return;
      }
    }
  }

  private Token octalLiteral() {
    readOctalDigits();
    switch (ch) {
      case 'l':
      case 'L':
        eat();
        return Token.LONG_LITERAL;
      default:
        return Token.INT_LITERAL;
    }
  }

  private void readOctalDigits() {
    switch (ch) {
      case '0':
      case '1':
      case '2':
      case '3':
      case '4':
      case '5':
      case '6':
      case '7':
      case '_':
        eat();
        break;
      default:
        throw inputError();
    }
    OUTER:
    while (true) {
      switch (ch) {
        case '_':
          do {
            eat();
          } while (ch == '_');
          switch (ch) {
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
              continue OUTER;
            default:
              throw inputError();
          }
        case '0':
        case '1':
        case '2':
        case '3':
        case '4':
        case '5':
        case '6':
        case '7':
          eat();
          continue OUTER;
        default:
          return;
      }
    }
  }

  private Token hexLiteral() {
    readHexDigits();
    switch (ch) {
      case '.':
        eat();
        return hexFloatLiteral();
      case 'l':
      case 'L':
        eat();
        return Token.LONG_LITERAL;
      case 'p':
      case 'P':
        eat();
        signedInteger();
        return floatTypeSuffix();
      default:
        return Token.INT_LITERAL;
    }
  }

  private Token operator() {
    switch (ch) {
      case '=':
        eat();
        if (ch == '=') {
          eat();
          return Token.EQ;
        } else {
          return Token.ASSIGN;
        }
      case '>':
        eat();
        switch (ch) {
          case '=':
            eat();
            return Token.GTE;
          case '>':
            eat();
            switch (ch) {
              case '>':
                eat();
                if (ch == '=') {
                  eat();
                  return Token.GTGTGTE;
                } else {
                  return Token.GTGTGT;
                }
              case '=':
                eat();
                return Token.GTGTE;
              default:
                return Token.GTGT;
            }
          default:
            return Token.GT;
        }
      case '<':
        eat();
        switch (ch) {
          case '=':
            eat();
            return Token.LTE;
          case '<':
            eat();
            if (ch == '=') {
              eat();
              return Token.LTLTE;
            } else {
              return Token.LTLT;
            }
          default:
            return Token.LT;
        }
      case '!':
        eat();
        if (ch == '=') {
          eat();
          return Token.NOTEQ;
        } else {
          return Token.NOT;
        }
      case '~':
        eat();
        return Token.TILDE;
      case '?':
        eat();
        return Token.COND;
      case ':':
        eat();
        if (ch == ':') {
          eat();
          return Token.COLONCOLON;
        } else {
          return Token.COLON;
        }
      case '-':
        eat();
        switch (ch) {
          case '>':
            eat();
            return Token.ARROW;
          case '-':
            eat();
            return Token.DECR;
          case '=':
            eat();
            return Token.MINUSEQ;
          default:
            return Token.MINUS;
        }
      case '&':
        eat();
        switch (ch) {
          case '&':
            eat();
            return Token.ANDAND;
          case '=':
            eat();
            return Token.ANDEQ;
          default:
            return Token.AND;
        }
      case '|':
        eat();
        switch (ch) {
          case '=':
            eat();
            return Token.OREQ;
          case '|':
            eat();
            return Token.OROR;
          default:
            return Token.OR;
        }
      case '+':
        eat();
        switch (ch) {
          case '+':
            eat();
            return Token.INCR;
          case '=':
            eat();
            return Token.PLUSEQ;
          default:
            return Token.PLUS;
        }
      case '*':
        eat();
        if (ch == '=') {
          eat();
          return Token.MULTEQ;
        } else {
          return Token.MULT;
        }
      case '/':
        // handled with comments
        throw inputError();

      case '%':
        eat();
        if (ch == '=') {
          eat();
          return Token.MODEQ;
        } else {
          return Token.MOD;
        }
      case '^':
        eat();
        if (ch == '=') {
          eat();
          return Token.XOREQ;
        } else {
          return Token.XOR;
        }
      default:
        throw inputError();
    }
  }

  private Token identifier() {
    readFrom();
    eat();
    // TODO(cushon): the style guide disallows non-ascii identifiers
    while (Character.isJavaIdentifierPart(ch)) {
      if (ch == ASCII_SUB && reader.done()) {
        break;
      }
      eat();
    }
    return makeIdent(stringValue());
  }

  private static Token makeIdent(String s) {
    switch (s) {
      case "abstract":
        return Token.ABSTRACT;
      case "assert":
        return Token.ASSERT;
      case "boolean":
        return Token.BOOLEAN;
      case "break":
        return Token.BREAK;
      case "byte":
        return Token.BYTE;
      case "case":
        return Token.CASE;
      case "catch":
        return Token.CATCH;
      case "char":
        return Token.CHAR;
      case "class":
        return Token.CLASS;
      case "const":
        return Token.CONST;
      case "continue":
        return Token.CONTINUE;
      case "default":
        return Token.DEFAULT;
      case "do":
        return Token.DO;
      case "double":
        return Token.DOUBLE;
      case "else":
        return Token.ELSE;
      case "enum":
        return Token.ENUM;
      case "extends":
        return Token.EXTENDS;
      case "final":
        return Token.FINAL;
      case "finally":
        return Token.FINALLY;
      case "float":
        return Token.FLOAT;
      case "for":
        return Token.FOR;
      case "goto":
        return Token.GOTO;
      case "if":
        return Token.IF;
      case "implements":
        return Token.IMPLEMENTS;
      case "import":
        return Token.IMPORT;
      case "instanceof":
        return Token.INSTANCEOF;
      case "int":
        return Token.INT;
      case "interface":
        return Token.INTERFACE;
      case "long":
        return Token.LONG;
      case "native":
        return Token.NATIVE;
      case "new":
        return Token.NEW;
      case "package":
        return Token.PACKAGE;
      case "private":
        return Token.PRIVATE;
      case "protected":
        return Token.PROTECTED;
      case "public":
        return Token.PUBLIC;
      case "return":
        return Token.RETURN;
      case "short":
        return Token.SHORT;
      case "static":
        return Token.STATIC;
      case "strictfp":
        return Token.STRICTFP;
      case "super":
        return Token.SUPER;
      case "switch":
        return Token.SWITCH;
      case "synchronized":
        return Token.SYNCHRONIZED;
      case "this":
        return Token.THIS;
      case "throw":
        return Token.THROW;
      case "throws":
        return Token.THROWS;
      case "transient":
        return Token.TRANSIENT;
      case "try":
        return Token.TRY;
      case "void":
        return Token.VOID;
      case "volatile":
        return Token.VOLATILE;
      case "while":
        return Token.WHILE;
      case "true":
        return Token.TRUE;
      case "false":
        return Token.FALSE;
      case "null":
        return Token.NULL;
      default:
        return Token.IDENT;
    }
  }

  private TurbineError inputError() {
    return error(
        ErrorKind.UNEXPECTED_INPUT,
        Character.isBmpCodePoint(ch) ? Character.toString((char) ch) : String.format("U+%X", ch));
  }

  private TurbineError error(ErrorKind kind, Object... args) {
    return TurbineError.format(reader.source(), reader.position(), kind, args);
  }
}
