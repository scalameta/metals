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

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoValue;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.CompUnitPreprocessor.PreprocessedCompUnit;
import com.google.turbine.binder.Processing.ProcessorInfo;
import com.google.turbine.binder.Resolve.CanonicalResolver;
import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.PackageSourceBoundModule;
import com.google.turbine.binder.bound.SourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.bound.SourceModuleInfo;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.LazyEnv;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.CanonicalSymbolResolver;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.CompoundTopLevelIndex;
import com.google.turbine.binder.lookup.ImportIndex;
import com.google.turbine.binder.lookup.ImportScope;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.lookup.SimpleTopLevelIndex;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.lookup.WildImportIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineDiagnostic;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineLog;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.tree.Tree.ModDecl;
import com.google.turbine.type.Type;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import org.jspecify.annotations.Nullable;

/** The entry point for analysis. */
public final class Binder {

  /** Binds symbols and types to the given compilation units. */
  public static @Nullable BindingResult bind(
      ImmutableList<CompUnit> units,
      ClassPath classpath,
      ClassPath bootclasspath,
      Optional<String> moduleVersion) {
    return bind(units, classpath, Processing.ProcessorInfo.empty(), bootclasspath, moduleVersion);
  }

  /** Binds symbols and types to the given compilation units. */
  public static @Nullable BindingResult bind(
      ImmutableList<CompUnit> units,
      ClassPath classpath,
      ProcessorInfo processorInfo,
      ClassPath bootclasspath,
      Optional<String> moduleVersion) {
    TurbineLog log = new TurbineLog();
    BindingResult br = bind(log, units, classpath, processorInfo, bootclasspath, moduleVersion);
    log.maybeThrow();
    return br;
  }

  /** Binds symbols and types to the given compilation units. */
  public static @Nullable BindingResult bind(
      TurbineLog log,
      ImmutableList<CompUnit> units,
      ClassPath classpath,
      ProcessorInfo processorInfo,
      ClassPath bootclasspath,
      Optional<String> moduleVersion) {
    BindingResult br;
    try {
      br =
          bind(
              log,
              units,
              /* generatedSources= */ ImmutableMap.of(),
              /* generatedClasses= */ ImmutableMap.of(),
              classpath,
              bootclasspath,
              moduleVersion);
      if (!processorInfo.processors().isEmpty() && !units.isEmpty()) {
        br =
            Processing.process(
                log, units, classpath, processorInfo, bootclasspath, br, moduleVersion);
      }
    } catch (TurbineError turbineError) {
      throw new TurbineError(
          ImmutableList.<TurbineDiagnostic>builder()
              .addAll(log.diagnostics())
              .addAll(turbineError.diagnostics())
              .build());
    }
    return br;
  }

