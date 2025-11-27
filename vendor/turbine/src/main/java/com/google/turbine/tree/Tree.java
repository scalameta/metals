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

package com.google.turbine.tree;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineTyKind;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** An AST node. */
public abstract class Tree {

  public abstract Kind kind();

  public abstract <I extends @Nullable Object, O extends @Nullable Object> O accept(
      Visitor<I, O> visitor, I input);

  private final int position;

  protected Tree(int position) {
    this.position = position;
  }

  public int position() {
    return position;
  }

  @Override
  public String toString() {
    return Pretty.pretty(this);
  }

  /** Tree kind. */
  public enum Kind {
    IDENT,
    WILD_TY,
    ARR_TY,
    PRIM_TY,
    VOID_TY,
    CLASS_TY,
    LITERAL,
    PAREN,
    TYPE_CAST,
    UNARY,
    BINARY,
    CONST_VAR_NAME,
    CLASS_LITERAL,
    ASSIGN,
    CONDITIONAL,
    ARRAY_INIT,
    COMP_UNIT,
    IMPORT_DECL,
    VAR_DECL,
    METH_DECL,
    ANNO,
    ANNO_EXPR,
    TY_DECL,
    TY_PARAM,
    PKG_DECL,
    MOD_DECL,
    MOD_REQUIRES,
    MOD_EXPORTS,
    MOD_OPENS,
    MOD_USES,
    MOD_PROVIDES
  }

  /** An identifier. */
  @Immutable
  public static class Ident extends Tree {

    private final String value;

    public Ident(int position, String value) {
      super(position);
      this.value = value;
    }

    @Override
    public Kind kind() {
      return Kind.IDENT;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitIdent(this, input);
    }

    public String value() {
      return value;
    }

    @Override
    public String toString() {
      return value;
    }
  }

  /** A type use. */
  public abstract static class Type extends Tree {
    private final ImmutableList<Anno> annos;

    public Type(int position, ImmutableList<Anno> annos) {
      super(position);
      this.annos = annos;
    }

    public ImmutableList<Anno> annos() {
      return annos;
    }
  }

  /** An expression. */
  public abstract static class Expression extends Tree {
    public Expression(int position) {
      super(position);
    }
  }

  /** A wildcard type, possibly with an upper or lower bound. */
  public static class WildTy extends Type {
    private final Optional<Type> upper;
    private final Optional<Type> lower;

    public WildTy(
        int position, ImmutableList<Anno> annos, Optional<Type> upper, Optional<Type> lower) {
      super(position, annos);
      this.upper = upper;
      this.lower = lower;
    }

    @Override
    public Kind kind() {
      return Kind.WILD_TY;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitWildTy(this, input);
    }

    /**
     * An optional upper (extends) bound.
     *
     * <p>At most one of {@link #upper} and {@link #lower} will be set.
     */
    public Optional<Type> upper() {
      return upper;
    }

    /**
     * An optional lower (super) bound.
     *
     * <p>At most one of {@link #upper} and {@link #lower} will be set.
     */
    public Optional<Type> lower() {
      return lower;
    }
  }

  /** An array type. */
  public static class ArrTy extends Type {
    private final Type elem;

    public ArrTy(int position, ImmutableList<Anno> annos, Type elem) {
      super(position, annos);
      this.elem = elem;
    }

    @Override
    public Kind kind() {
      return Kind.ARR_TY;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitArrTy(this, input);
    }

    /**
     * The element type of the array.
     *
     * <p>Multi-dimensional arrays are represented as nested {@link ArrTy}s.
     */
    public Type elem() {
      return elem;
    }
  }

  /** A primitive type. */
  public static class PrimTy extends Type {
    private final TurbineConstantTypeKind tykind;

    public PrimTy(int position, ImmutableList<Anno> annos, TurbineConstantTypeKind tykind) {
      super(position, annos);
      this.tykind = tykind;
    }

    @Override
    public Kind kind() {
      return Kind.PRIM_TY;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitPrimTy(this, input);
    }

