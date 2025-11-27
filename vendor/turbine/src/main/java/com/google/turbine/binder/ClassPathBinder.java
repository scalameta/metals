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

package com.google.turbine.binder;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bytecode.BytecodeBinder;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.SimpleTopLevelIndex;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.zip.Zip;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;

/** Sets up an environment for symbols on the classpath. */
public final class ClassPathBinder {

  /**
   * The prefix for repackaged transitive dependencies; see {@link
   * com.google.turbine.deps.Transitive}.
   */
  public static final String TRANSITIVE_PREFIX = "META-INF/TRANSITIVE/";

  /**
   * The suffix for repackaged transitive dependencies; see {@link
   * com.google.turbine.deps.Transitive}.
   */
  public static final String TRANSITIVE_SUFFIX = ".turbine";

  private static final Attributes.Name ORIGINAL_JAR_PATH = new Attributes.Name("Original-Jar-Path");

  /** Creates an environment containing symbols in the given classpath. */
  public static ClassPath bindClasspath(Collection<Path> paths) throws IOException {
    Map<ClassSymbol, Supplier<BytecodeBoundClass>> transitive = new LinkedHashMap<>();
    Map<ClassSymbol, Supplier<BytecodeBoundClass>> map = new HashMap<>();
    Map<ModuleSymbol, ModuleInfo> modules = new HashMap<>();
    Map<String, Supplier<byte[]>> resources = new HashMap<>();
    Env<ClassSymbol, BytecodeBoundClass> env =
        new Env<ClassSymbol, BytecodeBoundClass>() {
          @Override
          public @Nullable BytecodeBoundClass get(ClassSymbol sym) {
            Supplier<BytecodeBoundClass> supplier = map.get(sym);
            return supplier == null ? null : supplier.get();
          }
        };
    for (Path path : paths) {
      try {
        bindJar(path, map, modules, env, transitive, resources);
      } catch (IOException e) {
        throw new IOException("error reading " + path, e);
      }
    }
    for (Map.Entry<ClassSymbol, Supplier<BytecodeBoundClass>> entry : transitive.entrySet()) {
      ClassSymbol symbol = entry.getKey();
      map.putIfAbsent(symbol, entry.getValue());
    }
    SimpleEnv<ModuleSymbol, ModuleInfo> moduleEnv = new SimpleEnv<>(ImmutableMap.copyOf(modules));
    TopLevelIndex index = SimpleTopLevelIndex.of(map.keySet());
    return new ClassPath() {
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
        return resources.get(path);
      }
    };
  }

  private static void bindJar(
      Path path,
      Map<ClassSymbol, Supplier<BytecodeBoundClass>> env,
      Map<ModuleSymbol, ModuleInfo> modules,
      Env<ClassSymbol, BytecodeBoundClass> benv,
      Map<ClassSymbol, Supplier<BytecodeBoundClass>> transitive,
      Map<String, Supplier<byte[]>> resources)
      throws IOException {
    // TODO(cushon): don't leak file descriptors
    for (Zip.Entry ze : new Zip.ZipIterable(path)) {
      String name = ze.name();
      if (name.equals("META-INF/MANIFEST.MF")) {
        Manifest manifest = new Manifest(new ByteArrayInputStream(ze.data()));
        // If the classpath jar is a header jar, look up the name of the corresponding regular
        // jar. This path will end up in jdeps and be used for classpath reduced of downstream
        // javac invocations, which need the path of regular compile jar and not the header jar.
        String originalJarPath = (String) manifest.getMainAttributes().get(ORIGINAL_JAR_PATH);
        if (originalJarPath != null) {
          path = Path.of(originalJarPath);
        }
        continue;
      }
      if (name.startsWith(TRANSITIVE_PREFIX)) {
        if (!name.endsWith(TRANSITIVE_SUFFIX)) {
          continue;
        }
        ClassSymbol sym =
            new ClassSymbol(
                name.substring(
                    TRANSITIVE_PREFIX.length(), name.length() - TRANSITIVE_SUFFIX.length()));
        transitive.putIfAbsent(sym, BytecodeBoundClass.lazy(sym, ze, benv, path));
        continue;
      }
      if (!name.endsWith(".class")) {
        resources.put(name, ze);
        continue;
      }
      if (name.substring(name.lastIndexOf('/') + 1).equals("module-info.class")) {
        ModuleInfo moduleInfo = BytecodeBinder.bindModuleInfo(path.toString(), ze);
        modules.put(new ModuleSymbol(moduleInfo.name()), moduleInfo);
        continue;
      }
      ClassSymbol sym = new ClassSymbol(name.substring(0, name.length() - ".class".length()));
      env.putIfAbsent(sym, BytecodeBoundClass.lazy(sym, ze, benv, path));
    }
  }

  private ClassPathBinder() {}
}
