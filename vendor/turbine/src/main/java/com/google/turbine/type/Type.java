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

package com.google.turbine.type;

import static com.google.common.collect.Iterables.getLast;
import static java.lang.Math.max;
import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.tree.Tree;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** JLS 4 types. */
public interface Type {

  /** A type kind. */
  enum TyKind {
    /** A primitive type. */
    PRIM_TY,
    /**
     * The void type.
     *
     * <p>It isn't actually a type in the spec, but it's included here for convenience.
     */
    VOID_TY,
    /** A class type. */
    CLASS_TY,
    /** An array type. */
    ARRAY_TY,
    /** A type variable type. */
    TY_VAR,
    /** A wildcard type. */
    WILD_TY,
    /** An intersection type. */
    INTERSECTION_TY,
    /** A method type. */
    METHOD_TY,

    ERROR_TY,
    NONE_TY,
  }

  /** The type kind. */
  TyKind tyKind();

  /** The void type. */
  Type VOID =
      new Type() {
        @Override
        public TyKind tyKind() {
          return TyKind.VOID_TY;
        }

        @Override
        public final String toString() {
          return "void";
        }
      };

  /** The void type. */
  Type NONE =
      new Type() {
        @Override
        public TyKind tyKind() {
          return TyKind.NONE_TY;
        }

        @Override
        public final String toString() {
          return "none";
        }
      };

  /**
   * A class type.
   *
   * <p>Qualified types (e.g. {@code OuterClass<Foo>.InnerClass<Bar>}) are repesented as a list
   * {@link SimpleClassTy}s (enclosing types first), each of which contains a {@link ClassSymbol}
   * and an optional list of type arguments.
   */
  @AutoValue
  abstract class ClassTy implements Type {

    /**
     * The {@link ClassTy} for {@code java.lang.Object}. There's nothing special about this
     * instance, it's just to avoid some boilerplate.
     */
    public static final ClassTy OBJECT = asNonParametricClassTy(ClassSymbol.OBJECT);

    /** The {@link ClassTy} for {@code java.lang.String}. */
    public static final ClassTy STRING = asNonParametricClassTy(ClassSymbol.STRING);

    public static final ClassTy CLONEABLE = asNonParametricClassTy(ClassSymbol.CLONEABLE);
    public static final ClassTy SERIALIZABLE = asNonParametricClassTy(ClassSymbol.SERIALIZABLE);

    /** Returns a {@link ClassTy} with no type arguments for the given {@link ClassSymbol}. */
    public static ClassTy asNonParametricClassTy(ClassSymbol i) {
      return ClassTy.create(
          Arrays.asList(SimpleClassTy.create(i, ImmutableList.of(), ImmutableList.of())));
    }

    public abstract ImmutableList<SimpleClassTy> classes();

    public static ClassTy create(Iterable<SimpleClassTy> classes) {
      return new AutoValue_Type_ClassTy(ImmutableList.copyOf(classes));
    }

    @Override
    public TyKind tyKind() {
      return TyKind.CLASS_TY;
    }

    /** The class symbol. */
    public ClassSymbol sym() {
      return getLast(classes()).sym();
    }

    @Override
    public final String toString() {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (SimpleClassTy c : classes()) {
        String binaryName = c.sym().binaryName();
        if (!first) {
          for (AnnoInfo anno : c.annos()) {
            sb.append(anno);
            sb.append(' ');
          }
          sb.append('.');
          sb.append(binaryName, binaryName.lastIndexOf('$') + 1, binaryName.length());
        } else {
          int idx = max(binaryName.lastIndexOf('/'), binaryName.lastIndexOf('$')) + 1;
          String name = binaryName.replace('/', '.').replace('$', '.');
          sb.append(name, 0, idx);
          for (AnnoInfo anno : c.annos()) {
            sb.append(anno);
            sb.append(' ');
          }
          sb.append(name, idx, name.length());
        }
        if (!c.targs().isEmpty()) {
          sb.append('<');
          Joiner.on(',').appendTo(sb, c.targs());
          sb.append('>');
        }
        first = false;
      }
      return sb.toString();
    }

    /** One element of a qualified {@link ClassTy}. */
    @AutoValue
    public abstract static class SimpleClassTy {

      public static SimpleClassTy create(
          ClassSymbol sym, ImmutableList<Type> targs, ImmutableList<AnnoInfo> annos) {
        return new AutoValue_Type_ClassTy_SimpleClassTy(sym, targs, annos);
      }

      /** The class symbol of the element. */
      public abstract ClassSymbol sym();

      /** The type arguments. */
      public abstract ImmutableList<Type> targs();

      /** The type annotations. */
      public abstract ImmutableList<AnnoInfo> annos();

      @Memoized
      @Override
      public abstract int hashCode();
    }

    @Memoized
    @Override
    public int hashCode() {
      return Iterables.getLast(classes()).hashCode();
    }