    /** The primtiive type. */
    public TurbineConstantTypeKind tykind() {
      return tykind;
    }
  }

  /** The void type, used only for void-returning methods. */
  public static class VoidTy extends Type {

    @Override
    public Kind kind() {
      return Kind.VOID_TY;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitVoidTy(this, input);
    }

    public VoidTy(int position) {
      super(position, ImmutableList.of());
    }
  }

  /** A class, enum, interface, or annotation {@link Type}. */
  public static class ClassTy extends Type {
    private final Optional<ClassTy> base;
    private final Ident name;
    private final ImmutableList<Type> tyargs;

    public ClassTy(
        int position,
        Optional<ClassTy> base,
        Ident name,
        ImmutableList<Type> tyargs,
        ImmutableList<Anno> annos) {
      super(position, annos);
      this.base = base;
      this.name = name;
      this.tyargs = tyargs;
    }

    @Override
    public Kind kind() {
      return Kind.CLASS_TY;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitClassTy(this, input);
    }

    /**
     * The base type, for qualified type uses.
     *
     * <p>For example, {@code Map.Entry}.
     */
    public Optional<ClassTy> base() {
      return base;
    }

    /** The simple name of the type. */
    public Ident name() {
      return name;
    }

    /** A possibly empty list of type arguments. */
    public ImmutableList<Type> tyargs() {
      return tyargs;
    }
  }

  /** A JLS 3.10 literal expression. */
  public static class Literal extends Expression {
    private final TurbineConstantTypeKind tykind;
    private final Const value;

    public Literal(int position, TurbineConstantTypeKind tykind, Const value) {
      super(position);
      this.tykind = tykind;
      this.value = value;
    }

    @Override
    public Kind kind() {
      return Kind.LITERAL;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitLiteral(this, input);
    }

    public TurbineConstantTypeKind tykind() {
      return tykind;
    }

    public Const value() {
      return value;
    }
  }

  /** A JLS 15.8.5 parenthesized expression. */
  public static class Paren extends Expression {
    private final Expression expr;

    public Paren(int position, Expression expr) {
      super(position);
      this.expr = expr;
    }

    @Override
    public Kind kind() {
      return Kind.PAREN;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitParen(this, input);
    }

    public Expression expr() {
      return expr;
    }
  }

  /** A JLS 15.16 cast expression. */
  public static class TypeCast extends Expression {
    private final Type ty;
    private final Expression expr;

    public TypeCast(int position, Type ty, Expression expr) {
      super(position);
      this.ty = ty;
      this.expr = expr;
    }

    @Override
    public Kind kind() {
      return Kind.TYPE_CAST;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitTypeCast(this, input);
    }

    public Type ty() {
      return ty;
    }

    public Expression expr() {
      return expr;
    }
  }

  /** A JLS 15.14 - 14.15 unary expression. */
  public static class Unary extends Expression {
    private final Expression expr;
    private final TurbineOperatorKind op;

    public Unary(int position, Expression expr, TurbineOperatorKind op) {
      super(position);
      this.expr = expr;
      this.op = op;
    }

    @Override
    public Kind kind() {
      return Kind.UNARY;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitUnary(this, input);
    }

    public Expression expr() {
      return expr;
    }

    public TurbineOperatorKind op() {
      return op;
    }
  }

  /** A JLS 15.17 - 14.24 binary expression. */
  public static class Binary extends Expression {
    private final Expression lhs;
    private final Expression rhs;
    private final TurbineOperatorKind op;

    public Binary(int position, Expression lhs, Expression rhs, TurbineOperatorKind op) {
      super(position);
      this.lhs = lhs;
      this.rhs = rhs;
      this.op = op;
    }

    @Override
    public Kind kind() {
      return Kind.BINARY;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitBinary(this, input);
    }

