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

package com.google.turbine.binder.env;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.sym.Symbol;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An env that permits an analysis pass to access information about symbols from the current pass,
 * recursively. Cycles are detected, and result in an {@link LazyBindingError} being thrown.
 *
 * <p>This is used primarily for resolving the supertype hierarchy in {@code HierarchyBinder}. The
 * supertype hierarchy forms a directed acyclic graph, and {@code HierarchyBinder} needs to process
 * classes in a topological sort order of that graph. Unfortuntately, we can't produce a suitable
 * sort order until the graph exists.
 *
 * @param <T> the interface type of the bound node {@link V}, shared by any underlying environments.
 * @param <V> a specific implementation of {@code T}. For example, during hierarchy binding {@code
 *     SourceHeaderBoundClass} nodes are being completed from the sources being compiled, and the
 *     analysis of a given symbol may require looking up {@code HeaderBoundClass} nodes that will
 *     either be backed by other {@code SourceHeaderBoundClass} nodes or {@code BytecodeBoundClass}
 *     nodes. So the phase uses an {@code LazyEnv<HeaderBoundClass, SourceHeaderBoundClass>}.
 */
public class LazyEnv<S extends Symbol, T, V extends T> implements Env<S, V> {

  /** The list of symbols that are currently being processed, used to check for cycles. */
  private final LinkedHashSet<S> seen = new LinkedHashSet<>();

  /** Lazy value providers for the symbols in the environment. */
  private final ImmutableMap<S, Completer<S, T, V>> completers;

  /** Values that have already been computed. */
  private final Map<S, @Nullable V> cache = new LinkedHashMap<>();

  /** An underlying env of already-computed {@code T}s that can be queried during completion. */
  private final Env<S, T> rec;

  public LazyEnv(ImmutableMap<S, Completer<S, T, V>> completers, Env<S, ? extends T> base) {
    this.completers = completers;
    this.rec = CompoundEnv.<S, T>of(base).append(this);
  }

  @Override
  public @Nullable V get(S sym) {
    V v = cache.get(sym);
    if (v != null) {
      return v;
    }
    Completer<S, T, V> completer = completers.get(sym);
    if (completer != null) {
      if (!seen.add(sym)) {
        throw new LazyBindingError(Joiner.on(" -> ").join(seen) + " -> " + sym);
      }
      v = completer.complete(rec, sym);
      seen.remove(sym);
      cache.put(sym, v);
      return v;
    }
    return null;
  }

  /** A lazy value provider which is given access to the current environment. */
  public interface Completer<S extends Symbol, T, V extends T> {
    /** Provides the value for the given symbol in the current environment. */
    @Nullable V complete(Env<S, T> env, S k);
  }

  /** Indicates that a completer tried to complete itself, possibly transitively. */
  public static class LazyBindingError extends Error {
    public LazyBindingError(String message) {
      super(message);
    }
  }
}
