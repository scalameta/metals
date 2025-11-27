/*
 * Copyright 2018 Google Inc. All Rights Reserved.
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
import org.jspecify.annotations.Nullable;

/** A module symbol. */
@Immutable
public class ModuleSymbol implements Symbol {

  private final String name;

  public ModuleSymbol(String name) {
    this.name = name;
  }

  public String name() {
    return name;
  }

  @Override
  public Kind symKind() {
    return Kind.MODULE;
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof ModuleSymbol && name.equals(((ModuleSymbol) other).name);
  }

  public static final ModuleSymbol JAVA_BASE = new ModuleSymbol("java.base");
}
