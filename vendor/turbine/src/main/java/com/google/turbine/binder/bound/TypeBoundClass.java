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

package com.google.turbine.binder.bound;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.ParamSymbol;
import com.google.turbine.binder.sym.RecordComponentSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.MethDecl;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.IntersectionTy;
import com.google.turbine.type.Type.MethodTy;
import org.jspecify.annotations.Nullable;

/** A bound node that augments {@link HeaderBoundClass} with type information. */
public interface TypeBoundClass extends HeaderBoundClass {

  /** The super-class type. */
  @Nullable Type superClassType();

  /** Implemented interface types. */
  ImmutableList<Type> interfaceTypes();

  /** The permitted direct subclasses. */
  ImmutableList<ClassSymbol> permits();

  ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes();

  /** Declared fields. */
  ImmutableList<FieldInfo> fields();

  /** Declared methods. */
  ImmutableList<MethodInfo> methods();

  /** Record components. */
  ImmutableList<RecordComponentInfo> components();

  /**
   * Annotation metadata, e.g. from {@link java.lang.annotation.Target}, {@link
   * java.lang.annotation.Retention}, and {@link java.lang.annotation.Repeatable}.
   */
  @Nullable AnnotationMetadata annotationMetadata();

  /** Declaration annotations. */
  ImmutableList<AnnoInfo> annotations();

  /** A type parameter declaration. */
  class TyVarInfo {
    private final IntersectionTy upperBound;
    private final @Nullable Type lowerBound;
    private final ImmutableList<AnnoInfo> annotations;

    public TyVarInfo(
        IntersectionTy upperBound, @Nullable Type lowerBound, ImmutableList<AnnoInfo> annotations) {
      this.upperBound = upperBound;
      if (lowerBound != null) {
        throw new IllegalArgumentException("TODO(cushon): support lower bounds");
      }
      this.lowerBound = lowerBound;
      this.annotations = annotations;
    }

    /** The upper bound. */
    public IntersectionTy upperBound() {
      return upperBound;
    }

    /** The lower bound. */
    public @Nullable Type lowerBound() {
      return lowerBound;
    }

    /** Type parameter declaration annotations. */
    public ImmutableList<AnnoInfo> annotations() {
      return annotations;
    }
  }

  /** A field declaration. */
  class FieldInfo {
    private final FieldSymbol sym;
    private final Type type;
    private final int access;
    private final ImmutableList<AnnoInfo> annotations;

    private final Tree.@Nullable VarDecl decl;
    private final Const.@Nullable Value value;

    public FieldInfo(
        FieldSymbol sym,
        Type type,
        int access,
        ImmutableList<AnnoInfo> annotations,
        Tree.@Nullable VarDecl decl,
        Const.@Nullable Value value) {
      this.sym = sym;
      this.type = type;
      this.access = access;
      this.annotations = annotations;
      this.decl = decl;
      this.value = value;
    }

    /** The field symbol. */
    public FieldSymbol sym() {
      return sym;
    }

    /** The field name. */
    public String name() {
      return sym.name();
    }

    /** The field type. */
    public Type type() {
      return type;
    }

    /** Access bits. */
    public int access() {
      return access;
    }

    /** The field's declaration. */
    public Tree.@Nullable VarDecl decl() {
      return decl;
    }

    /** The constant field value. */
    public Const.@Nullable Value value() {
      return value;
    }

    /** Declaration annotations. */
    public ImmutableList<AnnoInfo> annotations() {
      return annotations;
    }
  }

  /** A declared method. */
  class MethodInfo {
    private final MethodSymbol sym;
    private final ImmutableMap<TyVarSymbol, TyVarInfo> tyParams;
    private final Type returnType;
    private final ImmutableList<ParamInfo> parameters;
    private final ImmutableList<Type> exceptions;
    private final int access;
    private final @Nullable Const defaultValue;
    private final @Nullable MethDecl decl;
    private final ImmutableList<AnnoInfo> annotations;
    private final @Nullable ParamInfo receiver;

