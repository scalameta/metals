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

import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.Symbol;
import org.jspecify.annotations.Nullable;

/**
 * An environment that maps {@link Symbol}s {@code S} to bound nodes {@code V}.
 *
 * <p>For example, {@code BoundClass} represents superclasses as a {@link ClassSymbol}, which only
 * contains the binary name of the type. To get the {@code BoundClass} for that supertype, an {@code
 * Env<BoundClass>} is used.
 *
 * <p>The indirection through env makes it possible to represent a graph with cycles using immutable
 * nodes, and makes it easy to reason about the information produced and consumed by each pass.
 *
 * <p>TODO(cushon): keep an eye on the cost of indirections, and consider caching lookups in bound
 * nodes if it looks like it would make a difference.
 */
public interface Env<S extends Symbol, V> {
  /** Returns the information associated with the given symbol in this environment. */
  @Nullable V get(S sym);

  default V getNonNull(S sym) {
    V result = get(sym);
    if (result == null) {
      throw new NullPointerException(sym.toString());
    }
    return result;
  }
}
