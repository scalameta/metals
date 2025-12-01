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

package com.google.turbine.binder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bound.ModuleInfo.ExportInfo;
import com.google.turbine.binder.bound.ModuleInfo.OpenInfo;
import com.google.turbine.binder.bound.ModuleInfo.ProvideInfo;
import com.google.turbine.binder.bound.ModuleInfo.RequireInfo;
import com.google.turbine.binder.bound.ModuleInfo.UseInfo;
import com.google.turbine.binder.bound.PackageSourceBoundModule;
import com.google.turbine.binder.bound.SourceModuleInfo;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.diag.TurbineLog.TurbineLogWithSource;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.tree.Tree.ModDirective;
import com.google.turbine.tree.Tree.ModExports;
import com.google.turbine.tree.Tree.ModOpens;
import com.google.turbine.tree.Tree.ModProvides;
import com.google.turbine.tree.Tree.ModRequires;
import com.google.turbine.tree.Tree.ModUses;
import com.google.turbine.tree.TurbineModifier;
import com.google.turbine.type.AnnoInfo;
import java.util.Optional;

/** Binding pass for modules. */
public class ModuleBinder {

  public static SourceModuleInfo bind(
      PackageSourceBoundModule module,
      CompoundEnv<ClassSymbol, TypeBoundClass> env,
      Env<ModuleSymbol, ModuleInfo> moduleEnv,
      Optional<String> moduleVersion,
      TurbineLogWithSource log) {
    return new ModuleBinder(module, env, moduleEnv, moduleVersion, log).bind();
  }

  private final PackageSourceBoundModule module;
  private final CompoundEnv<ClassSymbol, TypeBoundClass> env;
  private final Env<ModuleSymbol, ModuleInfo> moduleEnv;
  private final Optional<String> moduleVersion;
  private final CompoundScope scope;
  private final TurbineLogWithSource log;

  public ModuleBinder(
      PackageSourceBoundModule module,
      CompoundEnv<ClassSymbol, TypeBoundClass> env,
      Env<ModuleSymbol, ModuleInfo> moduleEnv,
      Optional<String> moduleVersion,
      TurbineLogWithSource log) {
    this.module = module;
    this.env = env;
    this.moduleEnv = moduleEnv;
    this.moduleVersion = moduleVersion;
    this.log = log;
    this.scope = module.scope().toScope(Resolve.resolveFunction(env, /* origin= */ null));
  }

  private SourceModuleInfo bind() {
    // bind annotations; constant fields are already bound
    ConstEvaluator constEvaluator =
        new ConstEvaluator(
            /* origin= */ null,
            /* owner= */ null,
            module.memberImports(),
            module.source(),
            scope,
            /* values= */ new SimpleEnv<>(ImmutableMap.of()),
            env,
            log);
    ImmutableList.Builder<AnnoInfo> annoInfos = ImmutableList.builder();
    for (Tree.Anno annoTree : module.module().annos()) {
      ClassSymbol sym = resolve(annoTree.position(), annoTree.name());
      annoInfos.add(new AnnoInfo(module.source(), sym, annoTree, ImmutableMap.of()));
    }
    ImmutableList<AnnoInfo> annos = constEvaluator.evaluateAnnotations(annoInfos.build());

    int flags = module.module().open() ? TurbineFlag.ACC_OPEN : 0;

    // bind directives
    ImmutableList.Builder<ModuleInfo.RequireInfo> requires = ImmutableList.builder();
    ImmutableList.Builder<ModuleInfo.ExportInfo> exports = ImmutableList.builder();
    ImmutableList.Builder<ModuleInfo.OpenInfo> opens = ImmutableList.builder();
    ImmutableList.Builder<ModuleInfo.UseInfo> uses = ImmutableList.builder();
    ImmutableList.Builder<ModuleInfo.ProvideInfo> provides = ImmutableList.builder();
    boolean requiresJavaBase = false;
    for (ModDirective directive : module.module().directives()) {
      switch (directive.directiveKind()) {
        case REQUIRES:
          {
            ModRequires require = (ModRequires) directive;
            requiresJavaBase |= require.moduleName().equals(ModuleSymbol.JAVA_BASE.name());
            requires.add(bindRequires(require));
            break;
          }
        case EXPORTS:
          exports.add(bindExports((ModExports) directive));
          break;
        case OPENS:
          opens.add(bindOpens((ModOpens) directive));
          break;
        case USES:
          uses.add(bindUses((ModUses) directive));
          break;
        case PROVIDES:
          provides.add(bindProvides((ModProvides) directive));
          break;
      }
    }
    if (!requiresJavaBase && !module.module().moduleName().equals(ModuleSymbol.JAVA_BASE.name())) {
      // Everything requires java.base, either explicitly or implicitly.
      ModuleInfo javaBaseModule = moduleEnv.get(ModuleSymbol.JAVA_BASE);
      // Tolerate a missing java.base module, e.g. when compiling a module against a non-modular
      // bootclasspath, and just omit the version below.
      String javaBaseVersion = javaBaseModule != null ? javaBaseModule.version() : null;
      requires =
          ImmutableList.<RequireInfo>builder()
              .add(
                  new RequireInfo(
                      ModuleSymbol.JAVA_BASE.name(), TurbineFlag.ACC_MANDATED, javaBaseVersion))
              .addAll(requires.build());
    }

    return new SourceModuleInfo(
        module.module().moduleName(),
        moduleVersion.orElse(null),
        flags,
        annos,
        requires.build(),
        exports.build(),
        opens.build(),
        uses.build(),
        provides.build(),
        module.source());
  }

