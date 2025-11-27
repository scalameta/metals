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

import static com.google.common.collect.Iterables.getOnlyElement;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.AnnoExpr;
import com.google.turbine.tree.Tree.ClassLiteral;
import com.google.turbine.tree.Tree.ClassTy;
import com.google.turbine.tree.Tree.Expression;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.tree.TurbineOperatorKind;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A parser for compile-time constant expressions. */
public class ConstExpressionParser {

  Token token;
  private int position;
  private final Lexer lexer;

  public ConstExpressionParser(Lexer lexer, Token token, int position) {
    this.lexer = lexer;
    this.token = token;
    this.position = position;
  }

  private static @Nullable TurbineOperatorKind operator(Token token) {
    switch (token) {
      case ASSIGN:
        // TODO(cushon): only allow in annotations?
        return TurbineOperatorKind.ASSIGN;
      case MULT:
        return TurbineOperatorKind.MULT;
      case DIV:
        return TurbineOperatorKind.DIVIDE;
      case MOD:
        return TurbineOperatorKind.MODULO;
      case PLUS:
        return TurbineOperatorKind.PLUS;
      case MINUS:
        return TurbineOperatorKind.MINUS;
      case LTLT:
        return TurbineOperatorKind.SHIFT_LEFT;
      case GTGT:
        return TurbineOperatorKind.SHIFT_RIGHT;
      case GTGTGT:
        return TurbineOperatorKind.UNSIGNED_SHIFT_RIGHT;
      case LT:
        return TurbineOperatorKind.LESS_THAN;
      case GT:
        return TurbineOperatorKind.GREATER_THAN;
      case LTE:
        return TurbineOperatorKind.LESS_THAN_EQ;
      case GTE:
        return TurbineOperatorKind.GREATER_THAN_EQ;
      case EQ:
        return TurbineOperatorKind.EQUAL;
      case NOTEQ:
        return TurbineOperatorKind.NOT_EQUAL;
      case AND:
        return TurbineOperatorKind.BITWISE_AND;
      case OR:
        return TurbineOperatorKind.BITWISE_OR;
      case XOR:
        return TurbineOperatorKind.BITWISE_XOR;
      case ANDAND:
        return TurbineOperatorKind.AND;
      case OROR:
        return TurbineOperatorKind.OR;
      case COND:
        return TurbineOperatorKind.TERNARY;
      default:
        return null;
    }
  }

  private @Nullable Expression primary(boolean negate) {
    switch (token) {
      case INT_LITERAL:
        return finishLiteral(TurbineConstantTypeKind.INT, negate);
      case DOUBLE_LITERAL:
        return finishLiteral(TurbineConstantTypeKind.DOUBLE, negate);
      case LONG_LITERAL:
        return finishLiteral(TurbineConstantTypeKind.LONG, negate);
      case FLOAT_LITERAL:
        return finishLiteral(TurbineConstantTypeKind.FLOAT, negate);
      case TRUE:
        {
          int pos = position;
          eat();
          return new Tree.Literal(
              pos, TurbineConstantTypeKind.BOOLEAN, new Const.BooleanValue(true));
        }
      case FALSE:
        {
          int pos = position;
          eat();
          return new Tree.Literal(
              pos, TurbineConstantTypeKind.BOOLEAN, new Const.BooleanValue(false));
        }
      case CHAR_LITERAL:
        return finishLiteral(TurbineConstantTypeKind.CHAR, negate);
      case STRING_LITERAL:
        return finishLiteral(TurbineConstantTypeKind.STRING, false);
      case PLUS:
        eat();
        return unaryRest(TurbineOperatorKind.UNARY_PLUS);
      case MINUS:
        eat();
        return unaryRest(TurbineOperatorKind.NEG);
      case NOT:
        eat();
        return unaryRest(TurbineOperatorKind.NOT);
      case TILDE:
        eat();
        return unaryRest(TurbineOperatorKind.BITWISE_COMP);
      case LPAREN:
        return maybeCast();
      case LBRACE:
        int pos = position;
        eat();
        return arrayInitializer(pos);
      case IDENT:
        return qualIdent();
      case BYTE:
        return primitiveClassLiteral(TurbineConstantTypeKind.BYTE);
      case CHAR:
        return primitiveClassLiteral(TurbineConstantTypeKind.CHAR);
      case DOUBLE:
        return primitiveClassLiteral(TurbineConstantTypeKind.DOUBLE);
      case FLOAT:
        return primitiveClassLiteral(TurbineConstantTypeKind.FLOAT);
      case INT:
        return primitiveClassLiteral(TurbineConstantTypeKind.INT);
      case LONG:
        return primitiveClassLiteral(TurbineConstantTypeKind.LONG);
      case SHORT:
        return primitiveClassLiteral(TurbineConstantTypeKind.SHORT);
      case BOOLEAN:
        return primitiveClassLiteral(TurbineConstantTypeKind.BOOLEAN);
      case VOID:
        eat();
        return finishClassLiteral(position, new Tree.VoidTy(position));
      case AT:
        return annotation();
      default:
        return null;
    }
  }