    public MethodInfo(
        MethodSymbol sym,
        ImmutableMap<TyVarSymbol, TyVarInfo> tyParams,
        Type returnType,
        ImmutableList<ParamInfo> parameters,
        ImmutableList<Type> exceptions,
        int access,
        @Nullable Const defaultValue,
        @Nullable MethDecl decl,
        ImmutableList<AnnoInfo> annotations,
        @Nullable ParamInfo receiver) {
      this.sym = sym;
      this.tyParams = tyParams;
      this.returnType = returnType;
      this.parameters = parameters;
      this.exceptions = exceptions;
      this.access = access;
      this.defaultValue = defaultValue;
      this.decl = decl;
      this.annotations = annotations;
      this.receiver = receiver;
    }

    /** The method symbol. */
    public MethodSymbol sym() {
      return sym;
    }

    /** The method name. */
    public String name() {
      return sym.name();
    }

    /** The type parameters */
    public ImmutableMap<TyVarSymbol, TyVarInfo> tyParams() {
      return tyParams;
    }

    /** Type return type, possibly {#link Type#VOID}. */
    public Type returnType() {
      return returnType;
    }

    /** The formal parameters. */
    public ImmutableList<ParamInfo> parameters() {
      return parameters;
    }

    /** Thrown exceptions. */
    public ImmutableList<Type> exceptions() {
      return exceptions;
    }

    /** Access bits. */
    public int access() {
      return access;
    }

    /** The default value of an annotation interface method. */
    public @Nullable Const defaultValue() {
      return defaultValue;
    }

    /**
     * Returns true for annotation members with a default value. The default value may not have been
     * bound yet, in which case {@link #defaultValue} may still return {@code null}.
     */
    public boolean hasDefaultValue() {
      return decl() != null ? decl().defaultValue().isPresent() : defaultValue() != null;
    }

    /** The declaration. */
    public @Nullable MethDecl decl() {
      return decl;
    }

    /** Declaration annotations. */
    public ImmutableList<AnnoInfo> annotations() {
      return annotations;
    }

    /** Receiver parameter (see JLS 8.4.1), or {@code null}. */
    public @Nullable ParamInfo receiver() {
      return receiver;
    }

    public MethodTy asType() {
      return MethodTy.create(
          tyParams.keySet(),
          returnType,
          receiver != null ? receiver.type() : null,
          asTypes(parameters),
          exceptions);
    }

    private static ImmutableList<Type> asTypes(ImmutableList<ParamInfo> parameters) {
      ImmutableList.Builder<Type> result = ImmutableList.builder();
      for (ParamInfo param : parameters) {
        if (!param.synthetic()) {
          result.add(param.type());
        }
      }
      return result.build();
    }
  }

  /** A formal parameter declaration. */
  class ParamInfo {
    private final ParamSymbol sym;
    private final Type type;
    private final int access;
    private final ImmutableList<AnnoInfo> annotations;

    public ParamInfo(ParamSymbol sym, Type type, ImmutableList<AnnoInfo> annotations, int access) {
      this.sym = sym;
      this.type = type;
      this.access = access;
      this.annotations = annotations;
    }

    /** The parameter's symbol. */
    public ParamSymbol sym() {
      return sym;
    }

    /** The parameter type. */
    public Type type() {
      return type;
    }

    /**
     * Returns true if the parameter is synthetic, e.g. the enclosing instance parameter in an inner
     * class constructor.
     */
    public boolean synthetic() {
      return (access & (TurbineFlag.ACC_SYNTHETIC | TurbineFlag.ACC_MANDATED)) != 0;
    }

    /** Parameter annotations. */
    public ImmutableList<AnnoInfo> annotations() {
      return annotations;
    }

    /** The parameter's name. */
    public String name() {
      return sym.name();
    }

    /** The parameter's modifiers. */
    public int access() {
      return access;
    }
  }

  /** A record component. */
  class RecordComponentInfo {
    private final RecordComponentSymbol sym;
    private final Type type;
    private final int access;
    private final ImmutableList<AnnoInfo> annotations;

    public RecordComponentInfo(
        RecordComponentSymbol sym, Type type, ImmutableList<AnnoInfo> annotations, int access) {
      this.sym = sym;
      this.type = type;
      this.access = access;
      this.annotations = annotations;
    }

    /** The record component's symbol. */
    public RecordComponentSymbol sym() {
      return sym;
    }

    /** The record component type. */
    public Type type() {
      return type;
    }

    /** Record component annotations. */
    public ImmutableList<AnnoInfo> annotations() {
      return annotations;
    }

    /** The Record component's name. */
    public String name() {
      return sym.name();
    }

    /** The Record component's modifiers. */
    public int access() {
      return access;
    }
  }
}
