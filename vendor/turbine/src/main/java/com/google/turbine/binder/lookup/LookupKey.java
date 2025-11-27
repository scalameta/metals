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

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import com.google.turbine.tree.Tree.Ident;
import java.util.NoSuchElementException;

/**
 * The key for a qualified name lookup; effectively an immutable iterator over parts of a qualified
 * name.
 */
@Immutable
public class LookupKey {
  private final ImmutableList<Ident> simpleNames;

  public LookupKey(ImmutableList<Ident> simpleNames) {
    this.simpleNames = simpleNames;
  }

  /** The first simple name in the qualified type name. */
  public Ident first() {
    return simpleNames.get(0);
  }

  boolean hasNext() {
    return simpleNames.size() > 1;
  }

  /**
   * A {@link LookupKey} with the first simple name removed.
   *
   * <p>Used when resolving qualified top-level names, e.g. {@code java.util.HashMap.Entry} might be
   * resolved in the following stages, ending with a resolved class and a {@link LookupKey} for any
   * remaining nested type names (which may not be canonical).
   *
   * <ul>
   *   <li>{@code ((PACKAGE java) (KEY util.HashMap.Entry))}
   *   <li>{@code ((PACKAGE java.util) (KEY HashMap.Entry))}
   *   <li>{@code ((CLASS java.util.HashMap) (KEY Entry))}
   * </ul>
   */
  public LookupKey rest() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    return new LookupKey(simpleNames.subList(1, simpleNames.size()));
  }

  /** The simple names of the type. */
  public ImmutableList<Ident> simpleNames() {
    return simpleNames;
  }
}
