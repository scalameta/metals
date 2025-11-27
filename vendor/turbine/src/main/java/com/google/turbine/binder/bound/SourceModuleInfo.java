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

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.ModuleInfo.ExportInfo;
import com.google.turbine.binder.bound.ModuleInfo.OpenInfo;
import com.google.turbine.binder.bound.ModuleInfo.ProvideInfo;
import com.google.turbine.binder.bound.ModuleInfo.RequireInfo;
import com.google.turbine.binder.bound.ModuleInfo.UseInfo;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.type.AnnoInfo;
import org.jspecify.annotations.Nullable;

/** A {@link ModuleInfo} that corresponds to a source file being compiled. */
public class SourceModuleInfo extends ModuleInfo {

  private final SourceFile source;

  public SourceModuleInfo(
      String name,
      @Nullable String version,
      int flags,
      ImmutableList<AnnoInfo> annos,
      ImmutableList<RequireInfo> requires,
      ImmutableList<ExportInfo> exports,
      ImmutableList<OpenInfo> opens,
      ImmutableList<UseInfo> uses,
      ImmutableList<ProvideInfo> provides,
      SourceFile source) {
    super(name, version, flags, annos, requires, exports, opens, uses, provides);
    this.source = source;
  }

  public SourceFile source() {
    return source;
  }
}