    public Iterable<Expression> children() {
      ImmutableList.Builder<Expression> children = ImmutableList.builder();
      Deque<Expression> stack = new ArrayDeque<>();
      stack.addFirst(rhs);
      stack.addFirst(lhs);
      while (!stack.isEmpty()) {
        Expression curr = stack.removeFirst();
        if (curr.kind().equals(Kind.BINARY)) {
          Binary b = ((Binary) curr);
          if (b.op().equals(op())) {
            stack.addFirst(b.rhs);
            stack.addFirst(b.lhs);
            continue;
          }
        }
        children.add(curr);
      }
      return children.build();
    }

    public TurbineOperatorKind op() {
      return op;
    }
  }

  /** A JLS 6.5.6.1 simple name that refers to a JSL 4.12.4 constant variable. */
  public static class ConstVarName extends Expression {
    private final ImmutableList<Ident> name;

    public ConstVarName(int position, ImmutableList<Ident> name) {
      super(position);
      this.name = name;
    }

    @Override
    public Kind kind() {
      return Kind.CONST_VAR_NAME;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitConstVarName(this, input);
    }

    public ImmutableList<Ident> name() {
      return name;
    }
  }

  /** A JLS 15.8.2 class literal. */
  public static class ClassLiteral extends Expression {

    private final Type type;

    public ClassLiteral(int position, Type type) {
      super(position);
      this.type = type;
    }

    @Override
    public Kind kind() {
      return Kind.CLASS_LITERAL;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitClassLiteral(this, input);
    }

    public Type type() {
      return type;
    }
  }

  /** A JLS 15.26 assignment expression. */
  public static class Assign extends Expression {
    private final Ident name;
    private final Expression expr;

    public Assign(int position, Ident name, Expression expr) {
      super(position);
      this.name = requireNonNull(name);
      this.expr = requireNonNull(expr);
    }

    @Override
    public Kind kind() {
      return Kind.ASSIGN;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitAssign(this, input);
    }

    public Ident name() {
      return name;
    }

    public Expression expr() {
      return expr;
    }
  }

  /** A JLS 15.25 conditional expression. */
  public static class Conditional extends Expression {
    private final Expression cond;
    private final Expression iftrue;
    private final Expression iffalse;

    public Conditional(int position, Expression cond, Expression iftrue, Expression iffalse) {
      super(position);
      this.cond = cond;
      this.iftrue = iftrue;
      this.iffalse = iffalse;
    }

    @Override
    public Kind kind() {
      return Kind.CONDITIONAL;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitConditional(this, input);
    }

    public Expression cond() {
      return cond;
    }

    public Expression iftrue() {
      return iftrue;
    }

    public Expression iffalse() {
      return iffalse;
    }
  }

  /** JLS 10.6 array initializer. */
  public static class ArrayInit extends Expression {
    private final ImmutableList<Expression> exprs;

    public ArrayInit(int position, ImmutableList<Expression> exprs) {
      super(position);
      this.exprs = exprs;
    }

    @Override
    public Kind kind() {
      return Kind.ARRAY_INIT;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitArrayInit(this, input);
    }

    public ImmutableList<Expression> exprs() {
      return exprs;
    }
  }

  /** A JLS 7.3 compilation unit. */
  public static class CompUnit extends Tree {
    private final Optional<PkgDecl> pkg;
    private final Optional<ModDecl> mod;
    private final ImmutableList<ImportDecl> imports;
    private final ImmutableList<TyDecl> decls;
    private final SourceFile source;

    public CompUnit(
        int position,
        Optional<PkgDecl> pkg,
        Optional<ModDecl> mod,
        ImmutableList<ImportDecl> imports,
        ImmutableList<TyDecl> decls,
        SourceFile source) {
      super(position);
      this.pkg = pkg;
      this.mod = mod;
      this.imports = imports;
      this.decls = decls;
      this.source = source;
    }

    @Override
    public Kind kind() {
      return Kind.COMP_UNIT;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitCompUnit(this, input);
    }

    public Optional<PkgDecl> pkg() {
      return pkg;
    }

    public Optional<ModDecl> mod() {
      return mod;
    }

