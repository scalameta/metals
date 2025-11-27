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

package com.google.turbine.deps;

import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPath;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.lower.Lower.Lowered;
import com.google.turbine.model.Const;
import com.google.turbine.proto.DepsProto;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Support for Bazel jdeps dependency output. */
public final class Dependencies {
  /** Creates a jdeps proto for the current compilation. */
  public static DepsProto.Dependencies collectDeps(
      Optional<String> targetLabel, ClassPath bootclasspath, BindingResult bound, Lowered lowered) {
    DepsProto.Dependencies.Builder deps = DepsProto.Dependencies.newBuilder();
    Set<ClassSymbol> closure = superTypeClosure(bound, lowered);
    addPackageInfos(closure, bound);
    Set<String> jars = new LinkedHashSet<>();
    for (ClassSymbol sym : closure) {
      BytecodeBoundClass info = bound.classPathEnv().get(sym);
      if (info == null) {
        // the symbol wasn't loaded from the classpath
        continue;
      }
      String jarFile = info.jarFile();
      if (bootclasspath.env().get(sym) != null) {
        // bootclasspath deps are not tracked
        continue;
      }
      jars.add(jarFile);
    }
    for (String jarFile : jars) {
      deps.addDependency(
          DepsProto.Dependency.newBuilder()
              // Ensure that the path is written with forward slashes on all platforms.
              .setPath(jarFile.replace(File.separatorChar, '/'))
              .setKind(DepsProto.Dependency.Kind.EXPLICIT));
    }
    // we don't current write jdeps for failed compilations
    deps.setSuccess(true);
    if (targetLabel.isPresent()) {
      deps.setRuleLabel(targetLabel.get());
    }
    return deps.build();
  }

  private static Set<ClassSymbol> superTypeClosure(BindingResult bound, Lowered lowered) {
    Env<ClassSymbol, TypeBoundClass> env =
        CompoundEnv.<ClassSymbol, TypeBoundClass>of(new SimpleEnv<>(bound.units()))
            .append(bound.classPathEnv());
    Set<ClassSymbol> closure = new LinkedHashSet<>(lowered.symbols());
    for (ClassSymbol sym : lowered.symbols()) {
      TypeBoundClass info = env.getNonNull(sym);
      addAnnotations(closure, info.annotations());
      for (MethodInfo method : info.methods()) {
        addAnnotations(closure, method.annotations());
      }
      for (FieldInfo field : info.fields()) {
        addAnnotations(closure, field.annotations());
      }
      addSuperTypes(closure, env, info);
    }
    return closure;
  }

  private static void addAnnotations(
      Set<ClassSymbol> closure, ImmutableList<AnnoInfo> annotations) {
    for (AnnoInfo annoInfo : annotations) {
      addAnnotation(closure, annoInfo);
    }
  }

  private static void addAnnotation(Set<ClassSymbol> closure, AnnoInfo annoInfo) {
    closure.add(annoInfo.sym());
    for (Const c : annoInfo.values().values()) {
      addConst(closure, c);
    }
  }

  private static void addConst(Set<ClassSymbol> closure, Const c) {
    switch (c.kind()) {
      case ARRAY:
        for (Const e : ((Const.ArrayInitValue) c).elements()) {
          addConst(closure, e);
        }
        break;
      case CLASS_LITERAL:
        Type t = ((TurbineClassValue) c).type();
        if (t.tyKind() == Type.TyKind.CLASS_TY) {
          closure.add(((Type.ClassTy) t).sym());
        }
        break;
      case ENUM_CONSTANT:
        closure.add(((EnumConstantValue) c).sym().owner());
        break;
      case ANNOTATION:
        addAnnotation(closure, ((TurbineAnnotationValue) c).info());
        break;
      case PRIMITIVE:
        // continue below
    }
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
    addSuperTypes(closure, env, info);
  }

  private static void addSuperTypes(
      Set<ClassSymbol> closure, Env<ClassSymbol, TypeBoundClass> env, TypeBoundClass info) {
    if (info.superclass() != null) {
      addSuperTypes(closure, env, info.superclass());
    }
    for (ClassSymbol i : info.interfaces()) {
      addSuperTypes(closure, env, i);
    }
  }

  static void addPackageInfos(Set<ClassSymbol> closure, BindingResult bound) {
    Set<ClassSymbol> packages = new LinkedHashSet<>();
    for (ClassSymbol sym : closure) {
      String packageName = sym.packageName();
      if (packageName.isEmpty()) {
        continue;
      }
      packages.add(new ClassSymbol(packageName + "/package-info"));
    }
    for (ClassSymbol pkg : packages) {
      if (bound.classPathEnv().get(pkg) != null) {
        closure.add(pkg);
      }
    }
  }

  /**
   * Filters a transitive classpath to contain only the entries for direct dependencies, and the
   * types needed to compile those direct deps as reported by jdeps.
   *
   * <p>If no direct dependency information is available the full transitive classpath is returned.
   */
  public static Collection<String> reduceClasspath(
      ImmutableList<String> transitiveClasspath,
      ImmutableSet<String> directJars,
      ImmutableList<String> depsArtifacts) {
    if (directJars.isEmpty()) {
      // the compilation doesn't support strict deps (e.g. proto libraries)
      // TODO(cushon): make this a usage error
      return transitiveClasspath;
    }
    Set<String> reduced = new HashSet<>(directJars);
    for (String path : depsArtifacts) {
      DepsProto.Dependencies.Builder deps = DepsProto.Dependencies.newBuilder();
      try (InputStream is = new BufferedInputStream(Files.newInputStream(Paths.get(path)))) {
        deps.mergeFrom(is);
      } catch (IOException e) {
        throw new IOError(e);
      }
      for (DepsProto.Dependency dep : deps.build().getDependencyList()) {
        switch (dep.getKind()) {
          case EXPLICIT:
          case IMPLICIT:
            reduced.add(dep.getPath());
            break;
          case INCOMPLETE:
          case UNUSED:
            break;
        }
      }
    }
    // preserve the order of entries in the transitive classpath
    return Collections2.filter(transitiveClasspath, Predicates.in(reduced));
  }

  private Dependencies() {}
}
