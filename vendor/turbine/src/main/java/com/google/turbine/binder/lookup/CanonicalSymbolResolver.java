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

import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.tree.Tree;
import org.jspecify.annotations.Nullable;

/** Canonical type resolution. Breaks a circular dependency between binding and import handling. */
public interface CanonicalSymbolResolver extends ImportScope.ResolveFunction {
  /** Resolves a single member type of the given symbol by canonical name. */
  @Override
  @Nullable ClassSymbol resolveOne(ClassSymbol sym, Tree.Ident bit);

  /** Returns true if the given symbol is visible from the current package. */
  boolean visible(ClassSymbol sym);
}