  private Expression primitiveClassLiteral(TurbineConstantTypeKind type) {
    eat();
    return finishClassLiteral(position, new Tree.PrimTy(position, ImmutableList.of(), type));
  }

  private Expression maybeCast() {
    eat();
    switch (token) {
      case BOOLEAN:
        eat();
        return castTail(TurbineConstantTypeKind.BOOLEAN);
      case BYTE:
        eat();
        return castTail(TurbineConstantTypeKind.BYTE);
      case SHORT:
        eat();
        return castTail(TurbineConstantTypeKind.SHORT);
      case INT:
        eat();
        return castTail(TurbineConstantTypeKind.INT);
      case LONG:
        eat();
        return castTail(TurbineConstantTypeKind.LONG);
      case CHAR:
        eat();
        return castTail(TurbineConstantTypeKind.CHAR);
      case DOUBLE:
        eat();
        return castTail(TurbineConstantTypeKind.DOUBLE);
      case FLOAT:
        eat();
        return castTail(TurbineConstantTypeKind.FLOAT);
      default:
        return maybeStringCast();
    }
  }

  private @Nullable Expression maybeStringCast() {
    Expression expr = expression(null);
    if (expr == null) {
      return null;
    }
    if (token != Token.RPAREN) {
      return null;
    }
    eat();
    if (expr.kind() == Tree.Kind.CONST_VAR_NAME) {
      Tree.ConstVarName cvar = (Tree.ConstVarName) expr;
      switch (token) {
        case STRING_LITERAL:
        case IDENT:
        case LPAREN:
          Expression expression = primary(false);
          if (expression == null) {
            return null;
          }
          return new Tree.TypeCast(position, asClassTy(cvar.position(), cvar.name()), expression);
        default:
          return new Tree.Paren(position, expr);
      }
    } else {
      return new Tree.Paren(position, expr);
    }
  }

  private static ClassTy asClassTy(int pos, ImmutableList<Tree.Ident> names) {
    ClassTy cty = null;
    for (Tree.Ident bit : names) {
      cty = new ClassTy(pos, Optional.ofNullable(cty), bit, ImmutableList.of(), ImmutableList.of());
    }
    return cty;
  }

  private void eat() {
    token = lexer.next();
    position = lexer.position();
  }

  private @Nullable Expression arrayInitializer(int pos) {
    if (token == Token.RBRACE) {
      eat();
      return new Tree.ArrayInit(pos, ImmutableList.<Tree.Expression>of());
    }

    ImmutableList.Builder<Tree.Expression> exprs = ImmutableList.builder();
    OUTER:
    while (true) {
      if (token == Token.RBRACE) {
        eat();
        break OUTER;
      }
      Expression item = expression(null);
      if (item == null) {
        return null;
      }
      exprs.add(item);
      switch (token) {
        case COMMA:
          eat();
          break;
        case RBRACE:
          eat();
          break OUTER;
        default:
          return null;
      }
    }
    return new Tree.ArrayInit(pos, exprs.build());
  }

