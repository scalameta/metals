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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.LazyEnv.LazyBindingError;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.diag.TurbineLog.TurbineLogWithSource;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.ClassTy;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import org.jspecify.annotations.Nullable;

/** Type hierarchy binding. */
public class HierarchyBinder {

  /** Binds the type hierarchy (superclasses and interfaces) for a single class. */
  public static SourceHeaderBoundClass bind(
      TurbineLogWithSource log,
      ClassSymbol origin,
      PackageSourceBoundClass base,
      Env<ClassSymbol, ? extends HeaderBoundClass> env) {
    return new HierarchyBinder(log, origin, base, env).bind();
  }

  private final TurbineLogWithSource log;
  private final ClassSymbol origin;
  private final PackageSourceBoundClass base;
  private final Env<ClassSymbol, ? extends HeaderBoundClass> env;

  private HierarchyBinder(
      TurbineLogWithSource log,
      ClassSymbol origin,
      PackageSourceBoundClass base,
      Env<ClassSymbol, ? extends HeaderBoundClass> env) {
    this.log = log;
    this.origin = origin;
    this.base = base;
    this.env = env;
  }

  private SourceHeaderBoundClass bind() {
    Tree.TyDecl decl = base.decl();

    ClassSymbol superclass;
    if (decl.xtnds().isPresent()) {
      superclass = resolveClass(decl.xtnds().get());
      if (origin.equals(superclass)) {
        log.error(decl.xtnds().get().position(), ErrorKind.CYCLIC_HIERARCHY, origin);
      }
    } else {
      switch (decl.tykind()) {
        case ENUM:
          superclass = ClassSymbol.ENUM;
          break;
        case INTERFACE:
        case ANNOTATION:
        case CLASS:
          superclass = !origin.equals(ClassSymbol.OBJECT) ? ClassSymbol.OBJECT : null;
          break;
        case RECORD:
          superclass = ClassSymbol.RECORD;
          break;
        default:
          throw new AssertionError(decl.tykind());
      }
    }

    ImmutableList.Builder<ClassSymbol> interfaces = ImmutableList.builder();
    if (!decl.impls().isEmpty()) {
      for (Tree.ClassTy i : decl.impls()) {
        ClassSymbol result = resolveClass(i);
        if (result == null) {
          continue;
        }
        if (origin.equals(result)) {
          log.error(i.position(), ErrorKind.CYCLIC_HIERARCHY, origin);
        }
        interfaces.add(result);
      }
    } else {
      if (decl.tykind() == TurbineTyKind.ANNOTATION) {
        interfaces.add(ClassSymbol.ANNOTATION);
      }
    }

    LinkedHashMap<String, TyVarSymbol> typeParameters = new LinkedHashMap<>();
    for (Tree.TyParam p : decl.typarams()) {
      TyVarSymbol existing =
          typeParameters.putIfAbsent(p.name().value(), new TyVarSymbol(origin, p.name().value()));
      if (existing != null) {
        log.error(p.position(), ErrorKind.DUPLICATE_DECLARATION, p.name());
      }
    }

    return new SourceHeaderBoundClass(
        base, superclass, interfaces.build(), ImmutableMap.copyOf(typeParameters));
  }

  /**
   * Resolves the {@link ClassSymbol} for the given {@link Tree.ClassTy}, with handling for
   * non-canonical qualified type names.
   */
  private @Nullable ClassSymbol resolveClass(Tree.ClassTy ty) {
    // flatten a left-recursive qualified type name to its component simple names
    // e.g. Foo<Bar>.Baz -> ["Foo", "Bar"]
    ArrayDeque<Tree.Ident> flat = new ArrayDeque<>();
    for (Tree.ClassTy curr = ty; curr != null; curr = curr.base().orElse(null)) {
      flat.addFirst(curr.name());
    }
    // Resolve the base symbol in the qualified name.
    LookupResult result = lookup(ty, new LookupKey(ImmutableList.copyOf(flat)));
    if (result == null) {
      log.error(ty.position(), ErrorKind.CANNOT_RESOLVE, Joiner.on('.').join(flat));
      return null;
    }
    // Resolve pieces in the qualified name referring to member types.
    // This needs to consider member type declarations inherited from supertypes and interfaces.
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (Tree.Ident bit : result.remaining()) {
      sym = resolveNext(ty, sym, bit);
      if (sym == null) {
        break;
      }
    }
    return sym;
  }

  private @Nullable ClassSymbol resolveNext(ClassTy ty, ClassSymbol sym, Tree.Ident bit) {
    ClassSymbol next;
    try {
      next = Resolve.resolve(env, origin, sym, bit);
    } catch (LazyBindingError e) {
      log.error(ty.position(), ErrorKind.CYCLIC_HIERARCHY, e.getMessage());
      return null;
    }
    if (next == null) {
      log.error(
          bit.position(),
          ErrorKind.SYMBOL_NOT_FOUND,
          new ClassSymbol(sym.binaryName() + '$' + bit));
    }
    return next;
  }

  /** Resolve a qualified type name to a symbol. */
  private @Nullable LookupResult lookup(Tree tree, LookupKey lookup) {
    // Handle any lexically enclosing class declarations (if we're binding a member class).
    // We could build out scopes for this, but it doesn't seem worth it. (And sharing the scopes
    // with other members of the same enclosing declaration would be complicated.)
    for (ClassSymbol curr = base.owner(); curr != null; curr = env.getNonNull(curr).owner()) {
      ClassSymbol result;
      try {
        result = Resolve.resolve(env, origin, curr, lookup.first());
      } catch (LazyBindingError e) {
        log.error(tree.position(), ErrorKind.CYCLIC_HIERARCHY, e.getMessage());
        result = null;
      }
      if (result != null) {
        return new LookupResult(result, lookup);
      }
    }
    // Fall back to the top-level scopes for the compilation unit (imports, same package, then
    // qualified name resolution).
    return base.scope().lookup(lookup, Resolve.resolveFunction(env, origin));
  }
}
