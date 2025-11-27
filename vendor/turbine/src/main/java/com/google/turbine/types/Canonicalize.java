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

package com.google.turbine.types;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.IntersectionTy;
import com.google.turbine.type.Type.TyKind;
import com.google.turbine.type.Type.TyVar;
import com.google.turbine.type.Type.WildTy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Canonicalizes qualified type names so qualifiers are always the declaring class of the qualified
 * type.
 *
 * <p>For example, given:
 *
 * <pre>{@code
 * class A<T> {
 *   class Inner {}
 * }
 * class B extends A<String> {
 *   Inner i;
 * }
 * }</pre>
 *
 * <p>The canonical name of the type of {@code B.i} is {@code A<String>.Inner}, not {@code B.Inner}.
 */
public class Canonicalize {

  /** Canonicalizes the given type. */
  public static Type canonicalize(
      SourceFile source,
      int position,
      Env<ClassSymbol, TypeBoundClass> env,
      ClassSymbol sym,
      Type type) {
    return new Canonicalize(source, position, env).canonicalize(sym, type);
  }

  /** Canonicalize a qualified class type, excluding type arguments. */
  public static ClassTy canonicalizeClassTy(
      SourceFile source,
      int position,
      Env<ClassSymbol, TypeBoundClass> env,
      ClassSymbol owner,
      ClassTy classTy) {
    return new Canonicalize(source, position, env).canonicalizeClassTy(owner, classTy);
  }

  private final SourceFile source;
  private final int position;
  private final Env<ClassSymbol, TypeBoundClass> env;

  public Canonicalize(SourceFile source, int position, Env<ClassSymbol, TypeBoundClass> env) {
    this.source = source;
    this.position = position;
    this.env = env;
  }

  private Type canonicalize(ClassSymbol base, Type type) {
    switch (type.tyKind()) {
      case PRIM_TY:
      case VOID_TY:
      case TY_VAR:
      case ERROR_TY:
        return type;
      case WILD_TY:
        return canonicalizeWildTy(base, (WildTy) type);
      case ARRAY_TY:
        {
          Type.ArrayTy arrayTy = (Type.ArrayTy) type;
          return Type.ArrayTy.create(canonicalize(base, arrayTy.elementType()), arrayTy.annos());
        }
      case CLASS_TY:
        return canonicalizeClassTy(base, (ClassTy) type);
      case INTERSECTION_TY:
        return canonicalizeIntersectionTy(base, (IntersectionTy) type);
      default:
        throw new AssertionError(type.tyKind());
    }
  }

  private ClassTy canon(ClassSymbol base, ClassTy ty) {
    if (ty.sym().equals(ClassSymbol.ERROR)) {
      return ty;
    }
    // if the first name is a simple name resolved inside a nested class, add explicit qualifiers
    // for the enclosing declarations
    Iterator<ClassTy.SimpleClassTy> it = ty.classes().iterator();
    Collection<ClassTy.SimpleClassTy> lexicalBase = lexicalBase(ty.classes().get(0).sym(), base);
    ClassTy canon =
        !lexicalBase.isEmpty()
            ? ClassTy.create(lexicalBase)
            : ClassTy.create(Collections.singletonList(it.next()));

    // canonicalize each additional simple name that appeared in source
    while (it.hasNext()) {
      canon = canonOne(canon, it.next());
    }
    if (isRaw(canon)) {
      canon = Erasure.eraseClassTy(canon);
    }
    return canon;
  }

