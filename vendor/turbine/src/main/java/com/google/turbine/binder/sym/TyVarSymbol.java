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

package com.google.turbine.binder.sym;

import com.google.errorprone.annotations.Immutable;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A type variable symbol. */
@Immutable
public class TyVarSymbol implements Symbol {

  private final Symbol owner;
  private final String name;

  public TyVarSymbol(Symbol owner, String name) {
    this.owner = owner;
    this.name = name;
  }

  /** The type variable name. */
  public String name() {
    return name;
  }

  /** The class or method that declares this type variable. */
  public Symbol owner() {
    return owner;
  }

  @Override
  public Kind symKind() {
    return Kind.TY_PARAM;
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, owner);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof TyVarSymbol)) {
      return false;
    }
    TyVarSymbol other = (TyVarSymbol) obj;
    return name.equals(other.name()) && owner().equals(other.owner());
  }

  @Override
  public String toString() {
    return name;
  }
}
