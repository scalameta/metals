/*
 * Copyright 2026 Google Inc. All Rights Reserved.
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

package com.google.turbine.binder;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.CompoundTopLevelIndex;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import org.jspecify.annotations.Nullable;

/** A classpath composed of multiple classpaths with first-match-wins semantics. */
public final class CompoundClassPath implements ClassPath {

  private final ImmutableList<ClassPath> classpaths;
  private final Env<ClassSymbol, BytecodeBoundClass> env;
  private final Env<ModuleSymbol, ModuleInfo> moduleEnv;
  private final TopLevelIndex index;

  public static CompoundClassPath of(ClassPath first, ClassPath second) {
    return new CompoundClassPath(ImmutableList.of(first, second));
  }

  public CompoundClassPath(ImmutableList<ClassPath> classpaths) {
    this.classpaths = classpaths;
    CompoundEnv<ClassSymbol, BytecodeBoundClass> env = CompoundEnv.of(classpaths.get(0).env());
    CompoundEnv<ModuleSymbol, ModuleInfo> modEnv = CompoundEnv.of(classpaths.get(0).moduleEnv());
    TopLevelIndex[] indexes = new TopLevelIndex[classpaths.size()];
    for (int i = 0; i < classpaths.size(); i++) {
      if (i > 0) {
        env = env.append(classpaths.get(i).env());
        modEnv = modEnv.append(classpaths.get(i).moduleEnv());
      }
      indexes[i] = classpaths.get(i).index();
    }
    this.env = env;
    this.moduleEnv = modEnv;
    this.index = CompoundTopLevelIndex.of(indexes);
  }

  @Override
  public Env<ClassSymbol, BytecodeBoundClass> env() {
    return env;
  }

  @Override
  public Env<ModuleSymbol, ModuleInfo> moduleEnv() {
    return moduleEnv;
  }

  @Override
  public TopLevelIndex index() {
    return index;
  }

  @Override
  public @Nullable Supplier<byte[]> resource(String path) {
    for (ClassPath cp : classpaths) {
      Supplier<byte[]> res = cp.resource(path);
      if (res != null) {
        return res;
      }
    }
    return null;
  }
}
