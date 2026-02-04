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

package com.google.turbine.binder;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.diag.TurbineLog.TurbineLogWithSource;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.ArrayInit;
import com.google.turbine.tree.Tree.Binary;
import com.google.turbine.tree.Tree.ClassLiteral;
import com.google.turbine.tree.Tree.ClassTy;
import com.google.turbine.tree.Tree.Conditional;
import com.google.turbine.tree.Tree.ConstVarName;
import com.google.turbine.tree.Tree.Expression;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.tree.Tree.Paren;
import com.google.turbine.tree.Tree.PrimTy;
import com.google.turbine.tree.Tree.TypeCast;
import com.google.turbine.tree.Tree.Unary;
import com.google.turbine.tree.TurbineOperatorKind;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Constant expression evaluation.
 *
 * <p>This class requires strict floating point operations. In Java SE 17 and later, the Java SE
 * Platform always requires strict evaluation of floating-point expressions.
 */
public class ConstEvaluator {

  /** The symbol of the originating class, for visibility checks. */
  private final @Nullable ClassSymbol origin;

  /** The symbol of the enclosing class, for lexical field lookups. */
  private final @Nullable ClassSymbol owner;

  /** Member imports of the enclosing compilation unit. */
  private final MemberImportIndex memberImports;

  /** The current source file. */
  private final SourceFile source;

  /** The constant variable environment. */
  private final Env<FieldSymbol, Const.Value> values;

  /** The class environment. */
  private final CompoundEnv<ClassSymbol, TypeBoundClass> env;

  private final Scope scope;

  private final TurbineLogWithSource log;

  public ConstEvaluator(
      @Nullable ClassSymbol origin,
      @Nullable ClassSymbol owner,
      MemberImportIndex memberImports,
      SourceFile source,
      Scope scope,
      Env<FieldSymbol, Const.Value> values,
      CompoundEnv<ClassSymbol, TypeBoundClass> env,
      TurbineLogWithSource log) {

    this.origin = origin;
    this.owner = owner;
    this.memberImports = memberImports;
    this.source = source;
    this.values = values;
    this.env = env;
    this.scope = scope;
    this.log = log;
  }

  /** Evaluates the given expression's value. */
  public @Nullable Const eval(Tree t) {
    return switch (t.kind()) {
      case LITERAL -> {
        Const.Value a = (Const.Value) ((Tree.Literal) t).value();
        if (a == null) {
          yield null;
        }
        yield switch (a.constantTypeKind()) {
          case CHAR, INT, LONG, FLOAT, DOUBLE, BOOLEAN, STRING -> a;
          case SHORT, BYTE, NULL -> throw new AssertionError(a.constantTypeKind());
        };
      }
      case VOID_TY -> throw new AssertionError(t.kind());
      case CONST_VAR_NAME -> evalConstVar((ConstVarName) t);
      case CLASS_LITERAL -> evalClassLiteral((ClassLiteral) t);
      case BINARY -> evalBinary((Binary) t);
      case PAREN -> eval(((Paren) t).expr());
      case TYPE_CAST -> evalCast((TypeCast) t);
      case UNARY -> evalUnary((Unary) t);
      case CONDITIONAL -> evalConditional((Conditional) t);
      case ARRAY_INIT -> evalArrayInit((ArrayInit) t);
      case ANNO_EXPR -> evalAnno(((Tree.AnnoExpr) t).value());
      default -> throw error(t.position(), ErrorKind.EXPRESSION_ERROR);
    };
  }

  /** Evaluates a class literal. */
  Const evalClassLiteral(ClassLiteral t) {
    return new TurbineClassValue(evalClassLiteralType(t.type()));
  }

  private Type evalClassLiteralType(Tree.Type type) {
    return switch (type.kind()) {
      case PRIM_TY -> Type.PrimTy.create(((PrimTy) type).tykind(), ImmutableList.of());
      case VOID_TY -> Type.VOID;
      case CLASS_TY -> resolveClass((ClassTy) type);
      case ARR_TY ->
          Type.ArrayTy.create(evalClassLiteralType(((Tree.ArrTy) type).elem()), ImmutableList.of());
      default -> throw new AssertionError(type.kind());
    };
  }

  /**
   * Resolves the {@link ClassSymbol} for the given {@link Tree.ClassTy}, with handling for
   * non-canonical qualified type names.
   *
   * <p>Similar to {@code HierarchyBinder#resolveClass}, except we can't unconditionally consider
   * members of the current class (e.g. when binding constants inside annotations on that class),
   * and when we do want to consider members we can rely on them being in the current scope (it
   * isn't completed during the hierarchy phase).
   */
  private Type resolveClass(ClassTy classTy) {
    ImmutableList<Ident> flat = classTy.qualifiedName();
    LookupResult result = scope.lookup(new LookupKey(flat));
    if (result == null) {
      log.error(classTy.position(), ErrorKind.CANNOT_RESOLVE, flat.get(0));
      return Type.ErrorTy.create(flat, ImmutableList.of());
    }
    if (result.sym().symKind() != Symbol.Kind.CLASS) {
      throw error(classTy.position(), ErrorKind.UNEXPECTED_TYPE_PARAMETER, flat.get(0));
    }
    ClassSymbol classSym = (ClassSymbol) result.sym();
    for (Ident bit : result.remaining()) {
      classSym = resolveNext(classTy.position(), classSym, bit);
    }
    return Type.ClassTy.asNonParametricClassTy(classSym);
  }

