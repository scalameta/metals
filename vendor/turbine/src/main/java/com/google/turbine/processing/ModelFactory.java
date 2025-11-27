/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

package com.google.turbine.processing;

import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.RecordComponentInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.PackageSymbol;
import com.google.turbine.binder.sym.ParamSymbol;
import com.google.turbine.binder.sym.RecordComponentSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.processing.TurbineElement.TurbineExecutableElement;
import com.google.turbine.processing.TurbineElement.TurbineFieldElement;
import com.google.turbine.processing.TurbineElement.TurbineNoTypeElement;
import com.google.turbine.processing.TurbineElement.TurbinePackageElement;
import com.google.turbine.processing.TurbineElement.TurbineParameterElement;
import com.google.turbine.processing.TurbineElement.TurbineRecordComponentElement;
import com.google.turbine.processing.TurbineElement.TurbineTypeElement;
import com.google.turbine.processing.TurbineElement.TurbineTypeParameterElement;
import com.google.turbine.processing.TurbineTypeMirror.TurbineArrayType;
import com.google.turbine.processing.TurbineTypeMirror.TurbineDeclaredType;
import com.google.turbine.processing.TurbineTypeMirror.TurbineErrorType;
import com.google.turbine.processing.TurbineTypeMirror.TurbineExecutableType;
import com.google.turbine.processing.TurbineTypeMirror.TurbineIntersectionType;
import com.google.turbine.processing.TurbineTypeMirror.TurbineNoType;
import com.google.turbine.processing.TurbineTypeMirror.TurbineNullType;
import com.google.turbine.processing.TurbineTypeMirror.TurbinePackageType;
import com.google.turbine.processing.TurbineTypeMirror.TurbinePrimitiveType;
import com.google.turbine.processing.TurbineTypeMirror.TurbineTypeVariable;
import com.google.turbine.processing.TurbineTypeMirror.TurbineVoidType;
import com.google.turbine.processing.TurbineTypeMirror.TurbineWildcardType;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ErrorTy;
import com.google.turbine.type.Type.IntersectionTy;
import com.google.turbine.type.Type.MethodTy;
import com.google.turbine.type.Type.PrimTy;
import com.google.turbine.type.Type.TyVar;
import com.google.turbine.type.Type.WildTy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.lang.model.element.Element;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.NoType;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeMirror;

/**
 * A factoy for turbine's implementations of {@link Element} and {@link TypeMirror}.
 *
 * <p>The model provided by those interfaces contains cycles between types and elements, e.g. {@link
 * Element#asType} and {@link javax.lang.model.type.DeclaredType#asElement}. Turbine's internal
 * model uses an immutable representation of classes and types which cannot represent cycles
 * directly. Instead, the implementations in {@link TurbineElement} and {@link TurbineTypeMirror}
 * maintain a reference to this class, and use it to lazily construct edges in the type and element
 * graph.
 */
public class ModelFactory {

  public Env<ClassSymbol, ? extends TypeBoundClass> env;

  private final AtomicInteger round = new AtomicInteger(0);

  public void round(CompoundEnv<ClassSymbol, TypeBoundClass> env, TopLevelIndex tli) {
    this.env = env;
    this.tli = tli;
    round.getAndIncrement();
    cha.round(env);
  }

  private final HashMap<Type, TurbineTypeMirror> typeCache = new HashMap<>();

  private final Map<FieldSymbol, TurbineFieldElement> fieldCache = new HashMap<>();
  private final Map<MethodSymbol, TurbineExecutableElement> methodCache = new HashMap<>();
  private final Map<ClassSymbol, TurbineTypeElement> classCache = new HashMap<>();
  private final Map<ParamSymbol, TurbineParameterElement> paramCache = new HashMap<>();
  private final Map<RecordComponentSymbol, TurbineRecordComponentElement> recordComponentCache =
      new HashMap<>();
  private final Map<TyVarSymbol, TurbineTypeParameterElement> tyParamCache = new HashMap<>();
  private final Map<PackageSymbol, TurbinePackageElement> packageCache = new HashMap<>();

  private final HashMap<CharSequence, ClassSymbol> inferSymbolCache = new HashMap<>();

  private final ClassHierarchy cha;
  private final ClassLoader processorLoader;

  private TopLevelIndex tli;

  public ModelFactory(
      Env<ClassSymbol, ? extends TypeBoundClass> env,
      ClassLoader processorLoader,
      TopLevelIndex tli) {
    this.env = requireNonNull(env);
    this.cha = new ClassHierarchy(env);
    this.processorLoader = requireNonNull(processorLoader);
    this.tli = requireNonNull(tli);
  }