  static BindingResult bind(
      TurbineLog log,
      ImmutableList<CompUnit> units,
      ImmutableMap<String, SourceFile> generatedSources,
      ImmutableMap<String, byte[]> generatedClasses,
      ClassPath classpath,
      ClassPath bootclasspath,
      Optional<String> moduleVersion) {
    ImmutableList<PreprocessedCompUnit> preProcessedUnits = CompUnitPreprocessor.preprocess(units);

    SimpleEnv<ClassSymbol, SourceBoundClass> ienv = bindSourceBoundClasses(log, preProcessedUnits);

    ImmutableSet<ClassSymbol> syms = ienv.asMap().keySet();

    CompoundTopLevelIndex tli =
        CompoundTopLevelIndex.of(
            SimpleTopLevelIndex.of(ienv.asMap().keySet()),
            bootclasspath.index(),
            classpath.index());

    CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv =
        CompoundEnv.of(classpath.env()).append(bootclasspath.env());

    CompoundEnv<ModuleSymbol, ModuleInfo> classPathModuleEnv =
        CompoundEnv.of(classpath.moduleEnv()).append(bootclasspath.moduleEnv());

    BindPackagesResult bindPackagesResult =
        bindPackages(log, ienv, tli, preProcessedUnits, classPathEnv);

    SimpleEnv<ClassSymbol, PackageSourceBoundClass> psenv = bindPackagesResult.classes;
    SimpleEnv<ModuleSymbol, PackageSourceBoundModule> modules = bindPackagesResult.modules;

    Env<ClassSymbol, SourceHeaderBoundClass> henv = bindHierarchy(log, syms, psenv, classPathEnv);

    Env<ClassSymbol, SourceTypeBoundClass> tenv =
        bindTypes(
            log,
            syms,
            henv,
            CompoundEnv.<ClassSymbol, HeaderBoundClass>of(classPathEnv).append(henv));

    tenv = PermitsBinder.bindPermits(syms, tenv);

    tenv =
        constants(
            syms,
            tenv,
            CompoundEnv.<ClassSymbol, TypeBoundClass>of(classPathEnv).append(tenv),
            log);
    tenv =
        disambiguateTypeAnnotations(
            syms,
            tenv,
            CompoundEnv.<ClassSymbol, TypeBoundClass>of(classPathEnv).append(tenv),
            log);
    tenv =
        canonicalizeTypes(
            syms, tenv, CompoundEnv.<ClassSymbol, TypeBoundClass>of(classPathEnv).append(tenv));

    ImmutableList<SourceModuleInfo> boundModules =
        bindModules(
            modules,
            CompoundEnv.<ClassSymbol, TypeBoundClass>of(classPathEnv).append(tenv),
            classPathModuleEnv,
            moduleVersion,
            log);

    ImmutableMap.Builder<ClassSymbol, SourceTypeBoundClass> result = ImmutableMap.builder();
    for (ClassSymbol sym : syms) {
      result.put(sym, tenv.getNonNull(sym));
    }

    return new BindingResult(
        result.buildOrThrow(),
        boundModules,
        classPathEnv,
        tli,
        generatedSources,
        generatedClasses,
        Statistics.empty());
  }

  /** Records enclosing declarations of member classes, and group classes by compilation unit. */
  static SimpleEnv<ClassSymbol, SourceBoundClass> bindSourceBoundClasses(
      TurbineLog log, ImmutableList<PreprocessedCompUnit> units) {
    SimpleEnv.Builder<ClassSymbol, SourceBoundClass> envBuilder = SimpleEnv.builder();
    for (PreprocessedCompUnit unit : units) {
      for (SourceBoundClass type : unit.types()) {
        SourceBoundClass prev = envBuilder.put(type.sym(), type);
        if (prev != null) {
          // TURBINE-DIFF START
          log.diagnostic(
              Diagnostic.Kind.ERROR,
              "Duplicate declaration of "
                  + type.sym().binaryName()
                  + " in "
                  + unit.source().path());
          // throw TurbineError.format(
          //     unit.source(), type.decl().position(), ErrorKind.DUPLICATE_DECLARATION,
          // type.sym());
          // TURBINE-DIFF END
        }
      }
    }
    return envBuilder.build();
  }

  static class BindPackagesResult {
    final SimpleEnv<ClassSymbol, PackageSourceBoundClass> classes;
    final SimpleEnv<ModuleSymbol, PackageSourceBoundModule> modules;

    BindPackagesResult(
        SimpleEnv<ClassSymbol, PackageSourceBoundClass> classes,
        SimpleEnv<ModuleSymbol, PackageSourceBoundModule> modules) {
      this.classes = classes;
      this.modules = modules;
    }
  }

