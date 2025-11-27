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
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.type.AnnoInfo;
import org.jspecify.annotations.Nullable;

/** A bound module declaration (see JLS §7.7). */
public class ModuleInfo {

  private final String name;
  private final @Nullable String version;
  private final int flags;
  private final ImmutableList<AnnoInfo> annos;
  private final ImmutableList<RequireInfo> requires;
  private final ImmutableList<ExportInfo> exports;
  private final ImmutableList<OpenInfo> opens;
  private final ImmutableList<UseInfo> uses;
  private final ImmutableList<ProvideInfo> provides;

  public ModuleInfo(
      String name,
      @Nullable String version,
      int flags,
      ImmutableList<AnnoInfo> annos,
      ImmutableList<RequireInfo> requires,
      ImmutableList<ExportInfo> exports,
      ImmutableList<OpenInfo> opens,
      ImmutableList<UseInfo> uses,
      ImmutableList<ProvideInfo> provides) {
    this.name = name;
    this.version = version;
    this.flags = flags;
    this.annos = annos;
    this.requires = requires;
    this.exports = exports;
    this.opens = opens;
    this.uses = uses;
    this.provides = provides;
  }

  public String name() {
    return name;
  }

  public @Nullable String version() {
    return version;
  }

  public int flags() {
    return flags;
  }

  public ImmutableList<AnnoInfo> annos() {
    return annos;
  }

  public ImmutableList<RequireInfo> requires() {
    return requires;
  }

  public ImmutableList<ExportInfo> exports() {
    return exports;
  }

  public ImmutableList<OpenInfo> opens() {
    return opens;
  }

  public ImmutableList<UseInfo> uses() {
    return uses;
  }

  public ImmutableList<ProvideInfo> provides() {
    return provides;
  }

  /** A JLS §7.7.1 requires directive. */
  public static class RequireInfo {

    private final String moduleName;
    private final int flags;
    private final @Nullable String version;

    public RequireInfo(String moduleName, int flags, @Nullable String version) {
      this.moduleName = moduleName;
      this.flags = flags;
      this.version = version;
    }

    public String moduleName() {
      return moduleName;
    }

    public int flags() {
      return flags;
    }

    public @Nullable String version() {
      return version;
    }
  }

  /** A JLS §7.7.2 exports directive. */
  public static class ExportInfo {

    private final String packageName;
    private final ImmutableList<String> modules;

    public ExportInfo(String packageName, ImmutableList<String> modules) {
      this.packageName = packageName;
      this.modules = modules;
    }

    public String packageName() {
      return packageName;
    }

    public ImmutableList<String> modules() {
      return modules;
    }
  }

  /** A JLS §7.7.2 opens directive. */
  public static class OpenInfo {

    private final String packageName;
    private final ImmutableList<String> modules;

    public OpenInfo(String packageName, ImmutableList<String> modules) {
      this.packageName = packageName;
      this.modules = modules;
    }

    public String packageName() {
      return packageName;
    }

    public ImmutableList<String> modules() {
      return modules;
    }
  }

  /** A JLS §7.7.3 uses directive. */
  public static class UseInfo {

    private final ClassSymbol sym;

    public UseInfo(ClassSymbol sym) {
      this.sym = sym;
    }

    public ClassSymbol sym() {
      return sym;
    }
  }

  /** A JLS §7.7.4 provides directive. */
  public static class ProvideInfo {

    private final ClassSymbol sym;
    private final ImmutableList<ClassSymbol> impls;

    public ProvideInfo(ClassSymbol sym, ImmutableList<ClassSymbol> impls) {
      this.sym = sym;
      this.impls = impls;
    }

    public ClassSymbol sym() {
      return sym;
    }

    public ImmutableList<ClassSymbol> impls() {
      return impls;
    }
  }
}