    public ImmutableList<ImportDecl> imports() {
      return imports;
    }

    public ImmutableList<TyDecl> decls() {
      return decls;
    }

    public SourceFile source() {
      return source;
    }
  }

  /** A JLS 7.5 import declaration. */
  public static class ImportDecl extends Tree {
    private final ImmutableList<Ident> type;
    private final boolean stat;
    private final boolean wild;

    public ImportDecl(int position, ImmutableList<Ident> type, boolean stat, boolean wild) {
      super(position);
      this.type = type;
      this.stat = stat;
      this.wild = wild;
    }

    @Override
    public Kind kind() {
      return Kind.IMPORT_DECL;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitImportDecl(this, input);
    }

    public ImmutableList<Ident> type() {
      return type;
    }

    /** Returns true for static member imports. */
    public boolean stat() {
      return stat;
    }

    /** Returns true for wildcard imports. */
    public boolean wild() {
      return wild;
    }
  }

  /** A JLS 8.3 field declaration, JLS 8.4.1 formal method parameter, or JLS 14.4 variable. */
  public static class VarDecl extends Tree {
    private final ImmutableSet<TurbineModifier> mods;
    private final ImmutableList<Anno> annos;
    private final Tree ty;
    private final Ident name;
    private final Optional<Expression> init;
    private final @Nullable String javadoc;

    public VarDecl(
        int position,
        Set<TurbineModifier> mods,
        ImmutableList<Anno> annos,
        Tree ty,
        Ident name,
        Optional<Expression> init,
        @Nullable String javadoc) {
      super(position);
      this.mods = ImmutableSet.copyOf(mods);
      this.annos = annos;
      this.ty = ty;
      this.name = name;
      this.init = init;
      this.javadoc = javadoc;
    }

    @Override
    public Kind kind() {
      return Kind.VAR_DECL;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitVarDecl(this, input);
    }

    public ImmutableSet<TurbineModifier> mods() {
      return mods;
    }

    public ImmutableList<Anno> annos() {
      return annos;
    }

    public Tree ty() {
      return ty;
    }

    public Ident name() {
      return name;
    }

    public Optional<Expression> init() {
      return init;
    }

    /**
     * A javadoc comment, excluding the opening and closing delimiters but including all interior
     * characters and whitespace.
     */
    public @Nullable String javadoc() {
      return javadoc;
    }
  }

  /** A JLS 8.4 method declaration. */
  public static class MethDecl extends Tree {
    private final ImmutableSet<TurbineModifier> mods;
    private final ImmutableList<Anno> annos;
    private final ImmutableList<TyParam> typarams;
    private final Optional<Tree> ret;
    private final Ident name;
    private final ImmutableList<VarDecl> params;
    private final ImmutableList<ClassTy> exntys;
    private final Optional<Tree> defaultValue;
    private final String javadoc;

    public MethDecl(
        int position,
        Set<TurbineModifier> mods,
        ImmutableList<Anno> annos,
        ImmutableList<TyParam> typarams,
        Optional<Tree> ret,
        Ident name,
        ImmutableList<VarDecl> params,
        ImmutableList<ClassTy> exntys,
        Optional<Tree> defaultValue,
        String javadoc) {
      super(position);
      this.mods = ImmutableSet.copyOf(mods);
      this.annos = annos;
      this.typarams = typarams;
      this.ret = ret;
      this.name = name;
      this.params = params;
      this.exntys = exntys;
      this.defaultValue = defaultValue;
      this.javadoc = javadoc;
    }

    @Override
    public Kind kind() {
      return Kind.METH_DECL;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitMethDecl(this, input);
    }

    public ImmutableSet<TurbineModifier> mods() {
      return mods;
    }

    public ImmutableList<Anno> annos() {
      return annos;
    }

    public ImmutableList<TyParam> typarams() {
      return typarams;
    }

    public Optional<Tree> ret() {
      return ret;
    }

    public Ident name() {
      return name;
    }

