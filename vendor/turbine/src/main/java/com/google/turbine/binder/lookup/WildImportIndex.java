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

package com.google.turbine.binder.lookup;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.ImportDecl;
import org.jspecify.annotations.Nullable;

/**
 * A scope that provides best-effort lookup for on-demand imported types in a compilation unit.
 *
 * <p>Resolution is lazy, imports are not evaluated until the first request for a matching simple
 * name.
 */
public class WildImportIndex implements ImportScope {

  /** {@link ImportScope}s for all on-demand imports in the compilation unit. */
  private final ImmutableList<Supplier<ImportScope>> packages;

  public WildImportIndex(ImmutableList<Supplier<ImportScope>> packages) {
    this.packages = packages;
  }

  /** Creates an import index for the given top-level environment. */
  public static WildImportIndex create(
      CanonicalSymbolResolver importResolver,
      final TopLevelIndex cpi,
      ImmutableList<ImportDecl> imports) {
    ImmutableList.Builder<Supplier<@Nullable ImportScope>> packageScopes = ImmutableList.builder();
    for (final ImportDecl i : imports) {
      if (i.wild()) {
        packageScopes.add(
            Suppliers.memoize(
                new Supplier<@Nullable ImportScope>() {
                  @Override
                  public @Nullable ImportScope get() {
                    if (i.stat()) {
                      return staticOnDemandImport(cpi, i, importResolver);
                    } else {
                      return onDemandImport(cpi, i, importResolver);
                    }
                  }
                }));
      }
    }
    return new WildImportIndex(packageScopes.build());
  }

  /** Full resolve the type for a non-static on-demand import. */
  private static @Nullable ImportScope onDemandImport(
      TopLevelIndex cpi, ImportDecl i, final CanonicalSymbolResolver importResolver) {
    ImmutableList.Builder<String> flatNames = ImmutableList.builder();
    for (Tree.Ident ident : i.type()) {
      flatNames.add(ident.value());
    }
    Scope packageIndex = cpi.lookupPackage(flatNames.build());
    if (packageIndex != null) {
      // a wildcard import of a package
      return new ImportScope() {
        @Override
        public @Nullable LookupResult lookup(LookupKey lookupKey, ResolveFunction resolve) {
          return packageIndex.lookup(lookupKey);
        }
      };
    }
    LookupResult result = cpi.scope().lookup(new LookupKey(i.type()));
    if (result == null) {
      return null;
    }
    ClassSymbol member = resolveImportBase(result, importResolver, importResolver);
    if (member == null) {
      return null;
    }
    return new ImportScope() {
      @Override
      public @Nullable LookupResult lookup(LookupKey lookupKey, ResolveFunction unused) {
        return resolveMember(member, importResolver, importResolver, lookupKey);
      }
    };
  }

  /**
   * Resolve the base class symbol of a possibly non-canonical static on-demand import (see {@code
   * ImportScope#staticNamedImport} for an explanation of why the possibly non-canonical part is
   * deferred).
   */
  private static @Nullable ImportScope staticOnDemandImport(
      TopLevelIndex cpi, ImportDecl i, final CanonicalSymbolResolver importResolver) {
    LookupResult result = cpi.scope().lookup(new LookupKey(i.type()));
    if (result == null) {
      return null;
    }
    return new ImportScope() {
      @Override
      public @Nullable LookupResult lookup(LookupKey lookupKey, ResolveFunction resolve) {
        ClassSymbol member = resolveImportBase(result, resolve, importResolver);
        if (member == null) {
          return null;
        }
        return resolveMember(member, resolve, importResolver, lookupKey);
      }
    };
  }

  private static @Nullable LookupResult resolveMember(
      ClassSymbol base,
      ResolveFunction resolve,
      CanonicalSymbolResolver importResolver,
      LookupKey lookupKey) {
    ClassSymbol member = resolve.resolveOne(base, lookupKey.first());
    if (member == null) {
      return null;
    }
    if (!importResolver.visible(member)) {
      return null;
    }
    return new LookupResult(member, lookupKey);
  }

  static @Nullable ClassSymbol resolveImportBase(
      LookupResult result, ResolveFunction resolve, CanonicalSymbolResolver importResolver) {
    ClassSymbol member = (ClassSymbol) result.sym();
    for (Tree.Ident bit : result.remaining()) {
      member = resolve.resolveOne(member, bit);
      if (member == null) {
        return null;
      }
      if (!importResolver.visible(member)) {
        return null;
      }
    }
    return member;
  }

  @Override
  public @Nullable LookupResult lookup(LookupKey lookup, ResolveFunction resolve) {
    for (Supplier<ImportScope> packageScope : packages) {
      ImportScope scope = packageScope.get();
      if (scope == null) {
        continue;
      }
      LookupResult result = scope.lookup(lookup, resolve);
      if (result == null) {
        continue;
      }
      ClassSymbol sym = (ClassSymbol) result.sym();
      if (!resolve.visible(sym)) {
        continue;
      }
      return result;
    }
    return null;
  }
}