  /**
   * Qualified type names cannot be partially raw; if any elements are raw erase the entire type.
   */
  private boolean isRaw(ClassTy ty) {
    for (ClassTy.SimpleClassTy s : ty.classes().reverse()) {
      TypeBoundClass info = getInfo(s.sym());
      if (s.targs().isEmpty() && !info.typeParameters().isEmpty()) {
        return true;
      }
      if ((info.access() & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC) {
        break;
      }
    }
    return false;
  }

  /** Given a base symbol to canonicalize, find any implicit enclosing instances. */
  private Collection<ClassTy.SimpleClassTy> lexicalBase(ClassSymbol first, ClassSymbol owner) {

    if ((getInfo(first).access() & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC) {
      return ImmutableList.of();
    }
    ClassSymbol canonOwner = getInfo(first).owner();
    Deque<ClassTy.SimpleClassTy> result = new ArrayDeque<>();
    while (canonOwner != null && owner != null) {
      if (!isSubclass(owner, canonOwner)) {
        owner = getInfo(owner).owner();
        continue;
      }
      result.addFirst(uninstantiated(owner));
      if ((getInfo(owner).access() & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC) {
        break;
      }
      canonOwner = getInfo(canonOwner).owner();
    }
    return result;
  }

  private ClassTy.SimpleClassTy uninstantiated(ClassSymbol owner) {
    ImmutableList.Builder<Type> targs = ImmutableList.builder();
    for (TyVarSymbol p : getInfo(owner).typeParameterTypes().keySet()) {
      targs.add(Type.TyVar.create(p, ImmutableList.of()));
    }
    return ClassTy.SimpleClassTy.create(owner, targs.build(), ImmutableList.of());
  }

  // is s a subclass (not interface) of t?
  private boolean isSubclass(ClassSymbol s, ClassSymbol t) {
    while (s != null) {
      if (s.equals(t)) {
        return true;
      }
      s = getInfo(s).superclass();
    }
    return false;
  }

  /**
   * Adds a simple class type to an existing canonical base class type, and canonicalizes the
   * result.
   */
  private ClassTy canonOne(ClassTy base, SimpleClassTy ty) {
    // if the class is static, it has a trivial canonical qualifier with no type arguments
    if ((getInfo(ty.sym()).access() & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC) {
      return ClassTy.create(ImmutableList.of(ty));
    }
    ImmutableList.Builder<ClassTy.SimpleClassTy> simples = ImmutableList.builder();
    // this inner class is known to have an owner
    ClassSymbol owner = requireNonNull(getInfo(ty.sym()).owner());
    if (owner.equals(base.sym())) {
      // if the canonical prefix is the owner the next symbol in the qualified name,
      // the type is already in canonical form
      simples.addAll(base.classes());
      simples.add(ty);
      return ClassTy.create(simples.build());
    }
    // ... otherwise, find the supertype the class was inherited from
    // and instantiate it as a member of the current class
    ClassTy curr = base;
    Map<TyVarSymbol, Type> mapping = new LinkedHashMap<>();
    while (curr != null) {
      for (ClassTy.SimpleClassTy s : curr.classes()) {
        addInstantiation(mapping, s);
      }
      if (curr.sym().equals(owner)) {
        for (ClassTy.SimpleClassTy s : curr.classes()) {
          simples.add(instantiate(mapping, s.sym()));
        }
        break;
      }
      TypeBoundClass info = getInfo(curr.sym());
      curr = canon(info.owner(), (ClassTy) info.superClassType());
    }
    simples.add(ty);
    return ClassTy.create(simples.build());
  }

  /** Add the type arguments of a simple class type to a type mapping. */
  private void addInstantiation(Map<TyVarSymbol, Type> mapping, ClassTy.SimpleClassTy simpleType) {
    Collection<TyVarSymbol> symbols = getInfo(simpleType.sym()).typeParameters().values();
    if (simpleType.targs().isEmpty()) {
      // the type is raw
      for (TyVarSymbol sym : symbols) {
        mapping.put(sym, null);
      }
      return;
    }
    // otherwise, it is an instantiated generic type
    Verify.verify(symbols.size() == simpleType.targs().size());
    Iterator<Type> typeArguments = simpleType.targs().iterator();
    for (TyVarSymbol sym : symbols) {
      Type argument = typeArguments.next();
      if (Objects.equals(tyVarSym(argument), sym)) {
        continue;
      }
      mapping.put(sym, argument);
    }
  }

  /** Instantiate a simple class type for the given symbol, and with the given type mapping. */
  private ClassTy.SimpleClassTy instantiate(
      Map<TyVarSymbol, Type> mapping, ClassSymbol classSymbol) {
    List<Type> args = new ArrayList<>();
    for (TyVarSymbol sym : getInfo(classSymbol).typeParameterTypes().keySet()) {
      if (!mapping.containsKey(sym)) {
        args.add(Type.TyVar.create(sym, ImmutableList.of()));
        continue;
      }
      Type arg = instantiate(mapping, mapping.get(sym));
      if (arg == null) {
        // raw types
        args.clear();
        break;
      }
      args.add(arg);
    }
    return ClassTy.SimpleClassTy.create(
        classSymbol, ImmutableList.copyOf(args), ImmutableList.of());
  }

  /** Instantiates a type argument using the given mapping. */
  private static @Nullable Type instantiate(Map<TyVarSymbol, Type> mapping, Type type) {
    if (type == null) {
      return null;
    }
    switch (type.tyKind()) {
      case WILD_TY:
        return instantiateWildTy(mapping, (WildTy) type);
      case PRIM_TY:
      case VOID_TY:
      case ERROR_TY:
        return type;
      case CLASS_TY:
        return instantiateClassTy(mapping, (ClassTy) type);
      case ARRAY_TY:
        ArrayTy arrayTy = (ArrayTy) type;
        Type elem = instantiate(mapping, arrayTy.elementType());
        return ArrayTy.create(elem, arrayTy.annos());
      case TY_VAR:
        TyVar tyVar = (TyVar) type;
        if (mapping.containsKey(tyVar.sym())) {
          return instantiate(mapping, mapping.get(tyVar.sym()));
        }
        return type;
      default:
        throw new AssertionError(type.tyKind());
    }
  }

  private static Type instantiateWildTy(Map<TyVarSymbol, Type> mapping, WildTy type) {
    switch (type.boundKind()) {
      case NONE:
        return type;
      case UPPER:
        return Type.WildUpperBoundedTy.create(
            instantiate(mapping, type.bound()), type.annotations());
      case LOWER:
        return Type.WildLowerBoundedTy.create(
            instantiate(mapping, type.bound()), type.annotations());
    }
    throw new AssertionError(type.boundKind());
  }

  private static Type instantiateClassTy(Map<TyVarSymbol, Type> mapping, ClassTy type) {
    ImmutableList.Builder<SimpleClassTy> simples = ImmutableList.builder();
    for (SimpleClassTy simple : type.classes()) {
      ImmutableList.Builder<Type> args = ImmutableList.builder();
      for (Type arg : simple.targs()) {
        // result is non-null if arg is
        args.add(requireNonNull(instantiate(mapping, arg)));
      }
      simples.add(SimpleClassTy.create(simple.sym(), args.build(), simple.annos()));
    }
    return ClassTy.create(simples.build());
  }

  /**
   * Returns the type variable symbol for a concrete type argument whose type is a type variable
   * reference, or else {@code null}.
   */
  private static @Nullable TyVarSymbol tyVarSym(Type type) {
    if (type.tyKind() == TyKind.TY_VAR) {
      return ((TyVar) type).sym();
    }
    return null;
  }

  private ClassTy canonicalizeClassTy(ClassSymbol base, ClassTy ty) {
    // canonicalize type arguments first
    ImmutableList.Builder<ClassTy.SimpleClassTy> args = ImmutableList.builder();
    for (ClassTy.SimpleClassTy s : ty.classes()) {
      args.add(SimpleClassTy.create(s.sym(), canonicalize(s.targs(), base), s.annos()));
    }
    ty = ClassTy.create(args.build());
    return canon(base, ty);
  }

  private ImmutableList<Type> canonicalize(ImmutableList<Type> targs, ClassSymbol base) {
    ImmutableList.Builder<Type> result = ImmutableList.builder();
    for (Type a : targs) {
      result.add(canonicalize(base, a));
    }
    return result.build();
  }

  private Type canonicalizeWildTy(ClassSymbol base, WildTy type) {
    switch (type.boundKind()) {
      case NONE:
        return type;
      case LOWER:
        return Type.WildLowerBoundedTy.create(canonicalize(base, type.bound()), type.annotations());
      case UPPER:
        return Type.WildUpperBoundedTy.create(canonicalize(base, type.bound()), type.annotations());
    }
    throw new AssertionError(type.boundKind());
  }

  private Type canonicalizeIntersectionTy(ClassSymbol base, IntersectionTy type) {
    ImmutableList.Builder<Type> bounds = ImmutableList.builder();
    for (Type bound : type.bounds()) {
      bounds.add(canonicalize(base, bound));
    }
    return IntersectionTy.create(bounds.build());
  }

  private TypeBoundClass getInfo(ClassSymbol canonOwner) {
    TypeBoundClass info = env.get(canonOwner);
    if (info == null) {
      throw TurbineError.format(source, position, ErrorKind.CLASS_FILE_NOT_FOUND, canonOwner);
    }
    return info;
  }
}