    public ImmutableList<VarDecl> params() {
      return params;
    }

    public ImmutableList<ClassTy> exntys() {
      return exntys;
    }

    public Optional<Tree> defaultValue() {
      return defaultValue;
    }

    /**
     * A javadoc comment, excluding the opening and closing delimiters but including all interior
     * characters and whitespace.
     */
    public String javadoc() {
      return javadoc;
    }
  }

  /** A JLS 9.7 annotation. */
  public static class Anno extends Tree {
    private final ImmutableList<Ident> name;
    private final ImmutableList<Expression> args;

    public Anno(int position, ImmutableList<Ident> name, ImmutableList<Expression> args) {
      super(position);
      this.name = name;
      this.args = args;
    }

    @Override
    public Kind kind() {
      return Kind.ANNO;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitAnno(this, input);
    }

    public ImmutableList<Ident> name() {
      return name;
    }

    public ImmutableList<Expression> args() {
      return args;
    }
  }

  /**
   * An annotation in an expression context, e.g. an annotation literal nested inside another
   * annotation.
   */
  public static class AnnoExpr extends Expression {

    private final Anno value;

    public AnnoExpr(int position, Anno value) {
      super(position);
      this.value = value;
    }

    /** The annotation. */
    public Anno value() {
      return value;
    }

    @Override
    public Kind kind() {
      return Kind.ANNO_EXPR;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitAnno(value, input);
    }
  }

  /** A JLS 7.6 or 8.5 type declaration. */
  public static class TyDecl extends Tree {
    private final ImmutableSet<TurbineModifier> mods;
    private final ImmutableList<Anno> annos;
    private final Ident name;
    private final ImmutableList<TyParam> typarams;
    private final Optional<ClassTy> xtnds;
    private final ImmutableList<ClassTy> impls;
    private final ImmutableList<ClassTy> permits;
    private final ImmutableList<Tree> members;
    private final ImmutableList<VarDecl> components;
    private final TurbineTyKind tykind;
    private final @Nullable String javadoc;

    public TyDecl(
        int position,
        Set<TurbineModifier> mods,
        ImmutableList<Anno> annos,
        Ident name,
        ImmutableList<TyParam> typarams,
        Optional<ClassTy> xtnds,
        ImmutableList<ClassTy> impls,
        ImmutableList<ClassTy> permits,
        ImmutableList<Tree> members,
        ImmutableList<VarDecl> components,
        TurbineTyKind tykind,
        @Nullable String javadoc) {
      super(position);
      this.mods = ImmutableSet.copyOf(mods);
      this.annos = annos;
      this.name = name;
      this.typarams = typarams;
      this.xtnds = xtnds;
      this.impls = impls;
      this.permits = permits;
      this.members = members;
      this.components = components;
      this.tykind = tykind;
      this.javadoc = javadoc;
    }

    @Override
    public Kind kind() {
      return Kind.TY_DECL;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitTyDecl(this, input);
    }

    public ImmutableSet<TurbineModifier> mods() {
      return mods;
    }

    public ImmutableList<Anno> annos() {
      return annos;
    }

    public Ident name() {
      return name;
    }

    public ImmutableList<TyParam> typarams() {
      return typarams;
    }

    public Optional<ClassTy> xtnds() {
      return xtnds;
    }

    public ImmutableList<ClassTy> impls() {
      return impls;
    }

    public ImmutableList<ClassTy> permits() {
      return permits;
    }

    public ImmutableList<Tree> members() {
      return members;
    }

    public ImmutableList<VarDecl> components() {
      return components;
    }

    public TurbineTyKind tykind() {
      return tykind;
    }

    /**
     * A javadoc comment, excluding the opening and closing delimiters but including all interior
     * characters and whitespace.
     */
    public @Nullable String javadoc() {
      return javadoc;
    }
  }

  /** A JLS 4.4. type variable declaration. */
  public static class TyParam extends Tree {
    private final Ident name;
    private final ImmutableList<Tree> bounds;
    private final ImmutableList<Anno> annos;

