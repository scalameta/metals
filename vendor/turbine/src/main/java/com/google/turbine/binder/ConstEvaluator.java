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
import com.google.common.collect.Iterables;
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
import com.google.turbine.diag.TurbineDiagnostic;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.diag.TurbineLog.TurbineLogWithSource;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.ArrayInitValue;
import com.google.turbine.model.Const.CharValue;
import com.google.turbine.model.Const.ConstCastError;
import com.google.turbine.model.Const.DoubleValue;
import com.google.turbine.model.Const.FloatValue;
import com.google.turbine.model.Const.StringValue;
import com.google.turbine.model.Const.Value;
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
import java.util.ArrayDeque;
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
  private final Env<FieldSymbol, Value> values;

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
      Env<FieldSymbol, Value> values,
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
    switch (t.kind()) {
      case LITERAL:
        {
          Value a = (Value) ((Tree.Literal) t).value();
          if (a == null) {
            return null;
          }
          switch (a.constantTypeKind()) {
            case CHAR:
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
            case BOOLEAN:
            case STRING:
              return a;
            case SHORT:
            case BYTE:
            case NULL:
          }
          throw new AssertionError(a.constantTypeKind());
        }
      case VOID_TY:
        throw new AssertionError(t.kind());
      case CONST_VAR_NAME:
        return evalConstVar((ConstVarName) t);
      case CLASS_LITERAL:
        return evalClassLiteral((ClassLiteral) t);
      case BINARY:
        return evalBinary((Binary) t);
      case PAREN:
        return eval(((Paren) t).expr());
      case TYPE_CAST:
        return evalCast((TypeCast) t);
      case UNARY:
        return evalUnary((Unary) t);
      case CONDITIONAL:
        return evalConditional((Conditional) t);
      case ARRAY_INIT:
        return evalArrayInit((ArrayInit) t);
      case ANNO_EXPR:
        return evalAnno(((Tree.AnnoExpr) t).value());
      default:
        throw error(t.position(), ErrorKind.EXPRESSION_ERROR);
    }
  }

  /** Evaluates a class literal. */
  Const evalClassLiteral(ClassLiteral t) {
    return new TurbineClassValue(evalClassLiteralType(t.type()));
  }

  private Type evalClassLiteralType(Tree.Type type) {
    switch (type.kind()) {
      case PRIM_TY:
        return Type.PrimTy.create(((PrimTy) type).tykind(), ImmutableList.of());
      case VOID_TY:
        return Type.VOID;
      case CLASS_TY:
        return resolveClass((ClassTy) type);
      case ARR_TY:
        return Type.ArrayTy.create(
            evalClassLiteralType(((Tree.ArrTy) type).elem()), ImmutableList.of());
      default:
        throw new AssertionError(type.kind());
    }
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
    ArrayDeque<Ident> flat = new ArrayDeque<>();
    for (ClassTy curr = classTy; curr != null; curr = curr.base().orElse(null)) {
      flat.addFirst(curr.name());
    }
    LookupResult result = scope.lookup(new LookupKey(ImmutableList.copyOf(flat)));
    if (result == null) {
      log.error(classTy.position(), ErrorKind.CANNOT_RESOLVE, flat.getFirst());
      return Type.ErrorTy.create(flat, ImmutableList.of());
    }
    if (result.sym().symKind() != Symbol.Kind.CLASS) {
      throw error(classTy.position(), ErrorKind.UNEXPECTED_TYPE_PARAMETER, flat.getFirst());
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

  FieldInfo resolveField(ConstVarName t) {
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
    throw error(
        t.position(),
        ErrorKind.CANNOT_RESOLVE,
        String.format("field %s", Iterables.getLast(t.name())));
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
    return Resolve.resolveField(env, origin, sym, Iterables.getLast(result.remaining()));
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
    switch (ty.tyKind()) {
      case CLASS_TY:
      case TY_VAR:
        return value;
      case PRIM_TY:
        if (!value.kind().equals(Const.Kind.PRIMITIVE)) {
          throw error(position, ErrorKind.EXPRESSION_ERROR);
        }
        return coerce(position, (Value) value, ((Type.PrimTy) ty).primkind());
      default:
        throw new AssertionError(ty.tyKind());
    }
  }

  /** Casts the constant value to the given type. */
  Value coerce(int position, Value value, TurbineConstantTypeKind kind) {
    switch (kind) {
      case BYTE:
        return asByte(position, value);
      case SHORT:
        return asShort(position, value);
      case INT:
        return asInt(position, value);
      case LONG:
        return asLong(position, value);
      case FLOAT:
        return asFloat(position, value);
      case DOUBLE:
        return asDouble(position, value);
      case CHAR:
        return asChar(position, value);
      case BOOLEAN:
      case STRING:
      case NULL:
        if (!value.constantTypeKind().equals(kind)) {
          throw typeError(position, value, kind);
        }
        return value;
    }
    throw new AssertionError(kind);
  }

  private Const.BooleanValue asBoolean(int position, Value value) {
    if (!value.constantTypeKind().equals(TurbineConstantTypeKind.BOOLEAN)) {
      throw typeError(position, value, TurbineConstantTypeKind.BOOLEAN);
    }
    return (Const.BooleanValue) value;
  }

  private Const.StringValue asString(int position, Value value) {
    if (!value.constantTypeKind().equals(TurbineConstantTypeKind.STRING)) {
      throw typeError(position, value, TurbineConstantTypeKind.STRING);
    }
    return (Const.StringValue) value;
  }

  private Const.StringValue toString(int position, Value value) {
    String result;
    switch (value.constantTypeKind()) {
      case CHAR:
        result = String.valueOf(((Const.CharValue) value).value());
        break;
      case SHORT:
        result = String.valueOf(((Const.ShortValue) value).value());
        break;
      case INT:
        result = String.valueOf(((Const.IntValue) value).value());
        break;
      case LONG:
        result = String.valueOf(((Const.LongValue) value).value());
        break;
      case FLOAT:
        result = String.valueOf(((Const.FloatValue) value).value());
        break;
      case DOUBLE:
        result = String.valueOf(((Const.DoubleValue) value).value());
        break;
      case BOOLEAN:
        result = String.valueOf(((Const.BooleanValue) value).value());
        break;
      case BYTE:
        result = String.valueOf(((Const.ByteValue) value).value());
        break;
      case STRING:
        return (StringValue) value;
      default:
        throw typeError(position, value, TurbineConstantTypeKind.STRING);
    }
    return new Const.StringValue(result);
  }

  private Const.CharValue asChar(int position, Value value) {
    char result;
    switch (value.constantTypeKind()) {
      case CHAR:
        return (Const.CharValue) value;
      case BYTE:
        result = (char) ((Const.ByteValue) value).value();
        break;
      case SHORT:
        result = (char) ((Const.ShortValue) value).value();
        break;
      case INT:
        result = (char) ((Const.IntValue) value).value();
        break;
      case LONG:
        result = (char) ((Const.LongValue) value).value();
        break;
      case FLOAT:
        result = (char) ((Const.FloatValue) value).value();
        break;
      case DOUBLE:
        result = (char) ((Const.DoubleValue) value).value();
        break;
      default:
        throw typeError(position, value, TurbineConstantTypeKind.CHAR);
    }
    return new Const.CharValue(result);
  }

  private Const.ByteValue asByte(int position, Value value) {
    byte result;
    switch (value.constantTypeKind()) {
      case CHAR:
        result = (byte) ((Const.CharValue) value).value();
        break;
      case BYTE:
        return (Const.ByteValue) value;
      case SHORT:
        result = (byte) ((Const.ShortValue) value).value();
        break;
      case INT:
        result = (byte) ((Const.IntValue) value).value();
        break;
      case LONG:
        result = (byte) ((Const.LongValue) value).value();
        break;
      case FLOAT:
        result = (byte) ((Const.FloatValue) value).value();
        break;
      case DOUBLE:
        result = (byte) ((Const.DoubleValue) value).value();
        break;
      default:
        throw typeError(position, value, TurbineConstantTypeKind.BYTE);
    }
    return new Const.ByteValue(result);
  }

  private Const.ShortValue asShort(int position, Value value) {
    short result;
    switch (value.constantTypeKind()) {
      case CHAR:
        result = (short) ((Const.CharValue) value).value();
        break;
      case BYTE:
        result = ((Const.ByteValue) value).value();
        break;
      case SHORT:
        return (Const.ShortValue) value;
      case INT:
        result = (short) ((Const.IntValue) value).value();
        break;
      case LONG:
        result = (short) ((Const.LongValue) value).value();
        break;
      case FLOAT:
        result = (short) ((Const.FloatValue) value).value();
        break;
      case DOUBLE:
        result = (short) ((Const.DoubleValue) value).value();
        break;
      default:
        throw typeError(position, value, TurbineConstantTypeKind.SHORT);
    }
    return new Const.ShortValue(result);
  }

  private Const.IntValue asInt(int position, Value value) {
    int result;
    switch (value.constantTypeKind()) {
      case CHAR:
        result = ((CharValue) value).value();
        break;
      case BYTE:
        result = ((Const.ByteValue) value).value();
        break;
      case SHORT:
        result = ((Const.ShortValue) value).value();
        break;
      case INT:
        return (Const.IntValue) value;
      case LONG:
        result = (int) ((Const.LongValue) value).value();
        break;
      case FLOAT:
        result = (int) ((Const.FloatValue) value).value();
        break;
      case DOUBLE:
        result = (int) ((Const.DoubleValue) value).value();
        break;
      default:
        throw typeError(position, value, TurbineConstantTypeKind.INT);
    }
    return new Const.IntValue(result);
  }

  private Const.LongValue asLong(int position, Value value) {
    long result;
    switch (value.constantTypeKind()) {
      case CHAR:
        result = ((CharValue) value).value();
        break;
      case BYTE:
        result = ((Const.ByteValue) value).value();
        break;
      case SHORT:
        result = ((Const.ShortValue) value).value();
        break;
      case INT:
        result = ((Const.IntValue) value).value();
        break;
      case LONG:
        return (Const.LongValue) value;
      case FLOAT:
        result = (long) ((Const.FloatValue) value).value();
        break;
      case DOUBLE:
        result = (long) ((Const.DoubleValue) value).value();
        break;
      default:
        throw typeError(position, value, TurbineConstantTypeKind.LONG);
    }
    return new Const.LongValue(result);
  }

  private Const.FloatValue asFloat(int position, Value value) {
    float result;
    switch (value.constantTypeKind()) {
      case CHAR:
        result = ((CharValue) value).value();
        break;
      case BYTE:
        result = ((Const.ByteValue) value).value();
        break;
      case SHORT:
        result = ((Const.ShortValue) value).value();
        break;
      case INT:
        result = (float) ((Const.IntValue) value).value();
        break;
      case LONG:
        result = (float) ((Const.LongValue) value).value();
        break;
      case FLOAT:
        return (FloatValue) value;
      case DOUBLE:
        result = (float) ((Const.DoubleValue) value).value();
        break;
      default:
        throw typeError(position, value, TurbineConstantTypeKind.FLOAT);
    }
    return new Const.FloatValue(result);
  }

  private Const.DoubleValue asDouble(int position, Value value) {
    double result;
    switch (value.constantTypeKind()) {
      case CHAR:
        result = ((CharValue) value).value();
        break;
      case BYTE:
        result = ((Const.ByteValue) value).value();
        break;
      case SHORT:
        result = ((Const.ShortValue) value).value();
        break;
      case INT:
        result = ((Const.IntValue) value).value();
        break;
      case LONG:
        result = (double) ((Const.LongValue) value).value();
        break;
      case FLOAT:
        result = ((Const.FloatValue) value).value();
        break;
      case DOUBLE:
        return (DoubleValue) value;
      default:
        throw typeError(position, value, TurbineConstantTypeKind.DOUBLE);
    }
    return new Const.DoubleValue(result);
  }

  private @Nullable Value evalValue(Expression tree) {
    Const result = eval(tree);
    // TODO(cushon): consider distinguishing between constant field and annotation values,
    // and only allowing class literals / enum constants in the latter
    return (result instanceof Value) ? (Value) result : null;
  }

  private @Nullable Value evalConditional(Conditional t) {
    Value condition = evalValue(t.cond());
    if (condition == null) {
      return null;
    }
    return asBoolean(t.position(), condition).value()
        ? evalValue(t.iftrue())
        : evalValue(t.iffalse());
  }

  private @Nullable Value evalUnary(Unary t) {
    Value expr = evalValue(t.expr());
    if (expr == null) {
      return null;
    }
    switch (t.op()) {
      case NOT:
        return unaryNegate(t.position(), expr);
      case BITWISE_COMP:
        return bitwiseComp(t.position(), expr);
      case UNARY_PLUS:
        return unaryPlus(t.position(), expr);
      case NEG:
        return unaryMinus(t.position(), expr);
      default:
        throw new AssertionError(t.op());
    }
  }

  private @Nullable Value unaryNegate(int position, Value expr) {
    switch (expr.constantTypeKind()) {
      case BOOLEAN:
        return new Const.BooleanValue(!asBoolean(position, expr).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, expr.constantTypeKind());
    }
  }

  private @Nullable Value bitwiseComp(int position, Value expr) {
    expr = promoteUnary(position, expr);
    switch (expr.constantTypeKind()) {
      case INT:
        return new Const.IntValue(~asInt(position, expr).value());
      case LONG:
        return new Const.LongValue(~asLong(position, expr).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, expr.constantTypeKind());
    }
  }

  private @Nullable Value unaryPlus(int position, Value expr) {
    expr = promoteUnary(position, expr);
    switch (expr.constantTypeKind()) {
      case INT:
        return new Const.IntValue(+asInt(position, expr).value());
      case LONG:
        return new Const.LongValue(+asLong(position, expr).value());
      case FLOAT:
        return new Const.FloatValue(+asFloat(position, expr).value());
      case DOUBLE:
        return new Const.DoubleValue(+asDouble(position, expr).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, expr.constantTypeKind());
    }
  }

  private @Nullable Value unaryMinus(int position, Value expr) {
    expr = promoteUnary(position, expr);
    switch (expr.constantTypeKind()) {
      case INT:
        return new Const.IntValue(-asInt(position, expr).value());
      case LONG:
        return new Const.LongValue(-asLong(position, expr).value());
      case FLOAT:
        return new Const.FloatValue(-asFloat(position, expr).value());
      case DOUBLE:
        return new Const.DoubleValue(-asDouble(position, expr).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, expr.constantTypeKind());
    }
  }

  private @Nullable Value evalCast(TypeCast t) {
    Value expr = evalValue(t.expr());
    if (expr == null) {
      return null;
    }
    switch (t.ty().kind()) {
      case PRIM_TY:
        return coerce(t.expr().position(), expr, ((Tree.PrimTy) t.ty()).tykind());
      case CLASS_TY:
        {
          ClassTy classTy = (ClassTy) t.ty();
          // TODO(cushon): check package?
          if (!classTy.name().value().equals("String")) {
            // Explicit boxing cases (e.g. `(Boolean) false`) are legal, but not const exprs.
            return null;
          }
          return toString(t.expr().position(), expr);
        }
      default:
        throw new AssertionError(t.ty().kind());
    }
  }

  private @Nullable Value add(int position, Value a, Value b) {
    if (a.constantTypeKind() == TurbineConstantTypeKind.STRING
        || b.constantTypeKind() == TurbineConstantTypeKind.STRING) {
      return new Const.StringValue(toString(position, a).value() + toString(position, b).value());
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(asInt(position, a).value() + asInt(position, b).value());
      case LONG:
        return new Const.LongValue(asLong(position, a).value() + asLong(position, b).value());
      case FLOAT:
        return new Const.FloatValue(asFloat(position, a).value() + asFloat(position, b).value());
      case DOUBLE:
        return new Const.DoubleValue(asDouble(position, a).value() + asDouble(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private @Nullable Value subtract(int position, Value a, Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(asInt(position, a).value() - asInt(position, b).value());
      case LONG:
        return new Const.LongValue(asLong(position, a).value() - asLong(position, b).value());
      case FLOAT:
        return new Const.FloatValue(asFloat(position, a).value() - asFloat(position, b).value());
      case DOUBLE:
        return new Const.DoubleValue(asDouble(position, a).value() - asDouble(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private @Nullable Value mult(int position, Value a, Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(asInt(position, a).value() * asInt(position, b).value());
      case LONG:
        return new Const.LongValue(asLong(position, a).value() * asLong(position, b).value());
      case FLOAT:
        return new Const.FloatValue(asFloat(position, a).value() * asFloat(position, b).value());
      case DOUBLE:
        return new Const.DoubleValue(asDouble(position, a).value() * asDouble(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private @Nullable Value divide(int position, Value a, Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(asInt(position, a).value() / asInt(position, b).value());
      case LONG:
        return new Const.LongValue(asLong(position, a).value() / asLong(position, b).value());
      case FLOAT:
        return new Const.FloatValue(asFloat(position, a).value() / asFloat(position, b).value());
      case DOUBLE:
        return new Const.DoubleValue(asDouble(position, a).value() / asDouble(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private @Nullable Value mod(int position, Value a, Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(asInt(position, a).value() % asInt(position, b).value());
      case LONG:
        return new Const.LongValue(asLong(position, a).value() % asLong(position, b).value());
      case FLOAT:
        return new Const.FloatValue(asFloat(position, a).value() % asFloat(position, b).value());
      case DOUBLE:
        return new Const.DoubleValue(asDouble(position, a).value() % asDouble(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private static final int INT_SHIFT_MASK = 0b11111;

  private static final int LONG_SHIFT_MASK = 0b111111;

  private @Nullable Value shiftLeft(int position, Value a, Value b) {
    a = promoteUnary(position, a);
    b = promoteUnary(position, b);
    switch (a.constantTypeKind()) {
      case INT:
        return new Const.IntValue(
            asInt(position, a).value() << (asInt(position, b).value() & INT_SHIFT_MASK));
      case LONG:
        return new Const.LongValue(
            asLong(position, a).value() << (asInt(position, b).value() & LONG_SHIFT_MASK));
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, a.constantTypeKind());
    }
  }

  private @Nullable Value shiftRight(int position, Value a, Value b) {
    a = promoteUnary(position, a);
    b = promoteUnary(position, b);
    switch (a.constantTypeKind()) {
      case INT:
        return new Const.IntValue(
            asInt(position, a).value() >> (asInt(position, b).value() & INT_SHIFT_MASK));
      case LONG:
        return new Const.LongValue(
            asLong(position, a).value() >> (asInt(position, b).value() & LONG_SHIFT_MASK));
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, a.constantTypeKind());
    }
  }

  private @Nullable Value unsignedShiftRight(int position, Value a, Value b) {
    a = promoteUnary(position, a);
    b = promoteUnary(position, b);
    switch (a.constantTypeKind()) {
      case INT:
        return new Const.IntValue(
            asInt(position, a).value() >>> (asInt(position, b).value() & INT_SHIFT_MASK));
      case LONG:
        return new Const.LongValue(
            asLong(position, a).value() >>> (asInt(position, b).value() & LONG_SHIFT_MASK));
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, a.constantTypeKind());
    }
  }

  private @Nullable Value lessThan(int position, Value a, Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(asInt(position, a).value() < asInt(position, b).value());
      case LONG:
        return new Const.BooleanValue(asLong(position, a).value() < asLong(position, b).value());
      case FLOAT:
        return new Const.BooleanValue(asFloat(position, a).value() < asFloat(position, b).value());
      case DOUBLE:
        return new Const.BooleanValue(
            asDouble(position, a).value() < asDouble(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private @Nullable Value lessThanEqual(int position, Value a, Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(asInt(position, a).value() <= asInt(position, b).value());
      case LONG:
        return new Const.BooleanValue(asLong(position, a).value() <= asLong(position, b).value());
      case FLOAT:
        return new Const.BooleanValue(asFloat(position, a).value() <= asFloat(position, b).value());
      case DOUBLE:
        return new Const.BooleanValue(
            asDouble(position, a).value() <= asDouble(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private @Nullable Value greaterThan(int position, Value a, Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(asInt(position, a).value() > asInt(position, b).value());
      case LONG:
        return new Const.BooleanValue(asLong(position, a).value() > asLong(position, b).value());
      case FLOAT:
        return new Const.BooleanValue(asFloat(position, a).value() > asFloat(position, b).value());
      case DOUBLE:
        return new Const.BooleanValue(
            asDouble(position, a).value() > asDouble(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private @Nullable Value greaterThanEqual(int position, Value a, Value b) {
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(asInt(position, a).value() >= asInt(position, b).value());
      case LONG:
        return new Const.BooleanValue(asLong(position, a).value() >= asLong(position, b).value());
      case FLOAT:
        return new Const.BooleanValue(asFloat(position, a).value() >= asFloat(position, b).value());
      case DOUBLE:
        return new Const.BooleanValue(
            asDouble(position, a).value() >= asDouble(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private @Nullable Value equal(int position, Value a, Value b) {
    switch (a.constantTypeKind()) {
      case STRING:
        return new Const.BooleanValue(
            asString(position, a).value().equals(asString(position, b).value()));
      case BOOLEAN:
        return new Const.BooleanValue(
            asBoolean(position, a).value() == asBoolean(position, b).value());
      default:
        break;
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(asInt(position, a).value() == asInt(position, b).value());
      case LONG:
        return new Const.BooleanValue(asLong(position, a).value() == asLong(position, b).value());
      case FLOAT:
        return new Const.BooleanValue(asFloat(position, a).value() == asFloat(position, b).value());
      case DOUBLE:
        return new Const.BooleanValue(
            asDouble(position, a).value() == asDouble(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private @Nullable Value notEqual(int position, Value a, Value b) {
    switch (a.constantTypeKind()) {
      case STRING:
        return new Const.BooleanValue(
            !asString(position, a).value().equals(asString(position, b).value()));
      case BOOLEAN:
        return new Const.BooleanValue(
            asBoolean(position, a).value() != asBoolean(position, b).value());
      default:
        break;
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(asInt(position, a).value() != asInt(position, b).value());
      case LONG:
        return new Const.BooleanValue(asLong(position, a).value() != asLong(position, b).value());
      case FLOAT:
        return new Const.BooleanValue(asFloat(position, a).value() != asFloat(position, b).value());
      case DOUBLE:
        return new Const.BooleanValue(
            asDouble(position, a).value() != asDouble(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private Value bitwiseAnd(int position, Value a, Value b) {
    switch (a.constantTypeKind()) {
      case BOOLEAN:
        return new Const.BooleanValue(
            asBoolean(position, a).value() & asBoolean(position, b).value());
      default:
        break;
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(asInt(position, a).value() & asInt(position, b).value());
      case LONG:
        return new Const.LongValue(asLong(position, a).value() & asLong(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private Value bitwiseOr(int position, Value a, Value b) {
    switch (a.constantTypeKind()) {
      case BOOLEAN:
        return new Const.BooleanValue(
            asBoolean(position, a).value() | asBoolean(position, b).value());
      default:
        break;
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(asInt(position, a).value() | asInt(position, b).value());
      case LONG:
        return new Const.LongValue(asLong(position, a).value() | asLong(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private @Nullable Value bitwiseXor(int position, Value a, Value b) {
    switch (a.constantTypeKind()) {
      case BOOLEAN:
        return new Const.BooleanValue(
            asBoolean(position, a).value() ^ asBoolean(position, b).value());
      default:
        break;
    }
    TurbineConstantTypeKind type = promoteBinary(position, a, b);
    a = coerce(position, a, type);
    b = coerce(position, b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(asInt(position, a).value() ^ asInt(position, b).value());
      case LONG:
        return new Const.LongValue(asLong(position, a).value() ^ asLong(position, b).value());
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, type);
    }
  }

  private @Nullable Value evalBinary(Binary t) {
    Value result = null;
    boolean first = true;
    for (Expression child : t.children()) {
      Value value = evalValue(child);
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

  private @Nullable Value evalBinary(int position, TurbineOperatorKind op, Value lhs, Value rhs) {
    switch (op) {
      case PLUS:
        return add(position, lhs, rhs);
      case MINUS:
        return subtract(position, lhs, rhs);
      case MULT:
        return mult(position, lhs, rhs);
      case DIVIDE:
        return divide(position, lhs, rhs);
      case MODULO:
        return mod(position, lhs, rhs);
      case SHIFT_LEFT:
        return shiftLeft(position, lhs, rhs);
      case SHIFT_RIGHT:
        return shiftRight(position, lhs, rhs);
      case UNSIGNED_SHIFT_RIGHT:
        return unsignedShiftRight(position, lhs, rhs);
      case LESS_THAN:
        return lessThan(position, lhs, rhs);
      case GREATER_THAN:
        return greaterThan(position, lhs, rhs);
      case LESS_THAN_EQ:
        return lessThanEqual(position, lhs, rhs);
      case GREATER_THAN_EQ:
        return greaterThanEqual(position, lhs, rhs);
      case EQUAL:
        return equal(position, lhs, rhs);
      case NOT_EQUAL:
        return notEqual(position, lhs, rhs);
      case AND:
        return new Const.BooleanValue(
            asBoolean(position, lhs).value() && asBoolean(position, rhs).value());
      case OR:
        return new Const.BooleanValue(
            asBoolean(position, lhs).value() || asBoolean(position, rhs).value());
      case BITWISE_AND:
        return bitwiseAnd(position, lhs, rhs);
      case BITWISE_XOR:
        return bitwiseXor(position, lhs, rhs);
      case BITWISE_OR:
        return bitwiseOr(position, lhs, rhs);
      default:
        throw new AssertionError(op);
    }
  }

  private Value promoteUnary(int position, Value v) {
    switch (v.constantTypeKind()) {
      case CHAR:
      case SHORT:
      case BYTE:
        return asInt(position, v);
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
        return v;
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, v.constantTypeKind());
    }
  }

  private TurbineConstantTypeKind promoteBinary(int position, Value a, Value b) {
    a = promoteUnary(position, a);
    b = promoteUnary(position, b);
    switch (a.constantTypeKind()) {
      case INT:
        switch (b.constantTypeKind()) {
          case INT:
          case LONG:
          case DOUBLE:
          case FLOAT:
            return b.constantTypeKind();
          default:
            throw error(position, ErrorKind.OPERAND_TYPE, b.constantTypeKind());
        }
      case LONG:
        switch (b.constantTypeKind()) {
          case INT:
            return TurbineConstantTypeKind.LONG;
          case LONG:
          case DOUBLE:
          case FLOAT:
            return b.constantTypeKind();
          default:
            throw error(position, ErrorKind.OPERAND_TYPE, b.constantTypeKind());
        }
      case FLOAT:
        switch (b.constantTypeKind()) {
          case INT:
          case LONG:
          case FLOAT:
            return TurbineConstantTypeKind.FLOAT;
          case DOUBLE:
            return TurbineConstantTypeKind.DOUBLE;
          default:
            throw error(position, ErrorKind.OPERAND_TYPE, b.constantTypeKind());
        }
      case DOUBLE:
        switch (b.constantTypeKind()) {
          case INT:
          case LONG:
          case FLOAT:
          case DOUBLE:
            return TurbineConstantTypeKind.DOUBLE;
          default:
            throw error(position, ErrorKind.OPERAND_TYPE, b.constantTypeKind());
        }
      default:
        throw error(position, ErrorKind.OPERAND_TYPE, a.constantTypeKind());
    }
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
        throw error(name.position(), ErrorKind.CANNOT_RESOLVE, name.value());
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

  private @Nullable ArrayInitValue evalArrayInit(ArrayInit t) {
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
    switch (ty.tyKind()) {
      case PRIM_TY:
        if (!(value instanceof Value)) {
          throw error(tree.position(), ErrorKind.EXPRESSION_ERROR);
        }
        return coerce(tree.position(), (Value) value, ((Type.PrimTy) ty).primkind());
      case CLASS_TY:
      case TY_VAR:
        return value;
      case ARRAY_TY:
        {
          Type elementType = ((Type.ArrayTy) ty).elementType();
          ImmutableList<Const> elements =
              value.kind() == Const.Kind.ARRAY
                  ? ((Const.ArrayInitValue) value).elements()
                  : ImmutableList.of(value);
          ImmutableList.Builder<Const> coerced = ImmutableList.builder();
          for (Const element : elements) {
            coerced.add(cast(tree.position(), elementType, element));
          }
          return new Const.ArrayInitValue(coerced.build());
        }
      default:
        throw new AssertionError(ty.tyKind());
    }
  }

  private TurbineError error(int position, ErrorKind kind, Object... args) {
    return TurbineError.format(source, position, kind, args);
  }

  private TurbineError typeError(int position, Value value, TurbineConstantTypeKind kind) {
    return error(position, ErrorKind.TYPE_CONVERSION, value, value.constantTypeKind(), kind);
  }

  public @Nullable Value evalFieldInitializer(Expression expression, Type type) {
    try {
      Const value = eval(expression);
      if (value == null || value.kind() != Const.Kind.PRIMITIVE) {
        return null;
      }
      return (Value) cast(expression.position(), type, value);
    } catch (TurbineError error) {
      for (TurbineDiagnostic diagnostic : error.diagnostics()) {
        switch (diagnostic.kind()) {
          case CANNOT_RESOLVE:
            // assume this wasn't a constant
            return null;
          default: // fall out
        }
      }
      throw error;
    } catch (ConstCastError error) {
      return null;
    }
  }
}
