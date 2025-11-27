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

import static com.google.common.collect.Iterables.getLast;

import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.diag.TurbineLog.TurbineLogWithSource;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.tree.Tree.ImportDecl;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A scope that provides entries for the single-type imports in a compilation unit.
 *
 * <p>Import resolution is lazy; imports are not evaluated until the first request for a matching
 * simple name.
 *
 * <p>Static imports of types, and on-demand imports of types (static or otherwise), are not
 * supported.
 */
public class ImportIndex implements ImportScope {

  /**
   * A map from simple names of imported symbols to an {@link ImportScope} for a named (possibly
   * static) import; e.g. {@code `Map` -> ImportScope(`import java.util.Map;`)}.
   */
  private final Map<String, Supplier<ImportScope>> thunks;

  public ImportIndex(TurbineLogWithSource log, ImmutableMap<String, Supplier<ImportScope>> thunks) {
    this.thunks = thunks;
  }

  /** Creates an import index for the given top-level environment. */
  public static ImportIndex create(
      TurbineLogWithSource log,
      CanonicalSymbolResolver resolve,
      final TopLevelIndex cpi,
      ImmutableList<ImportDecl> imports) {
    Map<String, Supplier<@Nullable ImportScope>> thunks = new HashMap<>();
    for (final Tree.ImportDecl i : imports) {
      if (i.stat() || i.wild()) {
        continue;
      }
      thunks.put(
          getLast(i.type()).value(),
          Suppliers.memoize(
              new Supplier<@Nullable ImportScope>() {
                @Override
                public @Nullable ImportScope get() {
                  return namedImport(log, cpi, i, resolve);
                }
              }));
    }
    // Process static imports as a separate pass. If a static and non-static named import share a
    // simple name the non-static import wins.
    for (final Tree.ImportDecl i : imports) {
      if (!i.stat() || i.wild()) {
        continue;
      }
      String last = getLast(i.type()).value();
      thunks.putIfAbsent(
          last,
          Suppliers.memoize(
              new Supplier<@Nullable ImportScope>() {
                @Override
                public @Nullable ImportScope get() {
                  return staticNamedImport(log, cpi, i);
                }
              }));
    }
    return new ImportIndex(log, ImmutableMap.copyOf(thunks));
  }

  /** Fully resolve the canonical name of a non-static named import. */
  private static @Nullable ImportScope namedImport(
      TurbineLogWithSource log, TopLevelIndex cpi, ImportDecl i, CanonicalSymbolResolver resolve) {
    LookupResult result = cpi.scope().lookup(new LookupKey(i.type()));
    if (result == null) {
      log.error(
          i.position(), ErrorKind.SYMBOL_NOT_FOUND, new ClassSymbol(Joiner.on('/').join(i.type())));
      return null;
    }
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (Tree.Ident bit : result.remaining()) {
      sym = resolveNext(log, resolve, sym, bit);
      if (sym == null) {
        return null;
      }
    }
    ClassSymbol resolved = sym;
    return new ImportScope() {
      @Override
      public LookupResult lookup(LookupKey lookupKey, ResolveFunction unused) {
        return new LookupResult(resolved, lookupKey);
      }
    };
  }

  private static @Nullable ClassSymbol resolveNext(
      TurbineLogWithSource log, CanonicalSymbolResolver resolve, ClassSymbol sym, Ident bit) {
    ClassSymbol next = resolve.resolveOne(sym, bit);
    if (next == null) {
      log.error(
          bit.position(),
          ErrorKind.SYMBOL_NOT_FOUND,
          new ClassSymbol(sym.binaryName() + '$' + bit));
    }
    return next;
  }

  /**
   * Resolve the base class symbol of a possibly non-canonical static named import. For example,
   * {@code import static java.util.HashMap.Entry;} is a non-canonical import for {@code
   * java.util.Map.Entry}. We cannot resolve {@code Entry} as a member of {@code HashMap} until the
   * hierarchy analysis is complete, so for now we resolve the base {@code java.util.HashMap} and
   * defer the rest.
   */
  private static @Nullable ImportScope staticNamedImport(
      TurbineLogWithSource log, TopLevelIndex cpi, ImportDecl i) {
    LookupResult base = cpi.scope().lookup(new LookupKey(i.type()));
    if (base == null) {
      log.error(
          i.position(), ErrorKind.SYMBOL_NOT_FOUND, new ClassSymbol(Joiner.on("/").join(i.type())));
      return null;
    }
    return new ImportScope() {
      @Override
      public @Nullable LookupResult lookup(LookupKey lookupKey, ResolveFunction resolve) {
        ClassSymbol sym = (ClassSymbol) base.sym();
        for (Tree.Ident bit : base.remaining()) {
          sym = resolve.resolveOne(sym, bit);
          if (sym == null) {
            // Assume that static imports that don't resolve to types are non-type member imports,
            // even if the simple name matched what we're looking for.
            return null;
          }
        }
        return new LookupResult(sym, lookupKey);
      }
    };
  }

  @Override
  public @Nullable LookupResult lookup(LookupKey lookup, ResolveFunction resolve) {
    Supplier<ImportScope> thunk = thunks.get(lookup.first().value());
    if (thunk == null) {
      return null;
    }
    ImportScope scope = thunk.get();
    if (scope == null) {
      return null;
    }
    return scope.lookup(lookup, resolve);
  }
}
