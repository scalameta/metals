/*
 * Copyright 2020 Google Inc. All Rights Reserved.
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

import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.PackageScope;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import org.jspecify.annotations.Nullable;

/**
 * Binds a {@link StandardJavaFileManager} to an {@link ClassPath}. This can be used to share a
 * filemanager (and associated IO costs) between turbine and javac when running both in the same
 * process.
 */
public final class FileManagerClassBinder {

  public static ClassPath adapt(StandardJavaFileManager fileManager, StandardLocation location) {
    PackageLookup packageLookup = new PackageLookup(fileManager, location);
    Env<ClassSymbol, BytecodeBoundClass> env =
        new Env<ClassSymbol, BytecodeBoundClass>() {
          @Override
          public @Nullable BytecodeBoundClass get(ClassSymbol sym) {
            return packageLookup.getPackage(this, sym.packageName()).get(sym);
          }
        };
    SimpleEnv<ModuleSymbol, ModuleInfo> moduleEnv = new SimpleEnv<>(ImmutableMap.of());
    TopLevelIndex tli = new FileManagerTopLevelIndex(env, packageLookup);
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
        return tli;
      }

      @Override
      public @Nullable Supplier<byte[]> resource(String path) {
        return packageLookup.resource(path);
      }
    };
  }

  private static class PackageLookup {

    private final Map<String, Map<ClassSymbol, BytecodeBoundClass>> packages = new HashMap<>();
    private final StandardJavaFileManager fileManager;
    private final StandardLocation location;

    private PackageLookup(StandardJavaFileManager fileManager, StandardLocation location) {
      this.fileManager = fileManager;
      this.location = location;
    }

    private ImmutableMap<ClassSymbol, BytecodeBoundClass> listPackage(
        Env<ClassSymbol, BytecodeBoundClass> env, String packageName) throws IOException {
      Map<ClassSymbol, BytecodeBoundClass> result = new HashMap<>();
      for (JavaFileObject jfo :
          fileManager.list(
              location,
              packageName.replace('/', '.'),
              EnumSet.of(JavaFileObject.Kind.CLASS),
              false)) {
        String binaryName = fileManager.inferBinaryName(location, jfo);
        ClassSymbol sym = new ClassSymbol(binaryName.replace('.', '/'));
        result.putIfAbsent(
            sym,
            new BytecodeBoundClass(
                sym,
                new Supplier<byte[]>() {
                  @Override
                  public byte[] get() {
                    try {
                      return jfo.openInputStream().readAllBytes();
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  }
                },
                env,
                /* jarFile= */ null));
      }
      return ImmutableMap.copyOf(result);
    }

    private Map<ClassSymbol, BytecodeBoundClass> getPackage(
        Env<ClassSymbol, BytecodeBoundClass> env, String key) {
      return packages.computeIfAbsent(
          key,
          k -> {
            try {
              return listPackage(env, key);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    }

    public @Nullable Supplier<byte[]> resource(String resource) {
      String dir;
      String name;
      int idx = resource.lastIndexOf('/');
      if (idx != -1) {
        dir = resource.substring(0, idx + 1);
        name = resource.substring(idx + 1, resource.length());
      } else {
        dir = "";
        name = resource;
      }
      FileObject fileObject;
      try {
        fileObject = fileManager.getFileForInput(location, dir, name);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      if (fileObject == null) {
        return null;
      }
      return new Supplier<byte[]>() {
        @Override
        public byte[] get() {
          try {
            return fileObject.openInputStream().readAllBytes();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      };
    }
  }

  private static class FileManagerTopLevelIndex implements TopLevelIndex {
    private final Env<ClassSymbol, BytecodeBoundClass> env;
    private final PackageLookup packageLookup;

    public FileManagerTopLevelIndex(
        Env<ClassSymbol, BytecodeBoundClass> env, PackageLookup packageLookup) {
      this.env = env;
      this.packageLookup = packageLookup;
    }

    @Override
    public Scope scope() {
      return new Scope() {
        @Override
        public @Nullable LookupResult lookup(LookupKey lookupKey) {
          for (int i = lookupKey.simpleNames().size(); i > 0; i--) {
            String p = Joiner.on('/').join(lookupKey.simpleNames().subList(0, i));
            ClassSymbol sym = new ClassSymbol(p);
            BytecodeBoundClass r = env.get(sym);
            if (r != null) {
              return new LookupResult(
                  sym,
                  new LookupKey(
                      lookupKey.simpleNames().subList(i - 1, lookupKey.simpleNames().size())));
            }
          }
          return null;
        }
      };
    }

    @Override
    public @Nullable PackageScope lookupPackage(Iterable<String> names) {
      String packageName = Joiner.on('/').join(names);
      Map<ClassSymbol, BytecodeBoundClass> pkg = packageLookup.getPackage(env, packageName);
      if (pkg.isEmpty()) {
        return null;
      }
      return new PackageScope() {
        @Override
        public Iterable<ClassSymbol> classes() {
          return pkg.keySet();
        }

        @Override
        public @Nullable LookupResult lookup(LookupKey lookupKey) {
          String className = lookupKey.first().value();
          if (!packageName.isEmpty()) {
            className = packageName + "/" + className;
          }
          ClassSymbol sym = new ClassSymbol(className);
          if (!pkg.containsKey(sym)) {
            return null;
          }
          return new LookupResult(sym, lookupKey);
        }
      };
    }
  }

  private FileManagerClassBinder() {}
}