  private ClassSymbol resolveNext(int position, ClassSymbol sym, Ident bit) {
    ClassSymbol next = Resolve.resolve(env, origin, sym, bit);
    if (next == null) {
      throw error(
          position, ErrorKind.SYMBOL_NOT_FOUND, new ClassSymbol(sym.binaryName() + '$' + bit));
    }
    return next;
  }

  /** Evaluates a reference to another constant variable. */
  @Nullable Const evalConstVar(ConstVarName t) {
    FieldInfo field = resolveField(t);
    if (field == null) {
      return null;
    }
    if ((field.access() & TurbineFlag.ACC_ENUM) == TurbineFlag.ACC_ENUM) {
      return new EnumConstantValue(field.sym());
    }
    if (field.value() != null) {
      return field.value();
    }
    return values.get(field.sym());
  }

  @Nullable FieldInfo resolveField(ConstVarName t) {
    Ident simpleName = t.name().get(0);
    FieldInfo field = lexicalField(env, owner, simpleName);
    if (field != null) {
      return field;
    }
    field = resolveQualifiedField(t);
    if (field != null) {
      return field;
    }
    ClassSymbol classSymbol = memberImports.singleMemberImport(simpleName.value());
    if (classSymbol != null) {
      field = Resolve.resolveField(env, origin, classSymbol, simpleName);
      if (field != null) {
        return field;
      }
    }
    Iterator<ClassSymbol> it = memberImports.onDemandImports();
    while (it.hasNext()) {
      field = Resolve.resolveField(env, origin, it.next(), simpleName);
      if (field == null) {
        continue;
      }
      // resolve handles visibility of inherited members; on-demand imports of private members are
      // a special case
      if ((field.access() & TurbineFlag.ACC_PRIVATE) == TurbineFlag.ACC_PRIVATE) {
        continue;
      }
      return field;
    }
    log.error(t.position(), ErrorKind.CANNOT_RESOLVE_FIELD, t.name().get(t.name().size() - 1));
    return null;
  }

  private @Nullable FieldInfo resolveQualifiedField(ConstVarName t) {
    if (t.name().size() <= 1) {
      return null;
    }
    LookupResult result = scope.lookup(new LookupKey(t.name()));
    if (result == null) {
      return null;
    }
    if (result.remaining().isEmpty()) {
      // unexpectedly resolved qualified name to a type
      return null;
    }
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (int i = 0; i < result.remaining().size() - 1; i++) {
      sym = Resolve.resolve(env, sym, sym, result.remaining().get(i));
      if (sym == null) {
        return null;
      }
    }
    return Resolve.resolveField(
        env, origin, sym, result.remaining().get(result.remaining().size() - 1));
  }

  /** Search for constant variables in lexically enclosing scopes. */
  private @Nullable FieldInfo lexicalField(
      Env<ClassSymbol, TypeBoundClass> env, @Nullable ClassSymbol sym, Ident name) {
    while (sym != null) {
      TypeBoundClass info = env.getNonNull(sym);
      FieldInfo field = Resolve.resolveField(env, origin, sym, name);
      if (field != null) {
        return field;
      }
      sym = info.owner();
    }
    return null;
  }

  /** Casts the value to the given type. */
  private Const cast(int position, Type ty, Const value) {
    checkNotNull(value);
    return switch (ty.tyKind()) {
      case CLASS_TY, TY_VAR -> value;
      case PRIM_TY -> {
        if (!value.kind().equals(Const.Kind.PRIMITIVE)) {
          throw error(position, ErrorKind.EXPRESSION_ERROR);
        }
        yield coerce(position, (Const.Value) value, ((Type.PrimTy) ty).primkind());
      }
      default -> throw new AssertionError(ty.tyKind());
    };
  }

  /** Casts the constant value to the given type. */
  Const.Value coerce(int position, Const.Value value, TurbineConstantTypeKind kind) {
    return switch (kind) {
      case BYTE -> asByte(position, value);
      case SHORT -> asShort(position, value);
      case INT -> asInt(position, value);
      case LONG -> asLong(position, value);
      case FLOAT -> asFloat(position, value);
      case DOUBLE -> asDouble(position, value);
      case CHAR -> asChar(position, value);
      case BOOLEAN, STRING, NULL -> {
        if (!value.constantTypeKind().equals(kind)) {
          throw typeError(position, value, kind);
        }
        yield value;
      }
    };
  }

