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

package com.google.turbine.bytecode.sig;

import com.google.common.collect.ImmutableList;
import com.google.turbine.model.TurbineConstantTypeKind;
import org.jspecify.annotations.Nullable;

/** JVMS 4.7.9.1 signatures. */
public final class Sig {

  /** A JVMS 4.7.9.1 ClassSignature. */
  public static class ClassSig {

    private final ImmutableList<TyParamSig> tyParams;
    private final ClassTySig superClass;
    private final ImmutableList<ClassTySig> interfaces;

    public ClassSig(
        ImmutableList<TyParamSig> tyParams,
        ClassTySig superClass,
        ImmutableList<ClassTySig> interfaces) {
      this.tyParams = tyParams;
      this.superClass = superClass;
      this.interfaces = interfaces;
    }

    /** Formal type parameters. */
    public ImmutableList<TyParamSig> tyParams() {
      return tyParams;
    }

    /** The super class. */
    public ClassTySig superClass() {
      return superClass;
    }

    /** The interface list. */
    public ImmutableList<ClassTySig> interfaces() {
      return interfaces;
    }
  }

  /** A JVMS 4.7.9.1 FormalTypeParameter. */
  public static class TyParamSig {

    private final String name;
    private final @Nullable TySig classBound;
    private final ImmutableList<TySig> interfaceBounds;

    public TyParamSig(
        String name, @Nullable TySig classBound, ImmutableList<TySig> interfaceBounds) {
      this.name = name;
      this.classBound = classBound;
      this.interfaceBounds = interfaceBounds;
    }

    /** A single class upper-bound, or {@code null}. */
    public @Nullable TySig classBound() {
      return classBound;
    }

    /** Interface upper-bounds. */
    public ImmutableList<TySig> interfaceBounds() {
      return interfaceBounds;
    }

    /** The name of the type parameter. */
    public String name() {
      return name;
    }
  }

  /** A JVMS 4.7.9.1 ClassTypeSignature. */
  public static class ClassTySig extends TySig {

    private final String pkg;
    private final ImmutableList<SimpleClassTySig> classes;

    public ClassTySig(String pkg, ImmutableList<SimpleClassTySig> classes) {
      this.pkg = pkg;
      this.classes = classes;
    }

    /** The package name of the class. */
    public String pkg() {
      return pkg;
    }

    /**
     * A list of a simple names, containing at least one top-level type and possible repeated member
     * class names. Each element may include type arguments.
     *
     * <p>It's possible for the top-level type to be a desugared nested class with no type
     * arguments, in this case the first element is the simple name of the lowered type, e.g. in
     * {@code Foo$Bar<X>.<Y>} the first element may be an nested class {@code Bar} with an enclosing
     * type {@code Foo}, but it may also be a top-level class that was named {@code Foo$Bar} in
     * source. The signature is the same either way.
     */
    public ImmutableList<SimpleClassTySig> classes() {
      return classes;
    }

    @Override
    public TySigKind kind() {
      return TySigKind.CLASS_TY_SIG;
    }
  }

  /** A JVMS 4.7.9.1 SimpleClassTypeSignature. */
  public static class SimpleClassTySig {

    private final String simpleName;
    private final ImmutableList<TySig> tyArgs;

    public SimpleClassTySig(String simpleName, ImmutableList<TySig> tyArgs) {
      this.tyArgs = tyArgs;
      this.simpleName = simpleName;
    }

    /** Type arguments. */
    public ImmutableList<TySig> tyArgs() {
      return tyArgs;
    }

    /** The simple name of the class. */
    public String simpleName() {
      return simpleName;
    }
  }

  /**
   * A wildcard type.
   *
   * <p>Wildcard are represented as first class types, instead only allowing them as top-level type
   * arguments. This diverges from the buggy grammar in JVMS 4.7.9.1, see:
   * http://mail.openjdk.java.net/pipermail/compiler-dev/2016-October/010450.html
   */
  public abstract static class WildTySig extends TySig {
    /** A wildcard bound kind. */
    public enum BoundKind {
      /** An unbounded wildcard. */
      NONE,
      /** A lower-bounded wildcard. */
      LOWER,
      /** An upper-bounded wildcard. */
      UPPER
    }

