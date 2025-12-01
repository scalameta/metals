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
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.TyKind;
import org.jspecify.annotations.Nullable;

/** A HeaderBoundClass for classes compiled from source. */
public class SourceTypeBoundClass implements TypeBoundClass {

  private final TurbineTyKind kind;
  private final @Nullable ClassSymbol owner;
  private final ImmutableMap<String, ClassSymbol> children;

  private final int access;
  private final ImmutableMap<String, TyVarSymbol> typeParameters;

  private final ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes;
  private final @Nullable Type superClassType;
  private final ImmutableList<Type> interfaceTypes;
  private final ImmutableList<ClassSymbol> permits;
  private final ImmutableList<RecordComponentInfo> components;
  private final ImmutableList<MethodInfo> methods;
  private final ImmutableList<FieldInfo> fields;
  private final CompoundScope enclosingScope;
  private final CompoundScope scope;
  private final MemberImportIndex memberImports;
  private final @Nullable AnnotationMetadata annotationMetadata;
  private final ImmutableList<AnnoInfo> annotations;
  private final Tree.TyDecl decl;
  private final SourceFile source;

  public SourceTypeBoundClass(
      ImmutableList<Type> interfaceTypes,
      ImmutableList<ClassSymbol> permits,
      @Nullable Type superClassType,
      ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes,
      int access,
      ImmutableList<RecordComponentInfo> components,
      ImmutableList<MethodInfo> methods,
      ImmutableList<FieldInfo> fields,
      @Nullable ClassSymbol owner,
      TurbineTyKind kind,
      ImmutableMap<String, ClassSymbol> children,
      ImmutableMap<String, TyVarSymbol> typeParameters,
      CompoundScope enclosingScope,
      CompoundScope scope,
      MemberImportIndex memberImports,
      @Nullable AnnotationMetadata annotationMetadata,
      ImmutableList<AnnoInfo> annotations,
      SourceFile source,
      Tree.TyDecl decl) {
    this.interfaceTypes = interfaceTypes;
    this.permits = permits;
    this.superClassType = superClassType;
    this.typeParameterTypes = typeParameterTypes;
    this.access = access;
    this.components = components;
    this.methods = methods;
    this.fields = fields;
    this.owner = owner;
    this.kind = kind;
    this.children = children;
    this.typeParameters = typeParameters;
    this.enclosingScope = enclosingScope;
    this.scope = scope;
    this.memberImports = memberImports;
    this.annotationMetadata = annotationMetadata;
    this.annotations = annotations;
    this.source = source;
    this.decl = decl;
  }

  // TURBINE-DIFF START
  public static final SourceTypeBoundClass EMPTY =
      new SourceTypeBoundClass(
          ImmutableList.of(),
          ImmutableList.of(),
          null,
          ImmutableMap.of(),
          0,
          ImmutableList.of(),
          ImmutableList.of(),
          ImmutableList.of(),
          null,
          TurbineTyKind.CLASS,
          ImmutableMap.of(),
          ImmutableMap.of(),
          null,
          null,
          null,
          null,
          ImmutableList.of(),
          null,
          Tree.TyDecl.EMPTY);

  // TURBINE-DIFF END

  @Override
  public @Nullable ClassSymbol superclass() {
    if (superClassType == null) {
      return null;
    }
    if (superClassType.tyKind() != TyKind.CLASS_TY) {
      return null;
    }
    return ((ClassTy) superClassType).sym();
  }

  @Override
  public ImmutableList<ClassSymbol> interfaces() {
    ImmutableList.Builder<ClassSymbol> result = ImmutableList.builder();
    for (Type type : interfaceTypes) {
      if (type.tyKind() == TyKind.CLASS_TY) {
        result.add(((ClassTy) type).sym());
      }
    }
    return result.build();
  }

  @Override
  public ImmutableList<ClassSymbol> permits() {
    return permits;
  }

  @Override
  public int access() {
    return access;
  }

  @Override
  public TurbineTyKind kind() {
    return kind;
  }

  @Override
  public @Nullable ClassSymbol owner() {
    return owner;
  }

  @Override
  public ImmutableMap<String, ClassSymbol> children() {
    return children;
  }

  @Override
  public ImmutableMap<String, TyVarSymbol> typeParameters() {
    return typeParameters;
  }

  @Override
  public ImmutableList<Type> interfaceTypes() {
    return interfaceTypes;
  }

  /** The super-class type. */
  @Override
  public @Nullable Type superClassType() {
    return superClassType;
  }

  /** The record components. */
  @Override
  public ImmutableList<RecordComponentInfo> components() {
    return components;
  }

  /** Declared methods. */
  @Override
  public ImmutableList<MethodInfo> methods() {
    return methods;
  }

  @Override
  public @Nullable AnnotationMetadata annotationMetadata() {
    return annotationMetadata;
  }

  /** Declared fields. */
  @Override
  public ImmutableList<FieldInfo> fields() {
    return fields;
  }

  /** Declared type parameters. */
  @Override
  public ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes() {
    return typeParameterTypes;
  }

  /** The scope of the enclosing declaration or compilation unit. */
  public CompoundScope enclosingScope() {
    return enclosingScope;
  }

  /** The scope of the current class, including its members. */
  public CompoundScope scope() {
    return scope;
  }

  /** The static member import index for the enclosing compilation unit. */
  public MemberImportIndex memberImports() {
    return memberImports;
  }

  @Override
  public ImmutableList<AnnoInfo> annotations() {
    return annotations;
  }

  /** The source file. */
  public SourceFile source() {
    return source;
  }

  public Tree.TyDecl decl() {
    return decl;
  }
}