    @Override
    public final boolean equals(@Nullable Object obj) {
      if (!(obj instanceof ClassTy)) {
        return false;
      }
      ClassTy that = (ClassTy) obj;
      int i = this.classes().size() - 1;
      int j = that.classes().size() - 1;
      for (; i >= 0 && j >= 0; i--, j--) {
        if (!this.classes().get(i).equals(that.classes().get(j))) {
          return false;
        }
      }
      // don't rely on canonical form for simple class names
      if (hasTargs(this.classes(), i) || hasTargs(that.classes(), j)) {
        return false;
      }
      return true;
    }

    private static boolean hasTargs(ImmutableList<SimpleClassTy> classes, int idx) {
      for (; idx >= 0; idx--) {
        SimpleClassTy simple = classes.get(idx);
        if (!simple.targs().isEmpty() || !simple.annos().isEmpty()) {
          return true;
        }
      }
      return false;
    }
  }

  /** An array type. */
  @AutoValue
  abstract class ArrayTy implements Type {

    public static ArrayTy create(Type elem, ImmutableList<AnnoInfo> annos) {
      return new AutoValue_Type_ArrayTy(elem, annos);
    }

    /** The element type of the array. */
    public abstract Type elementType();

    @Override
    public TyKind tyKind() {
      return TyKind.ARRAY_TY;
    }

    /** The type annotations. */
    public abstract ImmutableList<AnnoInfo> annos();

    @Override
    public final String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(elementType());
      if (!annos().isEmpty()) {
        sb.append(' ');
        for (AnnoInfo anno : annos()) {
          sb.append(anno);
          sb.append(' ');
        }
      }
      sb.append("[]");
      return sb.toString();
    }

