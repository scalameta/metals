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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.turbine.parse.UnicodeEscapePreprocessor.ASCII_SUB;
import static java.lang.Math.min;

import com.google.common.collect.ImmutableList;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.TurbineJavadoc;
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
  private TurbineJavadoc javadoc = null;

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
  public @Nullable TurbineJavadoc javadoc() {
    TurbineJavadoc result = javadoc;
    javadoc = null;
    if (result == null) {
      return null;
    }
    return result;
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
        case '\r', '\n', ' ', '\t', '\f' -> {
          eat();
          continue OUTER;
        }
        case '/' -> {
          eat();
          switch (ch) {
            case '/' -> {
              while (true) {
                eat();
                switch (ch) {
                  case '\n', '\r' -> {
                    eat();
                    continue OUTER;
                  }
                  case ASCII_SUB -> {
                    if (reader.done()) {
                      return Token.EOF;
                    }
                    eat();
                  }
                  default -> {}
                }
              }
            }
            case '*' -> {
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
                  case '*' -> {
                    eat();
                    sawStar = true;
                  }
                  case '/' -> {
                    if (sawStar) {
                      if (isJavadoc) {
                        javadoc =
                            new TurbineJavadoc(position, reader.position(), source().source());
                      }
                      eat();
                      continue OUTER;
                    }
                    sawStar = false;
                    eat();
                  }
                  case ASCII_SUB -> {
                    if (reader.done()) {
                      throw TurbineError.format(
                          reader.source(), position, ErrorKind.UNCLOSED_COMMENT);
                    }
                    eat();
                    sawStar = false;
                  }
                  default -> {
                    eat();
                    sawStar = false;
                  }
                }
              }
            }
            default -> {
              if (ch == '=') {
                eat();
                return Token.DIVEQ;
              }
              return Token.DIV;
            }
          }
        }
        case 'a',
            'b',
            'c',
            'd',
            'e',
            'f',
            'g',
            'h',
            'i',
            'j',
            'k',
            'l',
            'm',
            'n',
            'o',
            'p',
            'q',
            'r',
            's',
            't',
            'u',
            'v',
            'w',
            'x',
            'y',
            'z',
            'A',
            'B',
            'C',
            'D',
            'E',
            'F',
            'G',
            'H',
            'I',
            'J',
            'K',
            'L',
            'M',
            'N',
            'O',
            'P',
            'Q',
            'R',
            'S',
            'T',
            'U',
            'V',
            'W',
            'X',
            'Y',
            'Z',
            '_',
            '$' -> {
          return identifier();
        }
        case ASCII_SUB -> {
          if (!reader.done()) {
            throw error(ErrorKind.UNEXPECTED_EOF);
          }
          return Token.EOF;
        }
        case '-', '=', '>', '<', '!', '~', '+', '?', ':', '*', '&', '|', '^', '%' -> {
          return operator();
        }
        case '(' -> {
          eat();
          return Token.LPAREN;
        }
        case ')' -> {
          eat();
          return Token.RPAREN;
        }
        case '{' -> {
          eat();
          return Token.LBRACE;
        }
        case '}' -> {
          eat();
          return Token.RBRACE;
        }
        case '[' -> {
          eat();
          return Token.LBRACK;
        }
        case ']' -> {
          eat();
          return Token.RBRACK;
        }
        case ';' -> {
          eat();
          return Token.SEMI;
        }
        case ',' -> {
          eat();
          return Token.COMMA;
        }
        case '@' -> {
          eat();
          return Token.AT;
          // what about frac, etc.?
        }
        case '0' -> {
          readFrom();
          eat();
          return switch (ch) {
            case 'x', 'X' -> {
              eat();
              yield hexLiteral();
            }
            case 'b', 'B' -> {
              eat();
              yield boolLiteral();
            }
            case '0', '1', '2', '3', '4', '5', '6', '7', '_' -> octalLiteral();
            case '.' -> {
              eat();
              yield floatLiteral();
            }
            case 'f', 'F' -> {
              eat();
              yield Token.FLOAT_LITERAL;
            }
            case 'd', 'D' -> {
              eat();
              yield Token.DOUBLE_LITERAL;
            }
            case 'l', 'L' -> {
              eat();
              yield Token.LONG_LITERAL;
            }
            default -> Token.INT_LITERAL;
          };
        }
        case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
          readFrom();
          return decimalLiteral();
        }
        case '.' -> {
          readFrom();
          eat();
          return switch (ch) {
            case '.' -> {
              eat();
              if (ch == '.') {
                eat();
                yield Token.ELLIPSIS;
              } else {
                throw inputError();
              }
            }
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> floatLiteral();
            default -> Token.DOT;
          };
        }
        case '\'' -> {
          eat();
          char value;
          switch (ch) {
            case '\\' -> {
              eat();
              value = escape();
            }
            case '\'' -> throw error(ErrorKind.EMPTY_CHARACTER_LITERAL);
            default -> {
              value = (char) ch;
              eat();
            }
          }
          if (ch == '\'') {
            saveValue(String.valueOf(value));
            eat();
            return Token.CHAR_LITERAL;
          }
          throw error(ErrorKind.UNTERMINATED_CHARACTER_LITERAL);
        }
        case '"' -> {
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
        default -> {
          if (Character.isJavaIdentifierStart(ch)) {
            // TODO(cushon): the style guide disallows non-ascii identifiers
            return identifier();
          }
          throw inputError();
        }
      }
    }
  }

  private Token textBlock() {
    OUTER:
    while (true) {
      switch (ch) {
        case ' ', '\r', '\t' -> eat();
        default -> {
          break OUTER;
        }
      }
    }
    switch (ch) {
      case '\r' -> {
        eat();
        if (ch == '\n') {
          eat();
        }
      }
      case '\n' -> eat();
      default -> throw inputError();
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
        case '\\' -> {
          eat();
          switch (ch) {
            case '\r' -> {
              eat();
              if (ch == '\n') {
                eat();
              }
            }
            case '\n' -> {
              eat();
            }
            default -> {
              sb.append(escape());
            }
          }
          continue;
        }
        case ASCII_SUB -> {
          break OUTER;
        }
        default -> {
          sb.appendCodePoint(ch);
          eat();
          continue;
        }
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
      case '0', '1', '2', '3':
        zeroToThree = true;
      // falls through
      case '4', '5', '6', '7':
        {
          char value = (char) (ch - '0');
          eat();
          return switch (ch) {
            case '0', '1', '2', '3', '4', '5', '6', '7' -> {
              value = (char) ((value << 3) | (ch - '0'));
              eat();
              if (zeroToThree) {
                yield switch (ch) {
                  case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                    value = (char) ((value << 3) | (ch - '0'));
                    eat();
                    yield value;
                  }
                  default -> value;
                };
              }
              yield value;
            }
            default -> value;
          };
        }
      default:
        throw inputError();
    }
  }

  private Token decimalLiteral() {
    readDigits();
    return switch (ch) {
      case 'e', 'E' -> floatLiteral();
      case '.' -> {
        eat();
        yield floatLiteral();
      }
      case 'f', 'F' -> {
        eat();
        yield Token.FLOAT_LITERAL;
      }
      case 'd', 'D' -> {
        eat();
        yield Token.DOUBLE_LITERAL;
      }
      case 'l', 'L' -> {
        eat();
        yield Token.LONG_LITERAL;
      }
      default -> Token.INT_LITERAL;
    };
  }

  private Token hexFloatLiteral() {
    readHexDigits();
    switch (ch) {
      case 'p', 'P' -> {
        eat();
        signedInteger();
      }
      default -> {}
    }
    return floatTypeSuffix();
  }

  private Token floatLiteral() {
    if ('0' <= ch && ch <= '9') {
      readDigits();
    }
    switch (ch) {
      case 'e', 'E' -> {
        eat();
        signedInteger();
      }
      default -> {}
    }
    return floatTypeSuffix();
  }

  private Token floatTypeSuffix() {
    return switch (ch) {
      case 'd', 'D' -> {
        eat();
        yield Token.DOUBLE_LITERAL;
      }
      case 'f', 'F' -> {
        eat();
        yield Token.FLOAT_LITERAL;
      }
      default -> Token.DOUBLE_LITERAL;
    };
  }

  private void signedInteger() {
    switch (ch) {
      case '-', '+' -> eat();
      default -> {}
    }
    readDigits();
  }

  private void readHexDigits() {
    switch (ch) {
      case 'A',
          'B',
          'C',
          'D',
          'E',
          'F',
          'a',
          'b',
          'c',
          'd',
          'e',
          'f',
          '0',
          '1',
          '2',
          '3',
          '4',
          '5',
          '6',
          '7',
          '8',
          '9' ->
          eat();
      default -> throw inputError();
    }
    OUTER:
    while (true) {
      switch (ch) {
        case '_' -> {
          do {
            eat();
          } while (ch == '_');
          switch (ch) {
            case 'A',
                'B',
                'C',
                'D',
                'E',
                'F',
                'a',
                'b',
                'c',
                'd',
                'e',
                'f',
                '0',
                '1',
                '2',
                '3',
                '4',
                '5',
                '6',
                '7',
                '8',
                '9' -> {
              continue OUTER;
            }
            default -> throw inputError();
          }
        }
        case 'A',
            'B',
            'C',
            'D',
            'E',
            'F',
            'a',
            'b',
            'c',
            'd',
            'e',
            'f',
            '0',
            '1',
            '2',
            '3',
            '4',
            '5',
            '6',
            '7',
            '8',
            '9' ->
            eat();
        default -> {
          return;
        }
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
        case '_' -> {
          do {
            eat();
          } while (ch == '_');
          if ('0' <= ch && ch <= '9') {
            continue OUTER;
          } else {
            throw inputError();
          }
        }
        case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
          eat();
          continue OUTER;
        }
        default -> {
          return;
        }
      }
    }
  }

  private Token boolLiteral() {
    readBinaryDigits();
    return switch (ch) {
      case 'l', 'L' -> {
        eat();
        yield Token.LONG_LITERAL;
      }
      default -> Token.INT_LITERAL;
    };
  }

  private void readBinaryDigits() {
    switch (ch) {
      case '0', '1' -> eat();
      default -> throw inputError();
    }
    OUTER:
    while (true) {
      switch (ch) {
        case '_' -> {
          do {
            eat();
          } while (ch == '_');
          switch (ch) {
            case '0', '1' -> {
              continue OUTER;
            }
            default -> throw inputError();
          }
        }
        case '0', '1' -> {
          eat();
          continue OUTER;
        }
        default -> {
          return;
        }
      }
    }
  }

  private Token octalLiteral() {
    readOctalDigits();
    return switch (ch) {
      case 'l', 'L' -> {
        eat();
        yield Token.LONG_LITERAL;
      }
      default -> Token.INT_LITERAL;
    };
  }

  private void readOctalDigits() {
    switch (ch) {
      case '0', '1', '2', '3', '4', '5', '6', '7', '_' -> eat();
      default -> throw inputError();
    }
    OUTER:
    while (true) {
      switch (ch) {
        case '_' -> {
          do {
            eat();
          } while (ch == '_');
          switch (ch) {
            case '0', '1', '2', '3', '4', '5', '6', '7' -> {
              continue OUTER;
            }
            default -> throw inputError();
          }
        }
        case '0', '1', '2', '3', '4', '5', '6', '7' -> {
          eat();
          continue OUTER;
        }
        default -> {
          return;
        }
      }
    }
  }

  private Token hexLiteral() {
    readHexDigits();
    return switch (ch) {
      case '.' -> {
        eat();
        yield hexFloatLiteral();
      }
      case 'l', 'L' -> {
        eat();
        yield Token.LONG_LITERAL;
      }
      case 'p', 'P' -> {
        eat();
        signedInteger();
        yield floatTypeSuffix();
      }
      default -> Token.INT_LITERAL;
    };
  }

  private Token operator() {
    switch (ch) {
      case '=' -> {
        eat();
        if (ch == '=') {
          eat();
          return Token.EQ;
        } else {
          return Token.ASSIGN;
        }
      }
      case '>' -> {
        eat();
        return switch (ch) {
          case '=' -> {
            eat();
            yield Token.GTE;
          }
          case '>' -> {
            eat();
            yield switch (ch) {
              case '>' -> {
                eat();
                if (ch == '=') {
                  eat();
                  yield Token.GTGTGTE;
                } else {
                  yield Token.GTGTGT;
                }
              }
              case '=' -> {
                eat();
                yield Token.GTGTE;
              }
              default -> Token.GTGT;
            };
          }
          default -> Token.GT;
        };
      }
      case '<' -> {
        eat();
        return switch (ch) {
          case '=' -> {
            eat();
            yield Token.LTE;
          }
          case '<' -> {
            eat();
            if (ch == '=') {
              eat();
              yield Token.LTLTE;
            } else {
              yield Token.LTLT;
            }
          }
          default -> Token.LT;
        };
      }
      case '!' -> {
        eat();
        if (ch == '=') {
          eat();
          return Token.NOTEQ;
        } else {
          return Token.NOT;
        }
      }
      case '~' -> {
        eat();
        return Token.TILDE;
      }
      case '?' -> {
        eat();
        return Token.COND;
      }
      case ':' -> {
        eat();
        if (ch == ':') {
          eat();
          return Token.COLONCOLON;
        } else {
          return Token.COLON;
        }
      }
      case '-' -> {
        eat();
        return switch (ch) {
          case '>' -> {
            eat();
            yield Token.ARROW;
          }
          case '-' -> {
            eat();
            yield Token.DECR;
          }
          case '=' -> {
            eat();
            yield Token.MINUSEQ;
          }
          default -> Token.MINUS;
        };
      }
      case '&' -> {
        eat();
        return switch (ch) {
          case '&' -> {
            eat();
            yield Token.ANDAND;
          }
          case '=' -> {
            eat();
            yield Token.ANDEQ;
          }
          default -> Token.AND;
        };
      }
      case '|' -> {
        eat();
        return switch (ch) {
          case '=' -> {
            eat();
            yield Token.OREQ;
          }
          case '|' -> {
            eat();
            yield Token.OROR;
          }
          default -> Token.OR;
        };
      }
      case '+' -> {
        eat();
        return switch (ch) {
          case '+' -> {
            eat();
            yield Token.INCR;
          }
          case '=' -> {
            eat();
            yield Token.PLUSEQ;
          }
          default -> Token.PLUS;
        };
      }
      case '*' -> {
        eat();
        if (ch == '=') {
          eat();
          return Token.MULTEQ;
        } else {
          return Token.MULT;
        }
      }
      case '/' ->
          // handled with comments
          throw inputError();
      case '%' -> {
        eat();
        if (ch == '=') {
          eat();
          return Token.MODEQ;
        } else {
          return Token.MOD;
        }
      }
      case '^' -> {
        eat();
        if (ch == '=') {
          eat();
          return Token.XOREQ;
        } else {
          return Token.XOR;
        }
      }
      default -> throw inputError();
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
    return switch (s) {
      case "abstract" -> Token.ABSTRACT;
      case "assert" -> Token.ASSERT;
      case "boolean" -> Token.BOOLEAN;
      case "break" -> Token.BREAK;
      case "byte" -> Token.BYTE;
      case "case" -> Token.CASE;
      case "catch" -> Token.CATCH;
      case "char" -> Token.CHAR;
      case "class" -> Token.CLASS;
      case "const" -> Token.CONST;
      case "continue" -> Token.CONTINUE;
      case "default" -> Token.DEFAULT;
      case "do" -> Token.DO;
      case "double" -> Token.DOUBLE;
      case "else" -> Token.ELSE;
      case "enum" -> Token.ENUM;
      case "extends" -> Token.EXTENDS;
      case "final" -> Token.FINAL;
      case "finally" -> Token.FINALLY;
      case "float" -> Token.FLOAT;
      case "for" -> Token.FOR;
      case "goto" -> Token.GOTO;
      case "if" -> Token.IF;
      case "implements" -> Token.IMPLEMENTS;
      case "import" -> Token.IMPORT;
      case "instanceof" -> Token.INSTANCEOF;
      case "int" -> Token.INT;
      case "interface" -> Token.INTERFACE;
      case "long" -> Token.LONG;
      case "native" -> Token.NATIVE;
      case "new" -> Token.NEW;
      case "package" -> Token.PACKAGE;
      case "private" -> Token.PRIVATE;
      case "protected" -> Token.PROTECTED;
      case "public" -> Token.PUBLIC;
      case "return" -> Token.RETURN;
      case "short" -> Token.SHORT;
      case "static" -> Token.STATIC;
      case "strictfp" -> Token.STRICTFP;
      case "super" -> Token.SUPER;
      case "switch" -> Token.SWITCH;
      case "synchronized" -> Token.SYNCHRONIZED;
      case "this" -> Token.THIS;
      case "throw" -> Token.THROW;
      case "throws" -> Token.THROWS;
      case "transient" -> Token.TRANSIENT;
      case "try" -> Token.TRY;
      case "void" -> Token.VOID;
      case "volatile" -> Token.VOLATILE;
      case "while" -> Token.WHILE;
      case "true" -> Token.TRUE;
      case "false" -> Token.FALSE;
      case "null" -> Token.NULL;
      default -> Token.IDENT;
    };
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
