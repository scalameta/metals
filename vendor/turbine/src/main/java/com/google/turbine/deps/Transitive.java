/*
 * Copyright 2017 Google Inc. All Rights Reserved.
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

package com.google.turbine.deps;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPath;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassFile.FieldInfo;
import com.google.turbine.bytecode.ClassFile.InnerClass;
import com.google.turbine.bytecode.ClassReader;
import com.google.turbine.bytecode.ClassWriter;
import com.google.turbine.model.TurbineFlag;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Collects the minimal compile-time API for symbols in the supertype closure of compiled classes.
 * This allows header compilations to be performed against a classpath containing only direct
 * dependencies and no transitive dependencies.
 */
public final class Transitive {

  public static ImmutableMap<String, byte[]> collectDeps(
      ClassPath bootClassPath, BindingResult bound) {
    ImmutableMap.Builder<String, byte[]> transitive = ImmutableMap.builder();
    Set<ClassSymbol> closure = superClosure(bound);
    Dependencies.addPackageInfos(closure, bound);
    for (ClassSymbol sym : closure) {
      BytecodeBoundClass info = bound.classPathEnv().get(sym);
      if (info == null) {
        // the symbol wasn't loaded from the classpath
        continue;
      }
      if (bootClassPath.env().get(sym) != null) {
        // don't export symbols loaded from the bootclasspath
        continue;
      }
      transitive.put(
          sym.binaryName(), ClassWriter.writeClass(trimClass(info.classFile(), info.jarFile())));
    }
    return transitive.buildOrThrow();
  }

  public static ImmutableMap<String, byte[]> trimOutput(ImmutableMap<String, byte[]> lowered) {
    ImmutableMap.Builder<String, byte[]> trimmed = ImmutableMap.builder();
    for (Map.Entry<String, byte[]> sym : lowered.entrySet()) {
      if (sym.getKey().equals("module-info")) {
        // Module attributes get trimmed which make module-infos invalid, and turbine doesn't need
        // modules anyways, so just drop them for now.
        continue;
      }
      trimmed.put(
          sym.getKey(), ClassWriter.writeClass(trimClass(ClassReader.read(sym.getValue()), null)));
    }
    return trimmed.buildOrThrow();
  }

  /**
   * Removes information from repackaged classes that will not be needed by upstream compilations.
   */
  public static ClassFile trimClass(ClassFile cf, @Nullable String jarFile) {
    // drop non-constant fields
    ImmutableList.Builder<FieldInfo> fields = ImmutableList.builder();
    for (FieldInfo f : cf.fields()) {
      if (keepField(f)) {
        fields.add(f);
      }
    }
    // Remove InnerClass attributes that are unnecessary after pruning the types they refer to.
    // To do this for javac, we would have to scan all remaining signatures and preserve attributes
    // for reachable inner classes, but turbine only needs the attributes for the immediate
    // children or parent of the current class.
    ImmutableList.Builder<InnerClass> innerClasses = ImmutableList.builder();
    for (InnerClass i : cf.innerClasses()) {
      if (i.innerClass().equals(cf.name()) || i.outerClass().equals(cf.name())) {
        innerClasses.add(i);
      }
    }
    // Include the original jar file name when repackaging transitive deps. If the same transitive
    // dep is repackaged more than once, keep the original name.
    String transitiveJar = cf.transitiveJar();
    if (transitiveJar == null) {
      transitiveJar = jarFile;
    }
    return new ClassFile(
        cf.access(),
        cf.majorVersion(),
        cf.name(),
        cf.signature(),
        cf.superName(),
        cf.interfaces(),
        cf.permits(),
        // drop methods, except for annotations where we need to resolve key/value information
        (cf.access() & TurbineFlag.ACC_ANNOTATION) == TurbineFlag.ACC_ANNOTATION
            ? cf.methods()
            : ImmutableList.of(),
        fields.build(),
        // unnecessary annotations are dropped during class reading, the only remaining ones are
        // well-known @interface meta-annotations (e.g. @Retention, etc.)
        cf.annotations(),
        innerClasses.build(),
        cf.typeAnnotations(),
        /* module= */ null,
        /* nestHost= */ null,
        /* nestMembers= */ ImmutableList.of(),
        /* record= */ null,
        /* transitiveJar= */ transitiveJar);
  }

  private static boolean keepField(FieldInfo f) {
    if (f.value() != null) {
      // keep compile-time constant fields
      return true;
    }
    if ((f.access() & TurbineFlag.ACC_ENUM) == TurbineFlag.ACC_ENUM) {
      // keep enum constants, which can be used as annotation values
      return true;
    }
    return false;
  }

  private static Set<ClassSymbol> superClosure(BindingResult bound) {
    Env<ClassSymbol, TypeBoundClass> env =
        CompoundEnv.<ClassSymbol, TypeBoundClass>of(new SimpleEnv<>(bound.units()))
            .append(bound.classPathEnv());
    Set<ClassSymbol> closure = new LinkedHashSet<>();
    for (ClassSymbol sym : bound.units().keySet()) {
      addSuperTypes(closure, env, sym);
    }
    Set<ClassSymbol> directChildren = new LinkedHashSet<>();
    for (ClassSymbol sym : closure) {
      TypeBoundClass info = env.get(sym);
      if (info != null) {
        directChildren.addAll(info.children().values());
      }
    }
    closure.addAll(directChildren);
    return closure;
  }

  private static void addSuperTypes(
      Set<ClassSymbol> closure, Env<ClassSymbol, TypeBoundClass> env, ClassSymbol sym) {
    if (!closure.add(sym)) {
      return;
    }
    TypeBoundClass info = env.get(sym);
    if (info == null) {
      return;
    }
    if (info.superclass() != null) {
      addSuperTypes(closure, env, info.superclass());
    }
    for (ClassSymbol i : info.interfaces()) {
      addSuperTypes(closure, env, i);
    }
  }

  private Transitive() {}
}