    /** Returns the wildcard bound kind. */
    public abstract BoundKind boundKind();

    @Override
    public TySigKind kind() {
      return TySigKind.WILD_TY_SIG;
    }
  }

  /** An upper-bounded wildcard. */
  public static class UpperBoundTySig extends WildTySig {

    private final TySig bound;

    public UpperBoundTySig(TySig bound) {
      this.bound = bound;
    }

    /** The upper bound. */
    public TySig bound() {
      return bound;
    }

    @Override
    public BoundKind boundKind() {
      return BoundKind.UPPER;
    }
  }

  /** An lower-bounded wildcard. */
  public static class LowerBoundTySig extends WildTySig {

    private final TySig bound;

    public LowerBoundTySig(TySig bound) {
      this.bound = bound;
    }

    /** The lower bound. */
    public TySig bound() {
      return bound;
    }

    @Override
    public BoundKind boundKind() {
      return BoundKind.LOWER;
    }
  }

  /** An unbounded wildcard. */
  public static class WildTyArgSig extends WildTySig {
    @Override
    public BoundKind boundKind() {
      return BoundKind.NONE;
    }
  }

  /** A JVMS 4.7.9.1 ArrayTypeSignature. */
  public static class ArrayTySig extends TySig {

    private final TySig elementType;

    public ArrayTySig(TySig elementType) {
      this.elementType = elementType;
    }

    /** The element type. */
    public TySig elementType() {
      return elementType;
    }

    @Override
    public TySigKind kind() {
      return TySigKind.ARRAY_TY_SIG;
    }
  }

  /** A JVMS 4.7.9.1 TypeVariableSignature. */
  public static class TyVarSig extends TySig {

    public final String name;

    public TyVarSig(String name) {
      this.name = name;
    }

    /** The name of the type variable. */
    public String name() {
      return name;
    }

    @Override
    public TySigKind kind() {
      return TySigKind.TY_VAR_SIG;
    }
  }

  /** An abstract class for all JVMS 4.7.9.1 JavaTypeSignatures. */
  public abstract static class TySig {

    /** The type kind. */
    public enum TySigKind {
      VOID_TY_SIG,
      BASE_TY_SIG,
      CLASS_TY_SIG,
      ARRAY_TY_SIG,
      TY_VAR_SIG,
      WILD_TY_SIG
    }

    /** The type kind. */
    public abstract TySigKind kind();
  }

  /** A JVMS 4.3.3 VoidDescriptor. */
  public static final TySig VOID =
      new TySig() {
        @Override
        public TySigKind kind() {
          return TySigKind.VOID_TY_SIG;
        }
      };

  /** A JVMS 4.3.2 BaseType. */
  public static class BaseTySig extends TySig {

    @Override
    public TySigKind kind() {
      return TySigKind.BASE_TY_SIG;
    }

    private final TurbineConstantTypeKind type;

    public BaseTySig(TurbineConstantTypeKind type) {
      this.type = type;
    }

    /** The base type kind. */
    public TurbineConstantTypeKind type() {
      return type;
    }
  }

  /** A JVMS 4.7.9.1 MethodTypeSignature. */
  public static class MethodSig {

    private final ImmutableList<TyParamSig> tyParams;
    private final ImmutableList<TySig> params;
    private final TySig returnType;
    private final ImmutableList<TySig> exceptions;

    public MethodSig(
        ImmutableList<TyParamSig> tyParams,
        ImmutableList<TySig> params,
        TySig returnType,
        ImmutableList<TySig> exceptions) {
      this.tyParams = tyParams;
      this.params = params;
      this.returnType = returnType;
      this.exceptions = exceptions;
    }

    /** The formal type parameters. */
    public ImmutableList<TyParamSig> tyParams() {
      return tyParams;
    }

    /** The return type. Non-null, possibly {@link #VOID}. */
    public TySig returnType() {
      return returnType;
    }

    /** The formal parameters. */
    public ImmutableList<TySig> params() {
      return params;
    }

    /** The thrown exceptions. */
    public ImmutableList<TySig> exceptions() {
      return exceptions;
    }
  }

  private Sig() {}
}