  TypeMirror asTypeMirror(Type type) {
    return typeCache.computeIfAbsent(type, this::createTypeMirror);
  }

  /**
   * Returns a supplier that memoizes the result of the input supplier.
   *
   * <p>It ensures that the results are invalidated after each annotation processing round, to
   * support computations that depend on information in the current round and which might change in
   * future, e.g. as additional types are generated.
   */
  <T> Supplier<T> memoize(Supplier<T> s) {
    return new Supplier<T>() {
      T v;
      int initializedInRound = -1;

      @Override
      public T get() {
        int r = round.get();
        if (initializedInRound != r) {
          v = s.get();
          initializedInRound = r;
        }
        return v;
      }
    };
  }

  /** Creates a {@link TurbineTypeMirror} backed by a {@link Type}. */
  private TurbineTypeMirror createTypeMirror(Type type) {
    switch (type.tyKind()) {
      case PRIM_TY:
        if (((PrimTy) type).primkind() == TurbineConstantTypeKind.STRING) {
          return new TurbineDeclaredType(this, ClassTy.STRING);
        }
        return new TurbinePrimitiveType(this, (PrimTy) type);
      case CLASS_TY:
        return new TurbineDeclaredType(this, (ClassTy) type);
      case ARRAY_TY:
        return new TurbineArrayType(this, (ArrayTy) type);
      case VOID_TY:
        return new TurbineVoidType(this);
      case WILD_TY:
        return new TurbineWildcardType(this, (WildTy) type);
      case TY_VAR:
        return new TurbineTypeVariable(this, (TyVar) type);
      case INTERSECTION_TY:
        IntersectionTy intersectionTy = (IntersectionTy) type;
        switch (intersectionTy.bounds().size()) {
          case 0:
            return createTypeMirror(ClassTy.OBJECT);
          case 1:
            return createTypeMirror(getOnlyElement(intersectionTy.bounds()));
          default:
            return new TurbineIntersectionType(this, intersectionTy);
        }
      case NONE_TY:
        return new TurbineNoType(this);
      case METHOD_TY:
        return new TurbineExecutableType(this, (MethodTy) type);
      case ERROR_TY:
        return new TurbineErrorType(this, (ErrorTy) type);
    }
    throw new AssertionError(type.tyKind());
  }

  /** Creates a list of {@link TurbineTypeMirror}s backed by the given {@link Type}s. */
  ImmutableList<TypeMirror> asTypeMirrors(Iterable<? extends Type> types) {
    ImmutableList.Builder<TypeMirror> result = ImmutableList.builder();
    for (Type type : types) {
      result.add(asTypeMirror(type));
    }
    return result.build();
  }

  NoType noType() {
    return (NoType) asTypeMirror(Type.NONE);
  }

  NoType packageType(PackageSymbol symbol) {
    return new TurbinePackageType(this, symbol);
  }

  public NullType nullType() {
    return new TurbineNullType(this);
  }

  /** Creates an {@link Element} backed by the given {@link Symbol}. */
  Element element(Symbol symbol) {
    switch (symbol.symKind()) {
      case CLASS:
        return typeElement((ClassSymbol) symbol);
      case TY_PARAM:
        return typeParameterElement((TyVarSymbol) symbol);
      case METHOD:
        return executableElement((MethodSymbol) symbol);
      case FIELD:
        return fieldElement((FieldSymbol) symbol);
      case PARAMETER:
        return parameterElement((ParamSymbol) symbol);
      case RECORD_COMPONENT:
        return recordComponentElement((RecordComponentSymbol) symbol);
      case PACKAGE:
        return packageElement((PackageSymbol) symbol);
      case MODULE:
        break;
    }
    throw new AssertionError(symbol.symKind());
  }

  Element noElement(String name) {
    return new TurbineNoTypeElement(this, name);
  }

  TurbineFieldElement fieldElement(FieldSymbol symbol) {
    return fieldCache.computeIfAbsent(symbol, k -> new TurbineFieldElement(this, symbol));
  }

  TurbineExecutableElement executableElement(MethodSymbol symbol) {
    return methodCache.computeIfAbsent(symbol, k -> new TurbineExecutableElement(this, symbol));
  }

  public TurbineTypeElement typeElement(ClassSymbol symbol) {
    Verify.verify(!symbol.simpleName().equals("package-info"), "%s", symbol);
    return classCache.computeIfAbsent(symbol, k -> new TurbineTypeElement(this, symbol));
  }

  TurbinePackageElement packageElement(PackageSymbol symbol) {
    return packageCache.computeIfAbsent(symbol, k -> new TurbinePackageElement(this, symbol));
  }

