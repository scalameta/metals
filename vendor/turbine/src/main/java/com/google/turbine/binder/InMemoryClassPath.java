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
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.SimpleTopLevelIndex;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** An in-memory classpath backed by a fixed set of classfiles. */
public final class InMemoryClassPath implements ClassPath {

  private final Env<ClassSymbol, BytecodeBoundClass> env;
  private final TopLevelIndex index;

  public static InMemoryClassPath create(ImmutableMap<String, byte[]> classes, String jarTag) {
    return new InMemoryClassPath(classes, jarTag);
  }

  private InMemoryClassPath(ImmutableMap<String, byte[]> classes, String jarTag) {
    Map<ClassSymbol, Supplier<BytecodeBoundClass>> map = new HashMap<>();
    Env<ClassSymbol, BytecodeBoundClass> env =
        new Env<ClassSymbol, BytecodeBoundClass>() {
          @Override
          public @Nullable BytecodeBoundClass get(ClassSymbol sym) {
            Supplier<BytecodeBoundClass> supplier = map.get(sym);
            return supplier == null ? null : supplier.get();
          }
        };

    for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
      ClassSymbol sym = new ClassSymbol(entry.getKey());
      Supplier<byte[]> bytes = Suppliers.ofInstance(entry.getValue());
      map.put(sym, Suppliers.memoize(() -> new BytecodeBoundClass(sym, bytes, env, jarTag)));
    }

    this.env = env;
    this.index = SimpleTopLevelIndex.of(map.keySet());
  }

  @Override
  public Env<ClassSymbol, BytecodeBoundClass> env() {
    return env;
  }

  @Override
  public Env<ModuleSymbol, ModuleInfo> moduleEnv() {
    return module -> null;
  }

  @Override
  public TopLevelIndex index() {
    return index;
  }

  @Override
  public @Nullable Supplier<byte[]> resource(String path) {
    return null;
  }
}
