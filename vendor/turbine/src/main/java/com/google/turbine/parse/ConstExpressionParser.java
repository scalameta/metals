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
    return switch (token) {
      case ASSIGN ->
          // TODO(cushon): only allow in annotations?
          TurbineOperatorKind.ASSIGN;
      case MULT -> TurbineOperatorKind.MULT;
      case DIV -> TurbineOperatorKind.DIVIDE;
      case MOD -> TurbineOperatorKind.MODULO;
      case PLUS -> TurbineOperatorKind.PLUS;
      case MINUS -> TurbineOperatorKind.MINUS;
      case LTLT -> TurbineOperatorKind.SHIFT_LEFT;
      case GTGT -> TurbineOperatorKind.SHIFT_RIGHT;
      case GTGTGT -> TurbineOperatorKind.UNSIGNED_SHIFT_RIGHT;
      case LT -> TurbineOperatorKind.LESS_THAN;
      case GT -> TurbineOperatorKind.GREATER_THAN;
      case LTE -> TurbineOperatorKind.LESS_THAN_EQ;
      case GTE -> TurbineOperatorKind.GREATER_THAN_EQ;
      case EQ -> TurbineOperatorKind.EQUAL;
      case NOTEQ -> TurbineOperatorKind.NOT_EQUAL;
      case AND -> TurbineOperatorKind.BITWISE_AND;
      case OR -> TurbineOperatorKind.BITWISE_OR;
      case XOR -> TurbineOperatorKind.BITWISE_XOR;
      case ANDAND -> TurbineOperatorKind.AND;
      case OROR -> TurbineOperatorKind.OR;
      case COND -> TurbineOperatorKind.TERNARY;
      default -> null;
    };
  }

  private @Nullable Expression primary(boolean negate) {
    return switch (token) {
      case INT_LITERAL -> finishLiteral(TurbineConstantTypeKind.INT, negate);
      case DOUBLE_LITERAL -> finishLiteral(TurbineConstantTypeKind.DOUBLE, negate);
      case LONG_LITERAL -> finishLiteral(TurbineConstantTypeKind.LONG, negate);
      case FLOAT_LITERAL -> finishLiteral(TurbineConstantTypeKind.FLOAT, negate);
      case TRUE -> {
        int pos = position;
        eat();
        yield new Tree.Literal(pos, TurbineConstantTypeKind.BOOLEAN, new Const.BooleanValue(true));
      }
      case FALSE -> {
        int pos = position;
        eat();
        yield new Tree.Literal(pos, TurbineConstantTypeKind.BOOLEAN, new Const.BooleanValue(false));
      }
      case CHAR_LITERAL -> finishLiteral(TurbineConstantTypeKind.CHAR, negate);
      case STRING_LITERAL -> finishLiteral(TurbineConstantTypeKind.STRING, false);
      case PLUS -> {
        eat();
        yield unaryRest(TurbineOperatorKind.UNARY_PLUS);
      }
      case MINUS -> {
        eat();
        yield unaryRest(TurbineOperatorKind.NEG);
      }
      case NOT -> {
        eat();
        yield unaryRest(TurbineOperatorKind.NOT);
      }
      case TILDE -> {
        eat();
        yield unaryRest(TurbineOperatorKind.BITWISE_COMP);
      }
      case LPAREN -> maybeCast();
      case LBRACE -> {
        int pos = position;
        eat();
        yield arrayInitializer(pos);
      }
      case IDENT -> qualIdent();
      case BYTE -> primitiveClassLiteral(TurbineConstantTypeKind.BYTE);
      case CHAR -> primitiveClassLiteral(TurbineConstantTypeKind.CHAR);
      case DOUBLE -> primitiveClassLiteral(TurbineConstantTypeKind.DOUBLE);
      case FLOAT -> primitiveClassLiteral(TurbineConstantTypeKind.FLOAT);
      case INT -> primitiveClassLiteral(TurbineConstantTypeKind.INT);
      case LONG -> primitiveClassLiteral(TurbineConstantTypeKind.LONG);
      case SHORT -> primitiveClassLiteral(TurbineConstantTypeKind.SHORT);
      case BOOLEAN -> primitiveClassLiteral(TurbineConstantTypeKind.BOOLEAN);
      case VOID -> {
        eat();
        yield finishClassLiteral(position, new Tree.VoidTy(position));
      }
      case AT -> annotation();
      default -> null;
    };
  }

  private Expression primitiveClassLiteral(TurbineConstantTypeKind type) {
    eat();
    return finishClassLiteral(position, new Tree.PrimTy(position, ImmutableList.of(), type));
  }

  private Expression maybeCast() {
    eat();
    return switch (token) {
      case BOOLEAN -> {
        eat();
        yield castTail(TurbineConstantTypeKind.BOOLEAN);
      }
      case BYTE -> {
        eat();
        yield castTail(TurbineConstantTypeKind.BYTE);
      }
      case SHORT -> {
        eat();
        yield castTail(TurbineConstantTypeKind.SHORT);
      }
      case INT -> {
        eat();
        yield castTail(TurbineConstantTypeKind.INT);
      }
      case LONG -> {
        eat();
        yield castTail(TurbineConstantTypeKind.LONG);
      }
      case CHAR -> {
        eat();
        yield castTail(TurbineConstantTypeKind.CHAR);
      }
      case DOUBLE -> {
        eat();
        yield castTail(TurbineConstantTypeKind.DOUBLE);
      }
      case FLOAT -> {
        eat();
        yield castTail(TurbineConstantTypeKind.FLOAT);
      }
      default -> maybeStringCast();
    };
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
      return switch (token) {
        case STRING_LITERAL, IDENT, LPAREN -> {
          Expression expression = primary(false);
          if (expression == null) {
            yield null;
          }
          yield new Tree.TypeCast(position, asClassTy(cvar.position(), cvar.name()), expression);
        }
        default -> new Tree.Paren(position, expr);
      };
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
        case COMMA -> eat();
        case RBRACE -> {
          eat();
          break OUTER;
        }
        default -> {
          return null;
        }
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
      case INT -> {
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
      }
      case LONG -> {
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
      }
      case CHAR -> value = new Const.CharValue(text.charAt(0));
      case FLOAT -> {
        try {
          value = new Const.FloatValue(Float.parseFloat(text.replace("_", "")));
        } catch (NumberFormatException e) {
          throw error(ErrorKind.INVALID_LITERAL, text);
        }
      }
      case DOUBLE -> {
        try {
          value = new Const.DoubleValue(Double.parseDouble(text.replace("_", "")));
        } catch (NumberFormatException e) {
          throw error(ErrorKind.INVALID_LITERAL, text);
        }
      }
      case STRING -> value = new Const.StringValue(text);
      default -> throw new AssertionError(kind);
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
        case INT, LONG -> {
          return expr;
        }
        default -> {}
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
        case IDENT -> bits.add(ident());
        case CLASS -> {
          // TODO(cushon): only allow in annotations?
          eat();
          return new Tree.ClassLiteral(pos, asClassTy(pos, bits.build()));
        }
        default -> {
          return null;
        }
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
    return switch (token) {
      case EOF, SEMI, COMMA, RPAREN ->
          // TODO(cushon): only allow in annotations?
          result;
      default -> null;
    };
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
        case TERNARY -> term1 = ternary(term1);
        case ASSIGN -> term1 = assign(term1, op);
        default -> {
          int pos = position;
          Expression term2 = expression(op.prec());
          if (term2 == null) {
            return null;
          }
          term1 = new Tree.Binary(pos, term1, term2, op);
        }
      }
      if (term1 == null) {
        return null;
      }
    }
  }

  private @Nullable Expression assign(Expression term1, TurbineOperatorKind op) {
    if (!(term1 instanceof Tree.ConstVarName constVarName)) {
      return null;
    }
    ImmutableList<Ident> names = constVarName.name();
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
    Expression qualIdent = qualIdent();
    if (!(qualIdent instanceof Tree.ConstVarName constVarName)) {
      return null;
    }
    ImmutableList<Ident> name = constVarName.name();
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