  private Const.BooleanValue asBoolean(int position, Const.Value value) {
    if (!value.constantTypeKind().equals(TurbineConstantTypeKind.BOOLEAN)) {
      throw typeError(position, value, TurbineConstantTypeKind.BOOLEAN);
    }
    return (Const.BooleanValue) value;
  }

  private Const.StringValue asString(int position, Const.Value value) {
    if (!value.constantTypeKind().equals(TurbineConstantTypeKind.STRING)) {
      throw typeError(position, value, TurbineConstantTypeKind.STRING);
    }
    return (Const.StringValue) value;
  }

  private Const.StringValue toString(int position, Const.Value value) {
    String result;
    switch (value.constantTypeKind()) {
      case CHAR -> result = String.valueOf(((Const.CharValue) value).value());
      case SHORT -> result = String.valueOf(((Const.ShortValue) value).value());
      case INT -> result = String.valueOf(((Const.IntValue) value).value());
      case LONG -> result = String.valueOf(((Const.LongValue) value).value());
      case FLOAT -> result = String.valueOf(((Const.FloatValue) value).value());
      case DOUBLE -> result = String.valueOf(((Const.DoubleValue) value).value());
      case BOOLEAN -> result = String.valueOf(((Const.BooleanValue) value).value());
      case BYTE -> result = String.valueOf(((Const.ByteValue) value).value());
      case STRING -> {
        return (Const.StringValue) value;
      }
      default -> throw typeError(position, value, TurbineConstantTypeKind.STRING);
    }
    return new Const.StringValue(result);
  }

  private Const.CharValue asChar(int position, Const.Value value) {
    char result;
    switch (value.constantTypeKind()) {
      case CHAR -> {
        return (Const.CharValue) value;
      }
      case BYTE -> result = (char) ((Const.ByteValue) value).value();
      case SHORT -> result = (char) ((Const.ShortValue) value).value();
      case INT -> result = (char) ((Const.IntValue) value).value();
      case LONG -> result = (char) ((Const.LongValue) value).value();
      case FLOAT -> result = (char) ((Const.FloatValue) value).value();
      case DOUBLE -> result = (char) ((Const.DoubleValue) value).value();
      default -> throw typeError(position, value, TurbineConstantTypeKind.CHAR);
    }
    return new Const.CharValue(result);
  }

  private Const.ByteValue asByte(int position, Const.Value value) {
    byte result;
    switch (value.constantTypeKind()) {
      case CHAR -> result = (byte) ((Const.CharValue) value).value();
      case BYTE -> {
        return (Const.ByteValue) value;
      }
      case SHORT -> result = (byte) ((Const.ShortValue) value).value();
      case INT -> result = (byte) ((Const.IntValue) value).value();
      case LONG -> result = (byte) ((Const.LongValue) value).value();
      case FLOAT -> result = (byte) ((Const.FloatValue) value).value();
      case DOUBLE -> result = (byte) ((Const.DoubleValue) value).value();
      default -> throw typeError(position, value, TurbineConstantTypeKind.BYTE);
    }
    return new Const.ByteValue(result);
  }

  private Const.ShortValue asShort(int position, Const.Value value) {
    short result;
    switch (value.constantTypeKind()) {
      case CHAR -> result = (short) ((Const.CharValue) value).value();
      case BYTE -> result = ((Const.ByteValue) value).value();
      case SHORT -> {
        return (Const.ShortValue) value;
      }
      case INT -> result = (short) ((Const.IntValue) value).value();
      case LONG -> result = (short) ((Const.LongValue) value).value();
      case FLOAT -> result = (short) ((Const.FloatValue) value).value();
      case DOUBLE -> result = (short) ((Const.DoubleValue) value).value();
      default -> throw typeError(position, value, TurbineConstantTypeKind.SHORT);
    }
    return new Const.ShortValue(result);
  }

  private Const.IntValue asInt(int position, Const.Value value) {
    int result;
    switch (value.constantTypeKind()) {
      case CHAR -> result = ((Const.CharValue) value).value();
      case BYTE -> result = ((Const.ByteValue) value).value();
      case SHORT -> result = ((Const.ShortValue) value).value();
      case INT -> {
        return (Const.IntValue) value;
      }
      case LONG -> result = (int) ((Const.LongValue) value).value();
      case FLOAT -> result = (int) ((Const.FloatValue) value).value();
      case DOUBLE -> result = (int) ((Const.DoubleValue) value).value();
      default -> throw typeError(position, value, TurbineConstantTypeKind.INT);
    }
    return new Const.IntValue(result);
  }

  private Const.LongValue asLong(int position, Const.Value value) {
    long result;
    switch (value.constantTypeKind()) {
      case CHAR -> result = ((Const.CharValue) value).value();
      case BYTE -> result = ((Const.ByteValue) value).value();
      case SHORT -> result = ((Const.ShortValue) value).value();
      case INT -> result = ((Const.IntValue) value).value();
      case LONG -> {
        return (Const.LongValue) value;
      }
      case FLOAT -> result = (long) ((Const.FloatValue) value).value();
      case DOUBLE -> result = (long) ((Const.DoubleValue) value).value();
      default -> throw typeError(position, value, TurbineConstantTypeKind.LONG);
    }
    return new Const.LongValue(result);
  }

