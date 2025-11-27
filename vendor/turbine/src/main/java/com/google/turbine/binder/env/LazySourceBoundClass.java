/*
 * Copyright 2025 Google Inc. All Rights Reserved.
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

package com.google.turbine.binder.env;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.HierarchyBinder;
import com.google.turbine.binder.TypeBinder;
import com.google.turbine.binder.bound.AnnotationMetadata;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineLog.TurbineLogWithSource;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import org.jspecify.annotations.Nullable;

/**
 * A lazy implementation of {@link TypeBoundClass} that triggers binding phases only when needed.
 */
public class LazySourceBoundClass implements TypeBoundClass {

  private final ClassSymbol sym;
  private final PackageSourceBoundClass base;
  private final Env<ClassSymbol, ? extends HeaderBoundClass> baseEnv;
  private final TurbineLogWithSource log;

  // Lazy phases
  private final Supplier<SourceHeaderBoundClass> header;
  private final Supplier<SourceTypeBoundClass> type;

  public LazySourceBoundClass(
      TurbineLogWithSource log,
      ClassSymbol sym,
      PackageSourceBoundClass base,
      Env<ClassSymbol, ? extends HeaderBoundClass> baseEnv) {
    this.log = log;
    this.sym = sym;
    this.base = base;
    this.baseEnv = baseEnv;

    this.header = Suppliers.memoize(() -> HierarchyBinder.bind(log, sym, base, baseEnv));

    this.type =
        Suppliers.memoize(
            () -> {
              SourceHeaderBoundClass h = header.get();
              // TURBINE-DIFF START
              @SuppressWarnings("unchecked")
              Env<ClassSymbol, HeaderBoundClass> env = (Env<ClassSymbol, HeaderBoundClass>) baseEnv;
              // TURBINE-DIFF END
              return TypeBinder.bind(log, env, sym, h);
            });
  }

  @Override
  public TurbineTyKind kind() {
    return header.get().kind();
  }

  @Override
  public @Nullable ClassSymbol owner() {
    return header.get().owner();
  }

  @Override
  public int access() {
    return header.get().access();
  }

  @Override
  public ImmutableMap<String, ClassSymbol> children() {
    return header.get().children();
  }

  @Override
  public ImmutableMap<String, TyVarSymbol> typeParameters() {
    return header.get().typeParameters();
  }

  @Override
  public ImmutableList<TypeBoundClass.RecordComponentInfo> components() {
    return type.get().components();
  }

  @Override
  public ImmutableList<MethodInfo> methods() {
    return type.get().methods();
  }

  @Override
  public ImmutableList<FieldInfo> fields() {
    return type.get().fields();
  }

  @Override
  public @Nullable ClassSymbol superclass() {
    return header.get().superclass();
  }

  @Override
  public ImmutableList<ClassSymbol> interfaces() {
    return header.get().interfaces();
  }

  @Override
  public ImmutableList<ClassSymbol> permits() {
    return type.get().permits();
  }

  @Override
  public @Nullable Type superClassType() {
    return type.get().superClassType();
  }

  @Override
  public ImmutableList<Type> interfaceTypes() {
    return type.get().interfaceTypes();
  }

  @Override
  public ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes() {
    return type.get().typeParameterTypes();
  }

  @Override
  public @Nullable AnnotationMetadata annotationMetadata() {
    return type.get().annotationMetadata();
  }

  @Override
  public ImmutableList<AnnoInfo> annotations() {
    return type.get().annotations();
  }

  public SourceFile source() {
    return base.source();
  }
}