  /** Initializes scopes for compilation unit and package-level lookup. */
  private static BindPackagesResult bindPackages(
      TurbineLog log,
      Env<ClassSymbol, SourceBoundClass> ienv,
      TopLevelIndex tli,
      ImmutableList<PreprocessedCompUnit> units,
      CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv) {

    SimpleEnv.Builder<ClassSymbol, PackageSourceBoundClass> env = SimpleEnv.builder();
    SimpleEnv.Builder<ModuleSymbol, PackageSourceBoundModule> modules = SimpleEnv.builder();
    Scope javaLang = tli.lookupPackage(ImmutableList.of("java", "lang"));
    if (javaLang == null) {
      // TODO(cushon): add support for diagnostics without a source position, and make this one
      // of those
      throw new IllegalArgumentException("Could not find java.lang on bootclasspath");
    }
    CompoundScope topLevel = CompoundScope.base(tli.scope()).append(javaLang);
    for (PreprocessedCompUnit unit : units) {
      ImmutableList<String> packagename =
          ImmutableList.copyOf(Splitter.on('/').omitEmptyStrings().split(unit.packageName()));
      Scope packageScope = tli.lookupPackage(packagename);
      CanonicalSymbolResolver importResolver =
          new CanonicalResolver(
              unit.packageName(),
              CompoundEnv.<ClassSymbol, BoundClass>of(classPathEnv).append(ienv));
      ImportScope importScope =
          ImportIndex.create(log.withSource(unit.source()), importResolver, tli, unit.imports());
      ImportScope wildImportScope = WildImportIndex.create(importResolver, tli, unit.imports());
      MemberImportIndex memberImports =
          new MemberImportIndex(unit.source(), importResolver, tli, unit.imports());
      ImportScope scope = ImportScope.fromScope(topLevel).append(wildImportScope);
      // Can be null if we're compiling a package-info.java for an empty package
      if (packageScope != null) {
        scope = scope.append(ImportScope.fromScope(packageScope));
      }
      scope = scope.append(importScope);
      if (unit.module().isPresent()) {
        ModDecl module = unit.module().get();
        modules.put(
            new ModuleSymbol(module.moduleName()),
            new PackageSourceBoundModule(module, scope, memberImports, unit.source()));
      }
      for (SourceBoundClass type : unit.types()) {
        env.put(type.sym(), new PackageSourceBoundClass(type, scope, memberImports, unit.source()));
      }
    }
    return new BindPackagesResult(env.build(), modules.build());
  }

  /** Binds the type hierarchy (superclasses and interfaces) for all classes in the compilation. */
  private static Env<ClassSymbol, SourceHeaderBoundClass> bindHierarchy(
      TurbineLog log,
      Iterable<ClassSymbol> syms,
      final SimpleEnv<ClassSymbol, PackageSourceBoundClass> psenv,
      CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv) {
    ImmutableMap.Builder<
            ClassSymbol, LazyEnv.Completer<ClassSymbol, HeaderBoundClass, SourceHeaderBoundClass>>
        completers = ImmutableMap.builder();
    for (ClassSymbol sym : syms) {
      completers.put(
          sym,
          new LazyEnv.Completer<ClassSymbol, HeaderBoundClass, SourceHeaderBoundClass>() {
            @Override
            public SourceHeaderBoundClass complete(
                Env<ClassSymbol, HeaderBoundClass> henv, ClassSymbol sym) {
              PackageSourceBoundClass base = psenv.getNonNull(sym);
              return HierarchyBinder.bind(log.withSource(base.source()), sym, base, henv);
            }
          });
    }
    return new LazyEnv<>(completers.buildOrThrow(), classPathEnv);
  }

  private static Env<ClassSymbol, SourceTypeBoundClass> bindTypes(
      TurbineLog log,
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceHeaderBoundClass> shenv,
      Env<ClassSymbol, HeaderBoundClass> henv) {
    SimpleEnv.Builder<ClassSymbol, SourceTypeBoundClass> builder = SimpleEnv.builder();
    for (ClassSymbol sym : syms) {
      SourceHeaderBoundClass base = shenv.getNonNull(sym);
      builder.put(sym, TypeBinder.bind(log.withSource(base.source()), henv, sym, base));
    }
    return builder.build();
  }

  private static Env<ClassSymbol, SourceTypeBoundClass> canonicalizeTypes(
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceTypeBoundClass> stenv,
      Env<ClassSymbol, TypeBoundClass> tenv) {
    SimpleEnv.Builder<ClassSymbol, SourceTypeBoundClass> builder = SimpleEnv.builder();
    for (ClassSymbol sym : syms) {
      SourceTypeBoundClass base = stenv.getNonNull(sym);
      builder.put(sym, CanonicalTypeBinder.bind(sym, base, tenv));
    }
    return builder.build();
  }