  private Const.FloatValue asFloat(int position, Const.Value value) {
    float result;
    switch (value.constantTypeKind()) {
      case CHAR -> result = ((Const.CharValue) value).value();
      case BYTE -> result = ((Const.ByteValue) value).value();
      case SHORT -> result = ((Const.ShortValue) value).value();
      case INT -> result = (float) ((Const.IntValue) value).value();
      case LONG -> result = (float) ((Const.LongValue) value).value();
      case FLOAT -> {
        return (Const.FloatValue) value;
      }
      case DOUBLE -> result = (float) ((Const.DoubleValue) value).value();
      default -> throw typeError(position, value, TurbineConstantTypeKind.FLOAT);
    }
    return new Const.FloatValue(result);
  }

  private Const.DoubleValue asDouble(int position, Const.Value value) {
    double result;
    switch (value.constantTypeKind()) {
      case CHAR -> result = ((Const.CharValue) value).value();
      case BYTE -> result = ((Const.ByteValue) value).value();
      case SHORT -> result = ((Const.ShortValue) value).value();
      case INT -> result = ((Const.IntValue) value).value();
      case LONG -> result = (double) ((Const.LongValue) value).value();
      case FLOAT -> result = ((Const.FloatValue) value).value();
      case DOUBLE -> {
        return (Const.DoubleValue) value;
      }
      default -> throw typeError(position, value, TurbineConstantTypeKind.DOUBLE);
    }
    return new Const.DoubleValue(result);
  }

  private Const.@Nullable Value evalValue(Expression tree) {
    Const result = eval(tree);
    // TODO(cushon): consider distinguishing between constant field and annotation values,
    // and only allowing class literals / enum constants in the latter
    return (result instanceof Const.Value value) ? value : null;
  }

  private Const.@Nullable Value evalConditional(Conditional t) {
    Const.Value condition = evalValue(t.cond());
    if (condition == null) {
      return null;
    }
    return asBoolean(t.position(), condition).value()
        ? evalValue(t.iftrue())
        : evalValue(t.iffalse());
  }

  private Const.@Nullable Value evalUnary(Unary t) {
    Const.Value expr = evalValue(t.expr());
    if (expr == null) {
      return null;
    }
    return switch (t.op()) {
      case NOT -> unaryNegate(t.position(), expr);
      case BITWISE_COMP -> bitwiseComp(t.position(), expr);
      case UNARY_PLUS -> unaryPlus(t.position(), expr);
      case NEG -> unaryMinus(t.position(), expr);
      default -> throw new AssertionError(t.op());
    };
  }

  private Const.@Nullable Value unaryNegate(int position, Const.Value expr) {
    return switch (expr.constantTypeKind()) {
      case BOOLEAN -> new Const.BooleanValue(!asBoolean(position, expr).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, expr.constantTypeKind());
    };
  }

  private Const.@Nullable Value bitwiseComp(int position, Const.Value expr) {
    expr = promoteUnary(position, expr);
    return switch (expr.constantTypeKind()) {
      case INT -> new Const.IntValue(~asInt(position, expr).value());
      case LONG -> new Const.LongValue(~asLong(position, expr).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, expr.constantTypeKind());
    };
  }

  private Const.@Nullable Value unaryPlus(int position, Const.Value expr) {
    expr = promoteUnary(position, expr);
    return switch (expr.constantTypeKind()) {
      case INT -> new Const.IntValue(+asInt(position, expr).value());
      case LONG -> new Const.LongValue(+asLong(position, expr).value());
      case FLOAT -> new Const.FloatValue(+asFloat(position, expr).value());
      case DOUBLE -> new Const.DoubleValue(+asDouble(position, expr).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, expr.constantTypeKind());
    };
  }

  private Const.@Nullable Value unaryMinus(int position, Const.Value expr) {
    expr = promoteUnary(position, expr);
    return switch (expr.constantTypeKind()) {
      case INT -> new Const.IntValue(-asInt(position, expr).value());
      case LONG -> new Const.LongValue(-asLong(position, expr).value());
      case FLOAT -> new Const.FloatValue(-asFloat(position, expr).value());
      case DOUBLE -> new Const.DoubleValue(-asDouble(position, expr).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, expr.constantTypeKind());
    };
  }

  private Const.@Nullable Value evalCast(TypeCast t) {
    Const.Value expr = evalValue(t.expr());
    if (expr == null) {
      return null;
    }
    switch (t.ty().kind()) {
      case PRIM_TY -> {
        return coerce(t.expr().position(), expr, ((Tree.PrimTy) t.ty()).tykind());
      }
      case CLASS_TY -> {
        ClassTy classTy = (ClassTy) t.ty();
        // TODO(cushon): check package?
        if (!classTy.name().value().equals("String")) {
          // Explicit boxing cases (e.g. `(Boolean) false`) are legal, but not const exprs.
          return null;
        }
        return toString(t.expr().position(), expr);
      }
      default -> throw new AssertionError(t.ty().kind());
    }
  }