    public TyParam(
        int position, Ident name, ImmutableList<Tree> bounds, ImmutableList<Anno> annos) {
      super(position);
      this.name = name;
      this.bounds = bounds;
      this.annos = annos;
    }

    @Override
    public Kind kind() {
      return Kind.TY_PARAM;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitTyParam(this, input);
    }

    public Ident name() {
      return name;
    }

    public ImmutableList<Tree> bounds() {
      return bounds;
    }

    public ImmutableList<Anno> annos() {
      return annos;
    }
  }

  /** A JLS 7.4 package declaration. */
  public static class PkgDecl extends Tree {
    private final ImmutableList<Ident> name;
    private final ImmutableList<Anno> annos;

    public PkgDecl(int position, ImmutableList<Ident> name, ImmutableList<Anno> annos) {
      super(position);
      this.name = name;
      this.annos = annos;
    }

    @Override
    public Kind kind() {
      return Kind.PKG_DECL;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitPkgDecl(this, input);
    }

    public ImmutableList<Ident> name() {
      return name;
    }

    public ImmutableList<Anno> annos() {
      return annos;
    }
  }

  /** A JLS 7.7 module declaration. */
  public static class ModDecl extends Tree {

    private final ImmutableList<Anno> annos;
    private final boolean open;
    private final String moduleName;
    private final ImmutableList<ModDirective> directives;

    public ModDecl(
        int position,
        ImmutableList<Anno> annos,
        boolean open,
        String moduleName,
        ImmutableList<ModDirective> directives) {
      super(position);
      this.annos = annos;
      this.open = open;
      this.moduleName = moduleName;
      this.directives = directives;
    }

    public boolean open() {
      return open;
    }

    public ImmutableList<Anno> annos() {
      return annos;
    }

    public String moduleName() {
      return moduleName;
    }

    public ImmutableList<ModDirective> directives() {
      return directives;
    }

    @Override
    public Kind kind() {
      return Kind.MOD_DECL;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitModDecl(this, input);
    }
  }

  /** A kind of module directive. */
  public abstract static class ModDirective extends Tree {

    /** A module directive kind. */
    public enum DirectiveKind {
      REQUIRES,
      EXPORTS,
      OPENS,
      USES,
      PROVIDES
    }

    public abstract DirectiveKind directiveKind();

    protected ModDirective(int position) {
      super(position);
    }
  }

  /** A JLS 7.7.1 module requires directive. */
  public static class ModRequires extends ModDirective {

    private final ImmutableSet<TurbineModifier> mods;
    private final String moduleName;

    @Override
    public Kind kind() {
      return Kind.MOD_REQUIRES;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitModRequires(this, input);
    }

    public ModRequires(int position, ImmutableSet<TurbineModifier> mods, String moduleName) {
      super(position);
      this.mods = mods;
      this.moduleName = moduleName;
    }

    public ImmutableSet<TurbineModifier> mods() {
      return mods;
    }

    public String moduleName() {
      return moduleName;
    }

    @Override
    public DirectiveKind directiveKind() {
      return DirectiveKind.REQUIRES;
    }
  }

  /** A JLS 7.7.2 module exports directive. */
  public static class ModExports extends ModDirective {

    private final String packageName;
    private final ImmutableList<String> moduleNames;

    @Override
    public Kind kind() {
      return Kind.MOD_EXPORTS;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitModExports(this, input);
    }

    public ModExports(int position, String packageName, ImmutableList<String> moduleNames) {
      super(position);
      this.packageName = packageName;
      this.moduleNames = moduleNames;
    }

    public String packageName() {
      return packageName;
    }

    public ImmutableList<String> moduleNames() {
      return moduleNames;
    }

    @Override
    public DirectiveKind directiveKind() {
      return DirectiveKind.EXPORTS;
    }
  }

  /** A JLS 7.7.2 module opens directive. */
  public static class ModOpens extends ModDirective {