    @Memoized
    @Override
    public abstract int hashCode();
  }

  /** A type variable. */
  @AutoValue
  abstract class TyVar implements Type {

    public static TyVar create(TyVarSymbol sym, ImmutableList<AnnoInfo> annos) {
      return new AutoValue_Type_TyVar(sym, annos);
    }

    /** The type variable's symbol. */
    public abstract TyVarSymbol sym();

    @Override
    public TyKind tyKind() {
      return TyKind.TY_VAR;
    }

    @Override
    public final String toString() {
      StringBuilder sb = new StringBuilder();
      for (AnnoInfo anno : annos()) {
        sb.append(anno);
        sb.append(' ');
      }
      sb.append(sym().name());
      return sb.toString();
    }

    /** The type annotations. */
    public abstract ImmutableList<AnnoInfo> annos();

    @Memoized
    @Override
    public abstract int hashCode();
  }

  /** A primitive type. */
  // TODO: cushon - consider renaming this, since it models things like String and null that can
  // appear as constants and not primitives.
  @AutoValue
  abstract class PrimTy implements Type {

    public static PrimTy create(TurbineConstantTypeKind tykind, ImmutableList<AnnoInfo> annos) {
      return new AutoValue_Type_PrimTy(tykind, annos);
    }

    /** The primtive type kind. */
    public abstract TurbineConstantTypeKind primkind();

    @Override
    public TyKind tyKind() {
      return TyKind.PRIM_TY;
    }

    /** The type annotations. */
    public abstract ImmutableList<AnnoInfo> annos();

    @Override
    public final String toString() {
      StringBuilder sb = new StringBuilder();
      for (AnnoInfo anno : annos()) {
        sb.append(anno);
        sb.append(' ');
      }
      sb.append(primkind());
      return sb.toString();
    }

    @Memoized
    @Override
    public abstract int hashCode();
  }

  /** A wildcard type, valid only inside (possibly nested) type arguments. */
  abstract class WildTy implements Type {

    public enum BoundKind {
      NONE,
      UPPER,
      LOWER
    }

    public abstract BoundKind boundKind();

    public abstract Type bound();

    /** The type annotations. */
    public abstract ImmutableList<AnnoInfo> annotations();

    @Override
    public TyKind tyKind() {
      return TyKind.WILD_TY;
    }
  }

  /** An upper-bounded wildcard type. */
  @AutoValue
  abstract class WildUpperBoundedTy extends WildTy {

    public static WildUpperBoundedTy create(Type bound, ImmutableList<AnnoInfo> annotations) {
      return new AutoValue_Type_WildUpperBoundedTy(annotations, bound);
    }

    /** The upper bound. */
    @Override
    public abstract Type bound();

    @Override
    public BoundKind boundKind() {
      return BoundKind.UPPER;
    }

    @Override
    public final String toString() {
      StringBuilder sb = new StringBuilder();
      for (AnnoInfo anno : annotations()) {
        sb.append(anno);
        sb.append(' ');
      }
      sb.append("? extends ");
      sb.append(bound());
      return sb.toString();
    }

    @Memoized
    @Override
    public abstract int hashCode();
  }

  /** An lower-bounded wildcard type. */
  @AutoValue
  abstract class WildLowerBoundedTy extends WildTy {

    public static WildLowerBoundedTy create(Type bound, ImmutableList<AnnoInfo> annotations) {
      return new AutoValue_Type_WildLowerBoundedTy(annotations, bound);
    }

    /** The lower bound. */
    @Override
    public abstract Type bound();

    @Override
    public BoundKind boundKind() {
      return BoundKind.LOWER;
    }

    @Override
    public final String toString() {
      StringBuilder sb = new StringBuilder();
      for (AnnoInfo anno : annotations()) {
        sb.append(anno);
        sb.append(' ');
      }
      sb.append("? super ");
      sb.append(bound());
      return sb.toString();
    }

    @Memoized
    @Override
    public abstract int hashCode();
  }

  /** An unbounded wildcard type. */
  @AutoValue
  abstract class WildUnboundedTy extends WildTy {

    public static WildUnboundedTy create(ImmutableList<AnnoInfo> annotations) {
      return new AutoValue_Type_WildUnboundedTy(annotations);
    }

    @Override
    public BoundKind boundKind() {
      return BoundKind.NONE;
    }

    @Override
    public Type bound() {
      throw new IllegalStateException();
    }

    @Override
    public final String toString() {
      StringBuilder sb = new StringBuilder();
      for (AnnoInfo anno : annotations()) {
        sb.append(anno);
        sb.append(' ');
      }
      sb.append('?');
      return sb.toString();
    }

    @Memoized
    @Override
    public abstract int hashCode();
  }

  /** An intersection type. */
  @AutoValue
  abstract class IntersectionTy implements Type {

    public abstract ImmutableList<Type> bounds();

    public static IntersectionTy create(ImmutableList<Type> bounds) {
      return new AutoValue_Type_IntersectionTy(bounds);
    }

    @Override
    public TyKind tyKind() {
      return TyKind.INTERSECTION_TY;
    }

    @Memoized
    @Override
    public abstract int hashCode();

    @Override
    public final String toString() {
      return Joiner.on('&').join(bounds());
    }
  }

  /** A method type. */
  @AutoValue
  abstract class MethodTy implements Type {

    public abstract ImmutableSet<TyVarSymbol> tyParams();

    public abstract Type returnType();

    /** The type of the receiver parameter (see JLS 8.4.1). */
    public abstract @Nullable Type receiverType();

    public abstract ImmutableList<Type> parameters();

    public abstract ImmutableList<Type> thrown();

    public static MethodTy create(
        ImmutableSet<TyVarSymbol> tyParams,
        Type returnType,
        Type receiverType,
        ImmutableList<Type> parameters,
        ImmutableList<Type> thrown) {
      return new AutoValue_Type_MethodTy(tyParams, returnType, receiverType, parameters, thrown);
    }

    @Override
    public TyKind tyKind() {
      return TyKind.METHOD_TY;
    }

    @Override
    public final String toString() {
      StringBuilder sb = new StringBuilder();
      if (!tyParams().isEmpty()) {
        sb.append('<');
        Joiner.on(',').appendTo(sb, tyParams());
        sb.append('>');
      }
      sb.append('(');
      Joiner.on(',').appendTo(sb, parameters());
      sb.append(')');
      sb.append(returnType());
      return sb.toString();
    }

    @Memoized
    @Override
    public abstract int hashCode();
  }

  /** An error type. */
  final class ErrorTy implements Type {

    private final String name;
    private final ImmutableList<Type> targs;

    private ErrorTy(String name, ImmutableList<Type> targs) {
      this.name = requireNonNull(name);
      this.targs = requireNonNull(targs);
    }

    /**
     * Best-effort syntactic context for use in diagnostics or by annotation processors. This may be
     * a simple or qualified name; it is not a canonical qualified name.
     */
    public String name() {
      return name;
    }

    public ImmutableList<Type> targs() {
      return targs;
    }

    public static ErrorTy create(Iterable<Tree.Ident> names, ImmutableList<Type> targs) {
      List<String> bits = new ArrayList<>();
      for (Tree.Ident ident : names) {
        bits.add(ident.value());
      }
      return create(Joiner.on('.').join(bits), targs);
    }

    public static ErrorTy create(String name, ImmutableList<Type> targs) {
      return new ErrorTy(name, targs);
    }

    @Override
    public TyKind tyKind() {
      return TyKind.ERROR_TY;
    }

    @Override
    public final String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(name());
      if (!targs().isEmpty()) {
        sb.append('<');
        Joiner.on(',').appendTo(sb, targs());
        sb.append('>');
      }
      return sb.toString();
    }

    @Override
    public final int hashCode() {
      return System.identityHashCode(this);
    }

    @Override
    public final boolean equals(@Nullable Object other) {
      // The name associated with an error type is context for use in diagnostics or by annotations
      // processors. Two error types with the same name don't necessarily represent the same type.

      // TODO(cushon): should error types compare equal to themselves if they correspond to the same
      // source location? Investigate storing the source position for this type, or replacing with
      // `this == other` (and removing interning in ModelFactory).

      return false;
    }
  }
}
