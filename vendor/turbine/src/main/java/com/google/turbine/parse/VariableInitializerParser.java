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

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-process variable initializer expressions to handle multi-variable declarations.
 *
 * <p>Turbine needs to be able to parse compile-time constant expressions in constant variable
 * intializers and annotations. Parsing JLS 15.28 constant expressions is much easier than parsing
 * the full expression language, so we pre-process variable initializers to extract the expression
 * and then parse it with an simple constant expression parser that fails if it sees an expression
 * it doesn't understand.
 *
 * <p>To extract the (possibly constant) expression, we can usually just scan ahead to the
 * semi-colon at the end of the variable. To avoid matching on semi-colons inside lambdas or
 * anonymous class declarations, the preprocessor also matches braces.
 *
 * <p>That handles everything except multi-variable declarations (int x = 1, y = 2;), which in
 * hindsight were probably a mistake. Multi-variable declarations contain a list of name and
 * initializer pairs separated by commas. The initializer expressions may also contain commas, so
 * it's non-trivial to split on initializer boundaries. For example, consider {@code int x = a < b,
 * c = d;}. We can't tell looking at the prefix {@code a < b, c} whether that's a less-than
 * expression followed by another initializer, or the start of a generic type: {@code a<b, c>.foo(}.
 * Distinguishing between these cases requires arbitrary lookahead.
 *
 * <p>The preprocessor seems to be operationally correct. It's possible there are edge cases that it
 * doesn't handle, but it's extremely rare for compile-time constant multi-variable declarations to
 * contain complex generics. Multi-variable declarations are also disallowed by the Style guide.
 */
public class VariableInitializerParser {

  enum FieldInitState {
    /** The beginning of an initializer expression. */
    START,
    /** The state after `<identifier> <`. */
    TYPE,
  }

  /** Indices into {@code LT} tokens used for backtracking. */
  final ArrayDeque<Integer> ltIndices = new ArrayDeque<>();

  /** Indices into {@code commas} used for backtracking. */
  final ArrayDeque<Integer> commaIndices = new ArrayDeque<>();

  /** The saved tokens. */
  List<SavedToken> tokens = new ArrayList<>();

  /**
   * Indices of boundaries between variable initializers in {@code tokens} (which are indicated by
   * commas in the input).
   */
  List<Integer> commas = new ArrayList<>();

  public Token token;
  FieldInitState state = FieldInitState.START;
  int depth = 0;

  final Lexer lexer;

  public VariableInitializerParser(Token token, Lexer lexer) {
    this.token = token;
    this.lexer = lexer;
  }

  private void next() {
    token = lexer.next();
  }

  /** Returns lists of tokens for individual initializers in a (mutli-)variable initializer. */
  public List<List<SavedToken>> parseInitializers() {
    OUTER:
    while (true) {
      switch (token) {
        case IDENT:
          save();
          next();
          if (state == FieldInitState.START) {
            if (token == Token.LT) {
              state = FieldInitState.TYPE;
              depth = 1;
              ltIndices.clear();
              commaIndices.clear();
              ltIndices.addLast(tokens.size());
              commaIndices.addLast(commas.size());
              save();
              next();
              break;
            }
          }
          break;
        case LT:
          if (state == FieldInitState.TYPE) {
            depth++;
            ltIndices.addLast(tokens.size());
            commaIndices.addLast(commas.size());
          }
          save();
          next();
          break;
        case GTGTGT:
          save();
          next();
          dropBracks(3);
          break;
        case GTGT:
          save();
          next();
          dropBracks(2);
          break;
        case GT:
          save();
          next();
          dropBracks(1);
          break;
        case LPAREN:
          save();
          next();
          dropParens();
          break;
        case LBRACE:
          save();
          next();
          dropBraces();
          break;
        case SEMI:
          switch (state) {
            case START:
            case TYPE:
              break OUTER;
          }
          save();
          next();
          break;
        case COMMA:
          save();
          next();
          switch (state) {
            case START:
            case TYPE:
              commas.add(tokens.size());
              break;
          }
          break;
        case DOT:
          save();
          next();
          dropTypeArguments();
          break;
        case NEW:
          save();
          next();
          dropTypeArguments();
          while (token == Token.IDENT) {
            save();
            next();
            dropTypeArguments();
            if (token == Token.DOT) {
              next();
            } else {
              break;
            }
          }
          break;
        case COLONCOLON:
          save();
          next();
          dropTypeArguments();
          if (token == Token.NEW) {
            next();
          }
          break;
        case EOF:
          break OUTER;
        default:
          save();
          next();
          break;
      }
    }
    List<List<SavedToken>> result = new ArrayList<>();
    int start = 0;
    for (int idx : commas) {
      result.add(
          ImmutableList.<SavedToken>builder()
              .addAll(tokens.subList(start, idx - 1))
              .add(new SavedToken(Token.EOF, null, tokens.get(idx - 1).position))
              .build());
      start = idx;
    }
    result.add(
        ImmutableList.<SavedToken>builder()
            .addAll(tokens.subList(start, tokens.size()))
            .add(new SavedToken(Token.EOF, null, lexer.position()))
            .build());
    return result;
  }

  private void dropParens() {
    int depth = 1;
    while (depth > 0) {
      switch (token) {
        case LPAREN:
          save();
          next();
          depth++;
          break;
        case RPAREN:
          save();
          next();
          depth--;
          break;
        case EOF:
          throw error(ErrorKind.UNEXPECTED_EOF);
        default:
          save();
          next();
          break;
      }
    }
  }

  private void dropBraces() {
    int depth = 1;
    while (depth > 0) {
      switch (token) {
        case LBRACE:
          save();
          next();
          depth++;
          break;
        case RBRACE:
          save();
          next();
          depth--;
          break;
        case EOF:
          throw error(ErrorKind.UNEXPECTED_EOF);
        default:
          save();
          next();
          break;
      }
    }
  }

  private void save() {
    String value;
    switch (token) {
      case IDENT:
      case INT_LITERAL:
      case LONG_LITERAL:
      case DOUBLE_LITERAL:
      case FLOAT_LITERAL:
      case STRING_LITERAL:
      case CHAR_LITERAL:
        value = lexer.stringValue();
        break;
      default:
        // memory optimization: don't save string values for tokens that don't require them
        value = null;
        break;
    }
    tokens.add(new SavedToken(token, value, lexer.position()));
  }

  private void dropBracks(int many) {
    if (state != FieldInitState.TYPE) {
      return;
    }
    if (depth <= many) {
      state = FieldInitState.START;
    }
    depth -= many;
    int lastType = -1;
    int lastComma = -1;
    for (int i = 0; i < many; i++) {
      if (ltIndices.isEmpty()) {
        throw error(ErrorKind.UNEXPECTED_TOKEN, ">");
      }
      lastType = ltIndices.removeLast();
      lastComma = commaIndices.removeLast();
    }
    // The only known type argument locations that require look-ahead to classify are method
    // references with parametric receivers, and qualified nested type names:
    switch (token) {
      case COLONCOLON:
      case DOT:
        this.tokens = tokens.subList(0, lastType);
        this.commas = commas.subList(0, lastComma);
        break;
      default:
        break;
    }
  }

  /**
   * Drops pairs of `<` `>` from the input. Should only be called in contexts where the braces are
   * unambiguously type argument lists, not less-than.
   *
   * <p>Since the lexer munches multiple close braces as a single token, there's handling of right
   * shifts for cases like the `>>` in `List<SavedToken<String, Integer>>`.
   */
  private void dropTypeArguments() {
    if (token != Token.LT) {
      return;
    }
    next();
    int depth = 1;
    while (depth > 0) {
      switch (token) {
        case LT:
          depth++;
          next();
          break;
        case GTGTGT:
          depth -= 3;
          next();
          break;
        case GTGT:
          depth -= 2;
          next();
          break;
        case GT:
          depth--;
          next();
          break;
        case EOF:
          throw error(ErrorKind.UNEXPECTED_EOF);
        default:
          next();
          break;
      }
    }
  }

  @CheckReturnValue
  private TurbineError error(ErrorKind kind, Object... args) {
    return TurbineError.format(
        lexer.source(),
        Math.min(lexer.position(), lexer.source().source().length() - 1),
        kind,
        args);
  }
}
