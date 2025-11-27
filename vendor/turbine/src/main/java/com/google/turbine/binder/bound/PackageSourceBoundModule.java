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

package com.google.turbine.binder.bound;

import com.google.turbine.binder.lookup.ImportScope;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.tree.Tree.ModDecl;

/** Wraps a {@link ModDecl} with lookup scopes for the current compilation unit and package. */
public class PackageSourceBoundModule {

  private final ModDecl module;
  private final ImportScope scope;
  private final MemberImportIndex memberImports;
  private final SourceFile source;

  public PackageSourceBoundModule(
      ModDecl module, ImportScope scope, MemberImportIndex memberImports, SourceFile source) {
    this.module = module;
    this.scope = scope;
    this.memberImports = memberImports;
    this.source = source;
  }

  public ModDecl module() {
    return module;
  }

  public ImportScope scope() {
    return scope;
  }

  public MemberImportIndex memberImports() {
    return memberImports;
  }

  public SourceFile source() {
    return source;
  }
}