  private static ImmutableList<SourceModuleInfo> bindModules(
      SimpleEnv<ModuleSymbol, PackageSourceBoundModule> modules,
      CompoundEnv<ClassSymbol, TypeBoundClass> env,
      CompoundEnv<ModuleSymbol, ModuleInfo> moduleEnv,
      Optional<String> moduleVersion,
      TurbineLog log) {
    // Allow resolution of modules in the current compilation. Currently this is only needed for
    // version strings in requires directives.
    moduleEnv =
        moduleEnv.append(
            new Env<ModuleSymbol, ModuleInfo>() {
              @Override
              public @Nullable ModuleInfo get(ModuleSymbol sym) {
                PackageSourceBoundModule info = modules.get(sym);
                if (info != null) {
                  return new ModuleInfo(
                      info.module().moduleName(),
                      moduleVersion.orElse(null),
                      /* flags= */ 0,
                      /* annos= */ ImmutableList.of(),
                      /* requires= */ ImmutableList.of(),
                      /* exports= */ ImmutableList.of(),
                      /* opens= */ ImmutableList.of(),
                      /* uses= */ ImmutableList.of(),
                      /* provides= */ ImmutableList.of());
                }
                return null;
              }
            });
    ImmutableList.Builder<SourceModuleInfo> bound = ImmutableList.builder();
    for (PackageSourceBoundModule module : modules.asMap().values()) {
      bound.add(
          ModuleBinder.bind(
              module, env, moduleEnv, moduleVersion, log.withSource(module.source())));
    }
    return bound.build();
  }

  private static Env<ClassSymbol, SourceTypeBoundClass> constants(
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceTypeBoundClass> env,
      CompoundEnv<ClassSymbol, TypeBoundClass> baseEnv,
      TurbineLog log) {

    // Prepare to lazily evaluate constant fields in each compilation unit.
    // The laziness is necessary since constant fields can reference other
    // constant fields.
    ImmutableMap.Builder<FieldSymbol, LazyEnv.Completer<FieldSymbol, Const.Value, Const.Value>>
        completers = ImmutableMap.builder();
    for (ClassSymbol sym : syms) {
      SourceTypeBoundClass info = env.getNonNull(sym);
      for (FieldInfo field : info.fields()) {
        if (!isConst(field)) {
          continue;
        }
        completers.put(
            field.sym(),
            new LazyEnv.Completer<FieldSymbol, Const.Value, Const.Value>() {
              @Override
              public Const.@Nullable Value complete(
                  Env<FieldSymbol, Const.Value> env1, FieldSymbol k) {
                try {
                  return new ConstEvaluator(
                          sym,
                          sym,
                          info.memberImports(),
                          info.source(),
                          info.scope(),
                          env1,
                          baseEnv,
                          log.withSource(info.source()))
                      .evalFieldInitializer(
                          // we're processing fields bound from sources in the compilation
                          requireNonNull(field.decl()).init().get(), field.type());
                } catch (LazyEnv.LazyBindingError e) {
                  // fields initializers are allowed to reference the field being initialized,
                  // but if they do they aren't constants
                  return null;
                  // TURBINE-DIFF START
                } catch (TurbineError e) {
                  return null;
                }
                // TURBINE-DIFF END
              }
            });
      }
    }

    // Create an environment of constant field values that combines
    // lazily evaluated fields in the current compilation unit with
    // constant fields in the classpath (which don't require evaluation).
    Env<FieldSymbol, Const.Value> constenv =
        new LazyEnv<>(
            completers.buildOrThrow(), SimpleEnv.<FieldSymbol, Const.Value>builder().build());

    SimpleEnv.Builder<ClassSymbol, SourceTypeBoundClass> builder = SimpleEnv.builder();
    for (ClassSymbol sym : syms) {
      SourceTypeBoundClass base = env.getNonNull(sym);
      try {
        builder.put(
            sym,
            new ConstBinder(constenv, sym, baseEnv, base, log.withSource(base.source())).bind());
        // TURBINE-DIFF START
      } catch (TurbineError e) {
        log.diagnostic(
            Diagnostic.Kind.ERROR,
            "Error binding constant for class " + sym.binaryName() + " in " + base.source().path());
        builder.put(sym, SourceTypeBoundClass.EMPTY);
        // TURBINE-DIFF END
      }
    }
    return builder.build();
  }