  VariableElement parameterElement(ParamSymbol sym) {
    return paramCache.computeIfAbsent(sym, k -> new TurbineParameterElement(this, sym));
  }

  RecordComponentElement recordComponentElement(RecordComponentSymbol sym) {
    return recordComponentCache.computeIfAbsent(
        sym, k -> new TurbineRecordComponentElement(this, sym));
  }

  TurbineTypeParameterElement typeParameterElement(TyVarSymbol sym) {
    return tyParamCache.computeIfAbsent(sym, k -> new TurbineTypeParameterElement(this, sym));
  }

  ImmutableSet<Element> elements(ImmutableSet<? extends Symbol> symbols) {
    ImmutableSet.Builder<Element> result = ImmutableSet.builder();
    for (Symbol symbol : symbols) {
      result.add(element(symbol));
    }
    return result.build();
  }

  public ClassSymbol inferSymbol(CharSequence name) {
    return inferSymbolCache.computeIfAbsent(name, key -> inferSymbolImpl(name));
  }

  private ClassSymbol inferSymbolImpl(CharSequence name) {
    LookupResult lookup = tli.scope().lookup(new LookupKey(asIdents(name)));
    if (lookup == null) {
      return null;
    }
    ClassSymbol sym = (ClassSymbol) lookup.sym();
    for (Ident bit : lookup.remaining()) {
      sym = getSymbol(sym).children().get(bit.value());
      if (sym == null) {
        return null;
      }
    }
    return sym;
  }

  private static ImmutableList<Ident> asIdents(CharSequence name) {
    ImmutableList.Builder<Ident> result = ImmutableList.builder();
    for (String bit : Splitter.on('.').split(name)) {
      result.add(new Ident(-1, bit));
    }
    return result.build();
  }

  /**
   * Returns the {@link TypeBoundClass} for the given {@link ClassSymbol} from the current
   * environment.
   */
  TypeBoundClass getSymbol(ClassSymbol sym) {
    return env.get(sym);
  }

  MethodInfo getMethodInfo(MethodSymbol method) {
    TypeBoundClass info = getSymbol(method.owner());
    for (MethodInfo m : info.methods()) {
      if (m.sym().equals(method)) {
        return m;
      }
    }
    return null;
  }

  ParamInfo getParamInfo(ParamSymbol sym) {
    MethodInfo info = getMethodInfo(sym.owner());
    for (ParamInfo p : info.parameters()) {
      if (p.sym().equals(sym)) {
        return p;
      }
    }
    return null;
  }

  RecordComponentInfo getRecordComponentInfo(RecordComponentSymbol sym) {
    TypeBoundClass info = getSymbol(sym.owner());
    for (RecordComponentInfo component : info.components()) {
      if (component.sym().equals(sym)) {
        return component;
      }
    }
    return null;
  }

  FieldInfo getFieldInfo(FieldSymbol symbol) {
    TypeBoundClass info = getSymbol(symbol.owner());
    requireNonNull(info, symbol.owner().toString());
    for (FieldInfo field : info.fields()) {
      if (field.sym().equals(symbol)) {
        return field;
      }
    }
    throw new AssertionError(symbol);
  }

  TyVarInfo getTyVarInfo(TyVarSymbol tyVar) {
    Symbol owner = tyVar.owner();
    Verify.verifyNotNull(owner); // TODO(cushon): capture variables
    ImmutableMap<TyVarSymbol, TyVarInfo> tyParams;
    switch (owner.symKind()) {
      case METHOD:
        tyParams = getMethodInfo((MethodSymbol) owner).tyParams();
        break;
      case CLASS:
        tyParams = getSymbol((ClassSymbol) owner).typeParameterTypes();
        break;
      default:
        throw new AssertionError(owner.symKind());
    }
    return tyParams.get(tyVar);
  }

  static ClassSymbol enclosingClass(Symbol sym) {
    switch (sym.symKind()) {
      case CLASS:
        return (ClassSymbol) sym;
      case TY_PARAM:
        return enclosingClass(((TyVarSymbol) sym).owner());
      case METHOD:
        return ((MethodSymbol) sym).owner();
      case FIELD:
        return ((FieldSymbol) sym).owner();
      case PARAMETER:
        return ((ParamSymbol) sym).owner().owner();
      case RECORD_COMPONENT:
        return ((RecordComponentSymbol) sym).owner();
      case PACKAGE:
      case MODULE:
        throw new IllegalArgumentException(sym.toString());
    }
    throw new AssertionError(sym.symKind());
  }

  ClassHierarchy cha() {
    return cha;
  }

  ClassLoader processorLoader() {
    return processorLoader;
  }

  TopLevelIndex tli() {
    return tli;
  }
}