  private Const.@Nullable Value add(int position, Const.Value a, Const.Value b) {
    if (a.constantTypeKind() == TurbineConstantTypeKind.STRING
        || b.constantTypeKind() == TurbineConstantTypeKind.STRING) {
      return new Const.StringValue(toString(position, a).value() + toString(position, b).value());
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.IntValue(asInt(position, a).value() + asInt(position, b).value());
      case LONG -> new Const.LongValue(asLong(position, a).value() + asLong(position, b).value());
      case FLOAT ->
          new Const.FloatValue(asFloat(position, a).value() + asFloat(position, b).value());
      case DOUBLE ->
          new Const.DoubleValue(asDouble(position, a).value() + asDouble(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.@Nullable Value subtract(int position, Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.IntValue(asInt(position, a).value() - asInt(position, b).value());
      case LONG -> new Const.LongValue(asLong(position, a).value() - asLong(position, b).value());
      case FLOAT ->
          new Const.FloatValue(asFloat(position, a).value() - asFloat(position, b).value());
      case DOUBLE ->
          new Const.DoubleValue(asDouble(position, a).value() - asDouble(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.@Nullable Value mult(int position, Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.IntValue(asInt(position, a).value() * asInt(position, b).value());
      case LONG -> new Const.LongValue(asLong(position, a).value() * asLong(position, b).value());
      case FLOAT ->
          new Const.FloatValue(asFloat(position, a).value() * asFloat(position, b).value());
      case DOUBLE ->
          new Const.DoubleValue(asDouble(position, a).value() * asDouble(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.@Nullable Value divide(int position, Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.IntValue(asInt(position, a).value() / asInt(position, b).value());
      case LONG -> new Const.LongValue(asLong(position, a).value() / asLong(position, b).value());
      case FLOAT ->
          new Const.FloatValue(asFloat(position, a).value() / asFloat(position, b).value());
      case DOUBLE ->
          new Const.DoubleValue(asDouble(position, a).value() / asDouble(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.@Nullable Value mod(int position, Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.IntValue(asInt(position, a).value() % asInt(position, b).value());
      case LONG -> new Const.LongValue(asLong(position, a).value() % asLong(position, b).value());
      case FLOAT ->
          new Const.FloatValue(asFloat(position, a).value() % asFloat(position, b).value());
      case DOUBLE ->
          new Const.DoubleValue(asDouble(position, a).value() % asDouble(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private static final int INT_SHIFT_MASK = 0b11111;

  private static final int LONG_SHIFT_MASK = 0b111111;

  private Const.@Nullable Value shiftLeft(int position, Const.Value a, Const.Value b) {
    a = promoteUnary(position, a);
    b = promoteUnary(position, b);
    return switch (a.constantTypeKind()) {
      case INT ->
          new Const.IntValue(
              asInt(position, a).value() << (asInt(position, b).value() & INT_SHIFT_MASK));
      case LONG ->
          new Const.LongValue(
              asLong(position, a).value() << (asInt(position, b).value() & LONG_SHIFT_MASK));
      default -> throw error(position, ErrorKind.OPERAND_TYPE, a.constantTypeKind());
    };
  }

  private Const.@Nullable Value shiftRight(int position, Const.Value a, Const.Value b) {
    a = promoteUnary(position, a);
    b = promoteUnary(position, b);
    return switch (a.constantTypeKind()) {
      case INT ->
          new Const.IntValue(
              asInt(position, a).value() >> (asInt(position, b).value() & INT_SHIFT_MASK));
      case LONG ->
          new Const.LongValue(
              asLong(position, a).value() >> (asInt(position, b).value() & LONG_SHIFT_MASK));
      default -> throw error(position, ErrorKind.OPERAND_TYPE, a.constantTypeKind());
    };
  }

  private Const.@Nullable Value unsignedShiftRight(int position, Const.Value a, Const.Value b) {
    a = promoteUnary(position, a);
    b = promoteUnary(position, b);
    return switch (a.constantTypeKind()) {
      case INT ->
          new Const.IntValue(
              asInt(position, a).value() >>> (asInt(position, b).value() & INT_SHIFT_MASK));
      case LONG ->
          new Const.LongValue(
              asLong(position, a).value() >>> (asInt(position, b).value() & LONG_SHIFT_MASK));
      default -> throw error(position, ErrorKind.OPERAND_TYPE, a.constantTypeKind());
    };
  }

  private Const.@Nullable Value lessThan(int position, Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.BooleanValue(asInt(position, a).value() < asInt(position, b).value());
      case LONG ->
          new Const.BooleanValue(asLong(position, a).value() < asLong(position, b).value());
      case FLOAT ->
          new Const.BooleanValue(asFloat(position, a).value() < asFloat(position, b).value());
      case DOUBLE ->
          new Const.BooleanValue(asDouble(position, a).value() < asDouble(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.@Nullable Value lessThanEqual(int position, Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.BooleanValue(asInt(position, a).value() <= asInt(position, b).value());
      case LONG ->
          new Const.BooleanValue(asLong(position, a).value() <= asLong(position, b).value());
      case FLOAT ->
          new Const.BooleanValue(asFloat(position, a).value() <= asFloat(position, b).value());
      case DOUBLE ->
          new Const.BooleanValue(asDouble(position, a).value() <= asDouble(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.@Nullable Value greaterThan(int position, Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.BooleanValue(asInt(position, a).value() > asInt(position, b).value());
      case LONG ->
          new Const.BooleanValue(asLong(position, a).value() > asLong(position, b).value());
      case FLOAT ->
          new Const.BooleanValue(asFloat(position, a).value() > asFloat(position, b).value());
      case DOUBLE ->
          new Const.BooleanValue(asDouble(position, a).value() > asDouble(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.@Nullable Value greaterThanEqual(int position, Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.BooleanValue(asInt(position, a).value() >= asInt(position, b).value());
      case LONG ->
          new Const.BooleanValue(asLong(position, a).value() >= asLong(position, b).value());
      case FLOAT ->
          new Const.BooleanValue(asFloat(position, a).value() >= asFloat(position, b).value());
      case DOUBLE ->
          new Const.BooleanValue(asDouble(position, a).value() >= asDouble(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.@Nullable Value equal(int position, Const.Value a, Const.Value b) {
    switch (a.constantTypeKind()) {
      case STRING -> {
        return new Const.BooleanValue(
            asString(position, a).value().equals(asString(position, b).value()));
      }
      case BOOLEAN -> {
        return new Const.BooleanValue(
            asBoolean(position, a).value() == asBoolean(position, b).value());
      }
      default -> {}
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.BooleanValue(asInt(position, a).value() == asInt(position, b).value());
      case LONG ->
          new Const.BooleanValue(asLong(position, a).value() == asLong(position, b).value());
      case FLOAT ->
          new Const.BooleanValue(asFloat(position, a).value() == asFloat(position, b).value());
      case DOUBLE ->
          new Const.BooleanValue(asDouble(position, a).value() == asDouble(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.@Nullable Value notEqual(int position, Const.Value a, Const.Value b) {
    switch (a.constantTypeKind()) {
      case STRING -> {
        return new Const.BooleanValue(
            !asString(position, a).value().equals(asString(position, b).value()));
      }
      case BOOLEAN -> {
        return new Const.BooleanValue(
            asBoolean(position, a).value() != asBoolean(position, b).value());
      }
      default -> {}
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.BooleanValue(asInt(position, a).value() != asInt(position, b).value());
      case LONG ->
          new Const.BooleanValue(asLong(position, a).value() != asLong(position, b).value());
      case FLOAT ->
          new Const.BooleanValue(asFloat(position, a).value() != asFloat(position, b).value());
      case DOUBLE ->
          new Const.BooleanValue(asDouble(position, a).value() != asDouble(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.Value bitwiseAnd(int position, Const.Value a, Const.Value b) {
    switch (a.constantTypeKind()) {
      case BOOLEAN -> {
        // We're evaluating constants and aren't required to report errors, so using
        // short-circuiting operators is faster and not answer changing.
        return new Const.BooleanValue(
            asBoolean(position, a).value() && asBoolean(position, b).value());
      }
      default -> {}
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.IntValue(asInt(position, a).value() & asInt(position, b).value());
      case LONG -> new Const.LongValue(asLong(position, a).value() & asLong(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.Value bitwiseOr(int position, Const.Value a, Const.Value b) {
    switch (a.constantTypeKind()) {
      case BOOLEAN -> {
        // We're evaluating constants and aren't required to report errors, so using
        // short-circuiting operators is faster and not answer changing.
        return new Const.BooleanValue(
            asBoolean(position, a).value() || asBoolean(position, b).value());
      }
      default -> {}
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.IntValue(asInt(position, a).value() | asInt(position, b).value());
      case LONG -> new Const.LongValue(asLong(position, a).value() | asLong(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.@Nullable Value bitwiseXor(int position, Const.Value a, Const.Value b) {
    switch (a.constantTypeKind()) {
      case BOOLEAN -> {
        return new Const.BooleanValue(
            asBoolean(position, a).value() ^ asBoolean(position, b).value());
      }
      default -> {}
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    return switch (type) {
      case INT -> new Const.IntValue(asInt(position, a).value() ^ asInt(position, b).value());
      case LONG -> new Const.LongValue(asLong(position, a).value() ^ asLong(position, b).value());
      default -> throw error(position, ErrorKind.OPERAND_TYPE, type);
    };
  }

  private Const.@Nullable Value evalBinary(Binary t) {
    Const.Value result = null;
    boolean first = true;
    for (Expression child : t.children()) {
      Const.Value value = evalValue(child);
      if (value == null) {
        return null;
      }
      if (first) {
        result = value;
      } else {
        result = evalBinary(child.position(), t.op(), requireNonNull(result), value);
      }
      first = false;
    }
    return result;
  }

  private Const.@Nullable Value evalBinary(
      int position, TurbineOperatorKind op, Const.Value lhs, Const.Value rhs) {
    return switch (op) {
      case PLUS -> add(position, lhs, rhs);
      case MINUS -> subtract(position, lhs, rhs);
      case MULT -> mult(position, lhs, rhs);
      case DIVIDE -> divide(position, lhs, rhs);
      case MODULO -> mod(position, lhs, rhs);
      case SHIFT_LEFT -> shiftLeft(position, lhs, rhs);
      case SHIFT_RIGHT -> shiftRight(position, lhs, rhs);
      case UNSIGNED_SHIFT_RIGHT -> unsignedShiftRight(position, lhs, rhs);
      case LESS_THAN -> lessThan(position, lhs, rhs);
      case GREATER_THAN -> greaterThan(position, lhs, rhs);
      case LESS_THAN_EQ -> lessThanEqual(position, lhs, rhs);
      case GREATER_THAN_EQ -> greaterThanEqual(position, lhs, rhs);
      case EQUAL -> equal(position, lhs, rhs);
      case NOT_EQUAL -> notEqual(position, lhs, rhs);
      case AND ->
          new Const.BooleanValue(
              asBoolean(position, lhs).value() && asBoolean(position, rhs).value());
      case OR ->
          new Const.BooleanValue(
              asBoolean(position, lhs).value() || asBoolean(position, rhs).value());
      case BITWISE_AND -> bitwiseAnd(position, lhs, rhs);
      case BITWISE_XOR -> bitwiseXor(position, lhs, rhs);
      case BITWISE_OR -> bitwiseOr(position, lhs, rhs);
      default -> throw new AssertionError(op);
    };
  }

  private Const.Value promoteUnary(int position, Const.Value v) {
    return switch (v.constantTypeKind()) {
      case CHAR, SHORT, BYTE -> asInt(position, v);
      case INT, LONG, FLOAT, DOUBLE -> v;
      default -> throw error(position, ErrorKind.OPERAND_TYPE, v.constantTypeKind());
    };
  }

  private TurbineConstantTypeKind promoteBinary(int position, Const.Value a, Const.Value b) {
    a = promoteUnary(position, a);
    b = promoteUnary(position, b);
    return switch (a.constantTypeKind()) {
      case INT ->
          switch (b.constantTypeKind()) {
            case INT, LONG, DOUBLE, FLOAT -> b.constantTypeKind();
            default -> throw error(position, ErrorKind.OPERAND_TYPE, b.constantTypeKind());
          };
      case LONG ->
          switch (b.constantTypeKind()) {
            case INT -> TurbineConstantTypeKind.LONG;
            case LONG, DOUBLE, FLOAT -> b.constantTypeKind();
            default -> throw error(position, ErrorKind.OPERAND_TYPE, b.constantTypeKind());
          };
      case FLOAT ->
          switch (b.constantTypeKind()) {
            case INT, LONG, FLOAT -> TurbineConstantTypeKind.FLOAT;
            case DOUBLE -> TurbineConstantTypeKind.DOUBLE;
            default -> throw error(position, ErrorKind.OPERAND_TYPE, b.constantTypeKind());
          };
      case DOUBLE ->
          switch (b.constantTypeKind()) {
            case INT, LONG, FLOAT, DOUBLE -> TurbineConstantTypeKind.DOUBLE;
            default -> throw error(position, ErrorKind.OPERAND_TYPE, b.constantTypeKind());
          };
      default -> throw error(position, ErrorKind.OPERAND_TYPE, a.constantTypeKind());
    };
  }

  ImmutableList<AnnoInfo> evaluateAnnotations(ImmutableList<AnnoInfo> annotations) {
    ImmutableList.Builder<AnnoInfo> result = ImmutableList.builder();
    for (AnnoInfo annotation : annotations) {
      result.add(evaluateAnnotation(annotation));
    }
    return result.build();
  }

  /**
   * Evaluates annotation arguments given the symbol of the annotation declaration and a list of
   * expression trees.
   */
  AnnoInfo evaluateAnnotation(AnnoInfo info) {
    // bail if annotation has not been resolved
    if (info.sym() == null) {
      return info;
    }
    TypeBoundClass annoClass = env.getNonNull(info.sym());
    if (annoClass.kind() != TurbineTyKind.ANNOTATION) {
      // we've already reported an error for non-annotation symbols used as annotations,
      // skip error handling for annotation arguments
      return info;
    }
    Map<String, MethodInfo> template = new LinkedHashMap<>();
    if (annoClass != null) {
      for (MethodInfo method : annoClass.methods()) {
        template.put(method.name(), method);
      }
    }

    Map<String, Const> values = new LinkedHashMap<>();
    for (Expression arg : info.args()) {
      Expression expr;
      String key;
      if (arg.kind() == Tree.Kind.ASSIGN) {
        Tree.Assign assign = (Tree.Assign) arg;
        key = assign.name().value();
        expr = assign.expr();
      } else {
        if (info.args().size() != 1) {
          throw error(arg.position(), ErrorKind.ANNOTATION_VALUE_NAME);
        }
        // expand the implicit 'value' name; `@Foo(42)` is sugar for `@Foo(value=42)`
        key = "value";
        expr = arg;
      }
      MethodInfo methodInfo = template.remove(key);
      if (methodInfo == null) {
        log.error(
            arg.position(),
            ErrorKind.CANNOT_RESOLVE,
            String.format("element %s() in %s", key, info.sym()));
        continue;
      }
      Const value = evalAnnotationValue(expr, methodInfo.returnType());
      if (value == null) {
        log.error(expr.position(), ErrorKind.EXPRESSION_ERROR);
        continue;
      }
      Const existing = values.put(key, value);
      if (existing != null) {
        log.error(arg.position(), ErrorKind.INVALID_ANNOTATION_ARGUMENT);
        continue;
      }
    }
    for (MethodInfo methodInfo : template.values()) {
      if (!methodInfo.hasDefaultValue()) {
        throw error(
            info.tree().position(), ErrorKind.MISSING_ANNOTATION_ARGUMENT, methodInfo.name());
      }
    }
    return info.withValues(ImmutableMap.copyOf(values));
  }

  private @Nullable TurbineAnnotationValue evalAnno(Tree.Anno t) {
    LookupResult result = scope.lookup(new LookupKey(t.name()));
    if (result == null) {
      log.error(
          t.name().get(0).position(), ErrorKind.CANNOT_RESOLVE, Joiner.on(".").join(t.name()));
      return null;
    }
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (Ident name : result.remaining()) {
      sym = Resolve.resolve(env, sym, sym, name);
      if (sym == null) {
        log.error(name.position(), ErrorKind.CANNOT_RESOLVE, name.value());
        return null;
      }
    }
    if (sym == null) {
      return null;
    }
    if (env.getNonNull(sym).kind() != TurbineTyKind.ANNOTATION) {
      log.error(t.position(), ErrorKind.NOT_AN_ANNOTATION, sym);
    }
    AnnoInfo annoInfo = evaluateAnnotation(new AnnoInfo(source, sym, t, ImmutableMap.of()));
    return new TurbineAnnotationValue(annoInfo);
  }

  private Const.@Nullable ArrayInitValue evalArrayInit(ArrayInit t) {
    ImmutableList.Builder<Const> elements = ImmutableList.builder();
    for (Expression e : t.exprs()) {
      Const arg = eval(e);
      if (arg == null) {
        return null;
      }
      elements.add(arg);
    }
    return new Const.ArrayInitValue(elements.build());
  }

  @Nullable Const evalAnnotationValue(Tree tree, Type ty) {
    if (ty == null) {
      throw error(tree.position(), ErrorKind.EXPRESSION_ERROR);
    }
    Const value = eval(tree);
    if (value == null) {
      log.error(tree.position(), ErrorKind.EXPRESSION_ERROR);
      return null;
    }
    return switch (ty.tyKind()) {
      case PRIM_TY -> {
        if (!(value instanceof Const.Value constValue)) {
          throw error(tree.position(), ErrorKind.EXPRESSION_ERROR);
        }
        yield coerce(tree.position(), constValue, ((Type.PrimTy) ty).primkind());
      }
      case CLASS_TY, TY_VAR -> value;
      case ARRAY_TY -> {
        Type elementType = ((Type.ArrayTy) ty).elementType();
        ImmutableList<Const> elements =
            value.kind() == Const.Kind.ARRAY
                ? ((Const.ArrayInitValue) value).elements()
                : ImmutableList.of(value);
        ImmutableList.Builder<Const> coerced = ImmutableList.builder();
        for (Const element : elements) {
          coerced.add(cast(tree.position(), elementType, element));
        }
        yield new Const.ArrayInitValue(coerced.build());
      }
      default -> throw new AssertionError(ty.tyKind());
    };
  }

  private TurbineError error(int position, ErrorKind kind, Object... args) {
    return TurbineError.format(source, position, kind, args);
  }

  private TurbineError typeError(int position, Const.Value value, TurbineConstantTypeKind kind) {
    return error(position, ErrorKind.TYPE_CONVERSION, value, value.constantTypeKind(), kind);
  }

  public Const.@Nullable Value evalFieldInitializer(Expression expression, Type type) {
    try {
      Const value = eval(expression);
      if (value == null || value.kind() != Const.Kind.PRIMITIVE) {
        return null;
      }
      return (Const.Value) cast(expression.position(), type, value);
    } catch (Const.ConstCastError error) {
      return null;
    }
  }
}
