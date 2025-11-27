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

package com.google.turbine.binder.bound;

import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.TurbineTyKind;
import org.jspecify.annotations.Nullable;

/**
 * The initial bound tree representation.
 *
 * <p>The interfaces for bound trees generally have two implementations: one for the sources being
 * analyzed, and one for symbols backed by classes on the classpath.
 */
public interface BoundClass {
  /** The kind of declared type (class, interface, enum, or annotation). */
  TurbineTyKind kind();

  /** The enclosing declaration for member types, or {@code null} for top-level declarations. */
  @Nullable ClassSymbol owner();

  /** Class access bits (see JVMS table 4.1). */
  int access();

  /** Member type declarations, by simple name. */
  ImmutableMap<String, ClassSymbol> children();
}
