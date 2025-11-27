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
import com.google.turbine.tree.Tree;
import org.jspecify.annotations.Nullable;

/** A {@link BoundClass} that corresponds to a source file being compiled. */
public class SourceBoundClass implements BoundClass {
  private final ClassSymbol sym;
  private final @Nullable ClassSymbol owner;
  private final ImmutableMap<String, ClassSymbol> children;
  private final int access;
  private final Tree.TyDecl decl;

  public SourceBoundClass(
      ClassSymbol sym,
      @Nullable ClassSymbol owner,
      ImmutableMap<String, ClassSymbol> children,
      int access,
      Tree.TyDecl decl) {
    this.sym = sym;
    this.owner = owner;
    this.children = children;
    this.access = access;
    this.decl = decl;
  }

  public Tree.TyDecl decl() {
    return decl;
  }

  @Override
  public TurbineTyKind kind() {
    return decl().tykind();
  }

  @Override
  public @Nullable ClassSymbol owner() {
    return owner;
  }

  @Override
  public int access() {
    return access;
  }

  @Override
  public ImmutableMap<String, ClassSymbol> children() {
    return children;
  }

  public ClassSymbol sym() {
    return sym;
  }
}