  private RequireInfo bindRequires(ModRequires directive) {
    String moduleName = directive.moduleName();
    int flags = 0;
    for (TurbineModifier mod : directive.mods()) {
      switch (mod) {
        case TRANSITIVE:
          flags |= mod.flag();
          break;
        case STATIC:
          // the 'static' modifier on requires translates to ACC_STATIC_PHASE, not ACC_STATIC
          flags |= TurbineFlag.ACC_STATIC_PHASE;
          break;
        default:
          throw new AssertionError(mod);
      }
    }
    ModuleInfo requires = moduleEnv.get(new ModuleSymbol(moduleName));
    return new RequireInfo(moduleName, flags, requires != null ? requires.version() : null);
  }

  private static ExportInfo bindExports(ModExports directive) {
    return new ExportInfo(directive.packageName(), directive.moduleNames());
  }

  private static OpenInfo bindOpens(ModOpens directive) {
    return new OpenInfo(directive.packageName(), directive.moduleNames());
  }

  private UseInfo bindUses(ModUses directive) {
    return new UseInfo(resolve(directive.position(), directive.typeName()));
  }

  private ProvideInfo bindProvides(ModProvides directive) {
    ClassSymbol sym = resolve(directive.position(), directive.typeName());
    ImmutableList.Builder<ClassSymbol> impls = ImmutableList.builder();
    for (ImmutableList<Ident> impl : directive.implNames()) {
      impls.add(resolve(directive.position(), impl));
    }
    return new ProvideInfo(sym, impls.build());
  }

  /* Resolves qualified class names. */
  private ClassSymbol resolve(int pos, ImmutableList<Tree.Ident> simpleNames) {
    LookupKey key = new LookupKey(simpleNames);
    LookupResult result = scope.lookup(key);
    if (result == null) {
      // TURBINE-DIFF START
      return ClassSymbol.ERROR;
      // throw error(
      //     ErrorKind.SYMBOL_NOT_FOUND, pos, new ClassSymbol(Joiner.on('/').join(simpleNames)));
      // TURBINE-DIFF END
    }
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (Tree.Ident name : result.remaining()) {
      ClassSymbol next = Resolve.resolve(env, /* origin= */ null, sym, name);
      if (next == null) {
        // TURBINE-DIFF START
        return ClassSymbol.ERROR;
        // throw error(
        //     ErrorKind.SYMBOL_NOT_FOUND, pos, new ClassSymbol(sym.binaryName() + '$' + name));
        // TURBINE-DIFF END
      }
      sym = next;
    }
    return sym;
  }

  private TurbineError error(ErrorKind kind, int pos, Object... args) {
    return TurbineError.format(module.source(), pos, kind, args);
  }
}