    private final String packageName;
    private final ImmutableList<String> moduleNames;

    public ModOpens(int position, String packageName, ImmutableList<String> moduleNames) {
      super(position);
      this.packageName = packageName;
      this.moduleNames = moduleNames;
    }

    public String packageName() {
      return packageName;
    }

    public ImmutableList<String> moduleNames() {
      return moduleNames;
    }

    @Override
    public Kind kind() {
      return Kind.MOD_OPENS;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitModOpens(this, input);
    }

    @Override
    public DirectiveKind directiveKind() {
      return DirectiveKind.OPENS;
    }
  }

  /** A JLS 7.7.3 module uses directive. */
  public static class ModUses extends ModDirective {

    private final ImmutableList<Ident> typeName;

    public ModUses(int position, ImmutableList<Ident> typeName) {
      super(position);
      this.typeName = typeName;
    }

    public ImmutableList<Ident> typeName() {
      return typeName;
    }

    @Override
    public Kind kind() {
      return Kind.MOD_USES;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitModUses(this, input);
    }

    @Override
    public DirectiveKind directiveKind() {
      return DirectiveKind.USES;
    }
  }

  /** A JLS 7.7.4 module uses directive. */
  public static class ModProvides extends ModDirective {

    private final ImmutableList<Ident> typeName;
    private final ImmutableList<ImmutableList<Ident>> implNames;

    public ModProvides(
        int position,
        ImmutableList<Ident> typeName,
        ImmutableList<ImmutableList<Ident>> implNames) {
      super(position);
      this.typeName = typeName;
      this.implNames = implNames;
    }

    public ImmutableList<Ident> typeName() {
      return typeName;
    }

    public ImmutableList<ImmutableList<Ident>> implNames() {
      return implNames;
    }

    @Override
    public Kind kind() {
      return Kind.MOD_PROVIDES;
    }

    @Override
    public <I extends @Nullable Object, O extends @Nullable Object> O accept(
        Visitor<I, O> visitor, I input) {
      return visitor.visitModProvides(this, input);
    }

    @Override
    public DirectiveKind directiveKind() {
      return DirectiveKind.PROVIDES;
    }
  }

  /** A visitor for {@link Tree}s. */
  public interface Visitor<I extends @Nullable Object, O extends @Nullable Object> {
    O visitIdent(Ident ident, I input);

    O visitWildTy(WildTy visitor, I input);

    O visitArrTy(ArrTy arrTy, I input);

    O visitPrimTy(PrimTy primTy, I input);

    O visitVoidTy(VoidTy primTy, I input);

    O visitClassTy(ClassTy visitor, I input);

    O visitLiteral(Literal literal, I input);

    O visitParen(Paren unary, I input);

    O visitTypeCast(TypeCast typeCast, I input);

    O visitUnary(Unary unary, I input);

    O visitBinary(Binary binary, I input);

    O visitConstVarName(ConstVarName constVarName, I input);

    O visitClassLiteral(ClassLiteral classLiteral, I input);

    O visitAssign(Assign assign, I input);

    O visitConditional(Conditional conditional, I input);

    O visitArrayInit(ArrayInit arrayInit, I input);

    O visitCompUnit(CompUnit compUnit, I input);

    O visitImportDecl(ImportDecl importDecl, I input);

    O visitVarDecl(VarDecl varDecl, I input);

    O visitMethDecl(MethDecl methDecl, I input);

    O visitAnno(Anno anno, I input);

    O visitTyDecl(TyDecl tyDecl, I input);

    O visitTyParam(TyParam tyParam, I input);

    O visitPkgDecl(PkgDecl pkgDecl, I input);

    O visitModDecl(ModDecl modDecl, I input);

    O visitModRequires(ModRequires modRequires, I input);

    O visitModExports(ModExports modExports, I input);

    O visitModOpens(ModOpens modOpens, I input);

    O visitModUses(ModUses modUses, I input);

    O visitModProvides(ModProvides modProvides, I input);
  }
}