  static boolean isConst(FieldInfo field) {
    if ((field.access() & TurbineFlag.ACC_FINAL) == 0) {
      return false;
    }
    if (field.decl() == null) {
      return false;
    }
    final Optional<Tree.Expression> init = field.decl().init();
    if (!init.isPresent()) {
      return false;
    }
    switch (field.type().tyKind()) {
      case PRIM_TY:
        break;
      case CLASS_TY:
        if (((Type.ClassTy) field.type()).sym().equals(ClassSymbol.STRING)) {
          break;
        }
      // fall through
      default:
        return false;
    }
    return true;
  }

  /**
   * Disambiguate annotations on field types and method return types that could be declaration or
   * type annotations.
   */
  private static Env<ClassSymbol, SourceTypeBoundClass> disambiguateTypeAnnotations(
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceTypeBoundClass> stenv,
      Env<ClassSymbol, TypeBoundClass> tenv,
      TurbineLog log) {
    SimpleEnv.Builder<ClassSymbol, SourceTypeBoundClass> builder = SimpleEnv.builder();
    for (ClassSymbol sym : syms) {
      SourceTypeBoundClass base = stenv.getNonNull(sym);
      builder.put(sym, DisambiguateTypeAnnotations.bind(base, tenv, log));
    }
    return builder.build();
  }

  /** Statistics about annotation processing. */
  @AutoValue
  public abstract static class Statistics {

    /**
     * The total elapsed time spent in {@link Processor#init} and {@link Processor#process} across
     * all rounds for each annotation processor.
     */
    public abstract ImmutableMap<String, Duration> processingTime();

    /**
     * Serialized protos containing processor-specific metrics. Currently only supported for Dagger.
     */
    public abstract ImmutableMap<String, byte[]> processorMetrics();

    public static Statistics create(
        ImmutableMap<String, Duration> processingTime,
        ImmutableMap<String, byte[]> processorMetrics) {
      return new AutoValue_Binder_Statistics(processingTime, processorMetrics);
    }

    public static Statistics empty() {
      return create(ImmutableMap.of(), ImmutableMap.of());
    }
  }

  /** The result of binding: bound nodes for sources in the compilation, and the classpath. */
  public static class BindingResult {
    private final ImmutableMap<ClassSymbol, SourceTypeBoundClass> units;
    private final ImmutableList<SourceModuleInfo> modules;
    private final CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv;
    private final TopLevelIndex tli;
    private final ImmutableMap<String, SourceFile> generatedSources;
    private final ImmutableMap<String, byte[]> generatedClasses;
    private final Statistics statistics;

    public BindingResult(
        ImmutableMap<ClassSymbol, SourceTypeBoundClass> units,
        ImmutableList<SourceModuleInfo> modules,
        CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv,
        TopLevelIndex tli,
        ImmutableMap<String, SourceFile> generatedSources,
        ImmutableMap<String, byte[]> generatedClasses,
        Statistics statistics) {
      this.units = units;
      this.modules = modules;
      this.classPathEnv = classPathEnv;
      this.tli = tli;
      this.generatedSources = generatedSources;
      this.generatedClasses = generatedClasses;
      this.statistics = statistics;
    }

    /** Bound nodes for sources in the compilation. */
    public ImmutableMap<ClassSymbol, SourceTypeBoundClass> units() {
      return units;
    }

    public ImmutableList<SourceModuleInfo> modules() {
      return modules;
    }

    /** The classpath. */
    public CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv() {
      return classPathEnv;
    }

    public TopLevelIndex tli() {
      return tli;
    }

    public ImmutableMap<String, SourceFile> generatedSources() {
      return generatedSources;
    }

    public ImmutableMap<String, byte[]> generatedClasses() {
      return generatedClasses;
    }

    public Statistics statistics() {
      return statistics;
    }

    public BindingResult withGeneratedClasses(ImmutableMap<String, byte[]> generatedClasses) {
      return new BindingResult(
          units, modules, classPathEnv, tli, generatedSources, generatedClasses, statistics);
    }

    public BindingResult withGeneratedSources(ImmutableMap<String, SourceFile> generatedSources) {
      return new BindingResult(
          units, modules, classPathEnv, tli, generatedSources, generatedClasses, statistics);
    }

    public BindingResult withStatistics(Statistics statistics) {
      return new BindingResult(
          units, modules, classPathEnv, tli, generatedSources, generatedClasses, statistics);
    }
  }

  private Binder() {}
}