  /** Finish hex, decimal, octal, and binary integer literals (see JLS 3.10.1). */
  private Expression finishLiteral(TurbineConstantTypeKind kind, boolean negate) {
    int pos = position;
    String text = ident().value();
    Const.Value value;
    switch (kind) {
      case INT:
        {
          int radix = 10;
          if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
            radix = 0x10;
          } else if (isOctal(text)) {
            radix = 010;
          } else if (text.startsWith("0b") || text.startsWith("0B")) {
            text = text.substring(2);
            radix = 0b10;
          }
          if (negate) {
            text = "-" + text;
          }
          long longValue = parseLong(text, radix);
          if (radix == 10) {
            if (longValue != (int) longValue) {
              throw error(ErrorKind.INVALID_LITERAL, text);
            }
          } else {
            if (Math.abs(longValue) >> 32 != 0) {
              throw error(ErrorKind.INVALID_LITERAL, text);
            }
          }
          value = new Const.IntValue((int) longValue);
          break;
        }
      case LONG:
        {
          int radix = 10;
          if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
            radix = 0x10;
          } else if (isOctal(text)) {
            radix = 010;
          } else if (text.startsWith("0b") || text.startsWith("0B")) {
            text = text.substring(2);
            radix = 0b10;
          }
          if (negate) {
            text = "-" + text;
          }
          if (text.endsWith("L") || text.endsWith("l")) {
            text = text.substring(0, text.length() - 1);
          }
          value = new Const.LongValue(parseLong(text, radix));
          break;
        }
      case CHAR:
        value = new Const.CharValue(text.charAt(0));
        break;
      case FLOAT:
        try {
          value = new Const.FloatValue(Float.parseFloat(text.replace("_", "")));
        } catch (NumberFormatException e) {
          throw error(ErrorKind.INVALID_LITERAL, text);
        }
        break;
      case DOUBLE:
        try {
          value = new Const.DoubleValue(Double.parseDouble(text.replace("_", "")));
        } catch (NumberFormatException e) {
          throw error(ErrorKind.INVALID_LITERAL, text);
        }
        break;
      case STRING:
        value = new Const.StringValue(text);
        break;
      default:
        throw new AssertionError(kind);
    }
    eat();
    return new Tree.Literal(pos, kind, value);
  }

  static boolean isOctal(String text) {
    if (!text.startsWith("0")) {
      return false;
    }
    if (text.length() <= 1) {
      return false;
    }
    char next = text.charAt(1);
    return Character.isDigit(next) || next == '_';
  }

  /**
   * Parse the string as a signed long.
   *
   * <p>{@link Long#parseLong} doesn't accept {@link Long#MIN_VALUE}.
   */
  private long parseLong(String text, int radix) {
    long r = 0;
    boolean neg = text.startsWith("-");
    if (neg) {
      text = text.substring(1);
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      int digit;
      if ('0' <= c && c <= '9') {
        digit = c - '0';
      } else if ('a' <= c && c <= 'f') {
        digit = 10 + (c - 'a');
      } else if ('A' <= c && c <= 'F') {
        digit = 10 + (c - 'A');
      } else if (c == '_') {
        continue;
      } else {
        throw error(ErrorKind.INVALID_LITERAL, text);
      }
      r = (r * radix) + digit;
    }
    if (neg) {
      r = -r;
    }
    return r;
  }

  private @Nullable Expression unaryRest(TurbineOperatorKind op) {
    boolean negate = op == TurbineOperatorKind.NEG;
    Expression expr = primary(negate);
    if (expr == null) {
      return null;
    }
    if (negate && expr.kind() == Tree.Kind.LITERAL) {
      Tree.Literal lit = (Tree.Literal) expr;
      switch (lit.tykind()) {
        case INT:
        case LONG:
          return expr;
        default:
          break;
      }
    }
    return new Tree.Unary(position, expr, op);
  }

  private @Nullable Expression qualIdent() {
    int pos = position;
    ImmutableList.Builder<Ident> bits = ImmutableList.builder();
    bits.add(ident());
    eat();
    while (token == Token.DOT) {
      eat();
      switch (token) {
        case IDENT:
          bits.add(ident());
          break;
        case CLASS:
          // TODO(cushon): only allow in annotations?
          eat();
          return new Tree.ClassLiteral(pos, asClassTy(pos, bits.build()));
        default:
          return null;
      }
      eat();
    }
    if (token == Token.LBRACK) {
      return finishClassLiteral(pos, asClassTy(pos, bits.build()));
    }
    return new Tree.ConstVarName(pos, bits.build());
  }

  private Ident ident() {
    return new Ident(lexer.position(), lexer.stringValue());
  }

  private @Nullable Expression finishClassLiteral(int pos, Tree.Type type) {
    while (token == Token.LBRACK) {
      eat();
      if (token != Token.RBRACK) {
        return null;
      }
      eat();
      type = new Tree.ArrTy(position, ImmutableList.of(), type);
    }
    if (token != Token.DOT) {
      return null;
    }
    eat();
    if (token != Token.CLASS) {
      return null;
    }
    eat();
    return new ClassLiteral(pos, type);
  }

  public @Nullable Expression expression() {
    Expression result = expression(null);
    switch (token) {
      case EOF:
      case SEMI:
      // TODO(cushon): only allow in annotations?
      case COMMA:
      case RPAREN:
        return result;
      default:
        return null;
    }
  }

  private @Nullable Expression expression(TurbineOperatorKind.Precedence prec) {
    Expression term1 = primary(false);
    if (term1 == null) {
      return null;
    }
    return expression(term1, prec);
  }

  private @Nullable Expression expression(Expression term1, TurbineOperatorKind.Precedence prec) {
    while (true) {
      if (token == Token.EOF) {
        return term1;
      }
      TurbineOperatorKind op = operator(token);
      if (op == null) {
        return term1;
      }
      if (prec != null && op.prec().rank() <= prec.rank()) {
        return term1;
      }
      eat();
      switch (op) {
        case TERNARY:
          term1 = ternary(term1);
          break;
        case ASSIGN:
          term1 = assign(term1, op);
          break;
        default:
          int pos = position;
          Expression term2 = expression(op.prec());
          if (term2 == null) {
            return null;
          }
          term1 = new Tree.Binary(pos, term1, term2, op);
      }
      if (term1 == null) {
        return null;
      }
    }
  }

  private @Nullable Expression assign(Expression term1, TurbineOperatorKind op) {
    if (!(term1 instanceof Tree.ConstVarName)) {
      return null;
    }
    ImmutableList<Ident> names = ((Tree.ConstVarName) term1).name();
    if (names.size() > 1) {
      return null;
    }
    Ident name = getOnlyElement(names);
    Expression rhs = expression(op.prec());
    if (rhs == null) {
      return null;
    }
    return new Tree.Assign(term1.position(), name, rhs);
  }

  private @Nullable Expression ternary(Expression term1) {
    Expression thenExpr = expression(TurbineOperatorKind.Precedence.TERNARY);
    if (thenExpr == null) {
      return null;
    }
    if (token != Token.COLON) {
      return null;
    }
    eat();
    Expression elseExpr = expression();
    if (elseExpr == null) {
      return null;
    }
    return new Tree.Conditional(position, term1, thenExpr, elseExpr);
  }

  private @Nullable Expression castTail(TurbineConstantTypeKind ty) {
    if (token != Token.RPAREN) {
      return null;
    }
    eat();
    Expression rhs = primary(false);
    if (rhs == null) {
      return null;
    }
    return new Tree.TypeCast(position, new Tree.PrimTy(position, ImmutableList.of(), ty), rhs);
  }

  private @Nullable AnnoExpr annotation() {
    if (token != Token.AT) {
      throw new AssertionError();
    }
    eat();
    int pos = position;
    Expression constVarName = qualIdent();
    if (!(constVarName instanceof Tree.ConstVarName)) {
      return null;
    }
    ImmutableList<Ident> name = ((Tree.ConstVarName) constVarName).name();
    ImmutableList.Builder<Tree.Expression> args = ImmutableList.builder();
    if (token == Token.LPAREN) {
      eat();
      while (token != Token.RPAREN) {
        int argPos = position;
        Expression expression = expression();
        if (expression == null) {
          throw TurbineError.format(lexer.source(), argPos, ErrorKind.INVALID_ANNOTATION_ARGUMENT);
        }
        args.add(expression);
        if (token != Token.COMMA) {
          break;
        }
        eat();
      }
      if (token == Token.RPAREN) {
        eat();
      }
    }
    return new Tree.AnnoExpr(pos, new Tree.Anno(pos, name, args.build()));
  }

  @CheckReturnValue
  private TurbineError error(ErrorKind kind, Object... args) {
    return TurbineError.format(lexer.source(), lexer.position(), kind, args);
  }

  public int f() {
    return helper(1, 2);
  }

  private int helper(int x, int y) {
    return x + y;
  }
}
