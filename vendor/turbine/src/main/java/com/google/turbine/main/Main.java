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

package com.google.turbine.main;

import static com.google.common.base.StandardSystemProperty.JAVA_SPECIFICATION_VERSION;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.common.io.MoreFiles;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.Binder.Statistics;
import com.google.turbine.binder.ClassPath;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.CtSymClassBinder;
import com.google.turbine.binder.JimageClassBinder;
import com.google.turbine.binder.Processing;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.deps.Dependencies;
import com.google.turbine.deps.Transitive;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.lower.Lower;
import com.google.turbine.lower.Lower.Lowered;
import com.google.turbine.options.TurbineOptions;
import com.google.turbine.options.TurbineOptions.ReducedClasspathMode;
import com.google.turbine.options.TurbineOptionsParser;
import com.google.turbine.parse.Parser;
import com.google.turbine.proto.DepsProto;
import com.google.turbine.proto.ManifestProto;
import com.google.turbine.proto.ManifestProto.CompilationUnit;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.zip.Zip;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/** Main entry point for the turbine CLI. */
public final class Main {

  private static final int BUFFER_SIZE = 65536;

  // These attributes are used by JavaBuilder, Turbine, and ijar.
  // They must all be kept in sync.
  static final String MANIFEST_DIR = "META-INF/";
  static final String MANIFEST_NAME = JarFile.MANIFEST_NAME;
  static final Attributes.Name TARGET_LABEL = new Attributes.Name("Target-Label");
  static final Attributes.Name INJECTING_RULE_KIND = new Attributes.Name("Injecting-Rule-Kind");
  static final Attributes.Name ORIGINAL_JAR_PATH = new Attributes.Name("Original-Jar-Path");

  public static void main(String[] args) {
    boolean ok;
    try {
      compile(args);
      ok = true;
    } catch (TurbineError | UsageException e) {
      System.err.println(e.getMessage());
      ok = false;
    } catch (Throwable turbineCrash) {
      turbineCrash.printStackTrace();
      ok = false;
    }
    System.exit(ok ? 0 : 1);
  }

  /** The result of a turbine invocation. */
  @AutoValue
  public abstract static class Result {
    /** Returns {@code true} if transitive classpath fallback occurred. */
    public abstract boolean transitiveClasspathFallback();

    /** The length of the transitive classpath. */
    public abstract int transitiveClasspathLength();

    /**
     * The length of the reduced classpath, or {@link #transitiveClasspathLength} if classpath
     * reduction is not supported.
     */
    public abstract int reducedClasspathLength();

    public abstract Statistics processorStatistics();

    static Result create(
        boolean transitiveClasspathFallback,
        int transitiveClasspathLength,
        int reducedClasspathLength,
        Statistics processorStatistics) {
      return new AutoValue_Main_Result(
          transitiveClasspathFallback,
          transitiveClasspathLength,
          reducedClasspathLength,
          processorStatistics);
    }
  }

  @CanIgnoreReturnValue
  public static Result compile(String[] args) throws IOException {
    return compile(TurbineOptionsParser.parse(Arrays.asList(args)));
  }

  @CanIgnoreReturnValue
  public static Result compile(TurbineOptions options) throws IOException {
    usage(options);

    ImmutableList<CompUnit> units = parseAll(options);

    ClassPath bootclasspath = bootclasspath(options);

    BindingResult bound;
    ReducedClasspathMode reducedClasspathMode = options.reducedClasspathMode();
    if (reducedClasspathMode == ReducedClasspathMode.JAVABUILDER_REDUCED
        && options.directJars().isEmpty()) {
      // the compilation doesn't support reduced classpaths
      // TODO(cushon): make this a usage error, see TODO in Dependencies.reduceClasspath
      reducedClasspathMode = ReducedClasspathMode.NONE;
    }
    boolean transitiveClasspathFallback = false;
    ImmutableList<String> classPath = options.classPath();
    int transitiveClasspathLength = classPath.size();
    int reducedClasspathLength = classPath.size();
    switch (reducedClasspathMode) {
      case NONE:
        bound = bind(options, units, bootclasspath, classPath);
        break;
      case BAZEL_FALLBACK:
        reducedClasspathLength = options.reducedClasspathLength();
        bound = bind(options, units, bootclasspath, classPath);
        transitiveClasspathFallback = true;
        break;
      case JAVABUILDER_REDUCED:
        Collection<String> reducedClasspath =
            Dependencies.reduceClasspath(classPath, options.directJars(), options.depsArtifacts());
        reducedClasspathLength = reducedClasspath.size();
        try {
          bound = bind(options, units, bootclasspath, reducedClasspath);
        } catch (TurbineError e) {
          bound = fallback(options, units, bootclasspath, classPath);
          transitiveClasspathFallback = true;
        }
        break;
      case BAZEL_REDUCED:
        transitiveClasspathLength = options.fullClasspathLength();
        try {
          bound = bind(options, units, bootclasspath, classPath);
        } catch (TurbineError e) {
          writeJdepsForFallback(options);
          return Result.create(
              /* transitiveClasspathFallback= */ true,
              /* transitiveClasspathLength= */ transitiveClasspathLength,
              /* reducedClasspathLength= */ reducedClasspathLength,
              Statistics.empty());
        }
        break;
      default:
        throw new AssertionError(reducedClasspathMode);
    }

    if (options.outputDeps().isPresent()
        || options.headerCompilationOutput().isPresent()
        || options.output().isPresent()
        || options.outputManifest().isPresent()) {
      // TODO(cushon): parallelize
      Lowered lowered =
          Lower.lowerAll(
              Lower.LowerOptions.builder()
                  .languageVersion(options.languageVersion())
                  .emitPrivateFields(options.javacOpts().contains("-XDturbine.emitPrivateFields"))
                  .methodParameters(!options.javacOpts().contains("-XDturbine.noMethodParameters"))
                  .build(),
              bound.units(),
              bound.modules(),
              bound.classPathEnv());

      if (options.outputDeps().isPresent()) {
        DepsProto.Dependencies deps =
            Dependencies.collectDeps(options.targetLabel(), bootclasspath, bound, lowered);
        Path path = Paths.get(options.outputDeps().get());
        /*
         * TODO: cpovirk - Consider checking outputDeps for validity earlier so that anyone who
         * `--output_deps=/` or similar will get a proper error instead of NPE.
         */
        Files.createDirectories(requireNonNull(path.getParent()));
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(path))) {
          deps.writeTo(os);
        }
      }
      if (options.output().isPresent()) {
        ImmutableMap<String, byte[]> transitive =
            options.headerCompilationOutput().isPresent()
                ? ImmutableMap.of()
                : Transitive.collectDeps(bootclasspath, bound);
        writeOutput(options, bound.generatedClasses(), lowered.bytes(), transitive);
      }
      if (options.headerCompilationOutput().isPresent()) {
        ImmutableMap<String, byte[]> trimmed = Transitive.trimOutput(lowered.bytes());
        ImmutableMap<String, byte[]> transitive = Transitive.collectDeps(bootclasspath, bound);
        writeHeaderCompilationOutput(options, trimmed, transitive);
      }
      if (options.outputManifest().isPresent()) {
        writeManifestProto(options, bound.units(), bound.generatedSources());
      }
    }

    writeSources(options, bound.generatedSources());
    writeResources(options, bound.generatedClasses());
    return Result.create(
        /* transitiveClasspathFallback= */ transitiveClasspathFallback,
        /* transitiveClasspathLength= */ transitiveClasspathLength,
        /* reducedClasspathLength= */ reducedClasspathLength,
        bound.statistics());
  }

  // don't inline this; we want it to show up in profiles
  private static BindingResult fallback(
      TurbineOptions options,
      ImmutableList<CompUnit> units,
      ClassPath bootclasspath,
      ImmutableList<String> classPath)
      throws IOException {
    return bind(options, units, bootclasspath, classPath);
  }

  /**
   * Writes a jdeps proto that indiciates to Blaze that the transitive classpath compilation failed,
   * and it should fall back to the transitive classpath. Used only when {@link
   * ReducedClasspathMode#BAZEL_REDUCED}.
   */
  public static void writeJdepsForFallback(TurbineOptions options) throws IOException {
    try (OutputStream os =
        new BufferedOutputStream(Files.newOutputStream(Paths.get(options.outputDeps().get())))) {
      DepsProto.Dependencies.newBuilder()
          .setRuleLabel(options.targetLabel().get())
          .setRequiresReducedClasspathFallback(true)
          .build()
          .writeTo(os);
    }
  }

  private static BindingResult bind(
      TurbineOptions options,
      ImmutableList<CompUnit> units,
      ClassPath bootclasspath,
      Collection<String> classpath)
      throws IOException {
    return Binder.bind(
        units,
        ClassPathBinder.bindClasspath(toPaths(classpath)),
        Processing.initializeProcessors(
            /* sourceVersion= */ options.languageVersion().sourceVersion(),
            /* javacopts= */ options.javacOpts(),
            /* processorNames= */ options.processors(),
            Processing.processorLoader(
                /* processorPath= */ options.processorPath(),
                /* builtinProcessors= */ options.builtinProcessors())),
        bootclasspath,
        /* moduleVersion= */ Optional.empty());
  }

  private static void usage(TurbineOptions options) {
    if (options.help()) {
      throw new UsageException();
    }
    if (!options.output().isPresent()
        && !options.gensrcOutput().isPresent()
        && !options.resourceOutput().isPresent()) {
      throw new UsageException(
          "at least one of --output, --gensrc_output, or --resource_output is required");
    }
  }

  private static ClassPath bootclasspath(TurbineOptions options) throws IOException {
    // if both --release and --bootclasspath are specified, --release wins
    OptionalInt release = options.languageVersion().release();
    if (release.isPresent() && options.system().isPresent()) {
      throw new UsageException("expected at most one of --release and --system");
    }

    if (release.isPresent()) {
      return release(release.getAsInt());
    }

    if (options.system().isPresent()) {
      // look for a jimage in the given JDK
      return JimageClassBinder.bind(options.system().get());
    }

    // the bootclasspath might be empty, e.g. when compiling java.lang
    return ClassPathBinder.bindClasspath(toPaths(options.bootClassPath()));
  }

  private static ClassPath release(int release) throws IOException {
    // Search ct.sym for a matching release
    ClassPath bootclasspath = CtSymClassBinder.bind(release);
    if (bootclasspath != null) {
      return bootclasspath;
    }
    if (release == Integer.parseInt(JAVA_SPECIFICATION_VERSION.value())) {
      // if --release matches the host JDK, use its jimage
      return JimageClassBinder.bindDefault();
    }
    throw new UsageException("not a supported release: " + release);
  }

  /** Parse all source files and source jars. */
  // TODO(cushon): parallelize
  private static ImmutableList<CompUnit> parseAll(TurbineOptions options) throws IOException {
    return parseAll(options.sources(), options.sourceJars());
  }

  static ImmutableList<CompUnit> parseAll(Iterable<String> sources, Iterable<String> sourceJars)
      throws IOException {
    ImmutableList.Builder<CompUnit> units = ImmutableList.builder();
    for (String source : sources) {
      Path path = Paths.get(source);
      units.add(Parser.parse(new SourceFile(source, MoreFiles.asCharSource(path, UTF_8).read())));
    }
    for (String sourceJar : sourceJars) {
      try (Zip.ZipIterable iterable = new Zip.ZipIterable(Paths.get(sourceJar))) {
        for (Zip.Entry ze : iterable) {
          if (ze.name().endsWith(".java")) {
            String name = ze.name();
            String source = new String(ze.data(), UTF_8);
            units.add(Parser.parse(new SourceFile(name, source)));
          }
        }
      }
    }
    return units.build();
  }

  /** Writes source files generated by annotation processors. */
  private static void writeSources(
      TurbineOptions options, ImmutableMap<String, SourceFile> generatedSources)
      throws IOException {
    if (!options.gensrcOutput().isPresent()) {
      return;
    }
    Path path = Paths.get(options.gensrcOutput().get());
    if (Files.isDirectory(path)) {
      for (SourceFile source : generatedSources.values()) {
        Path to = path.resolve(source.path());
        // TODO: cpovirk - Consider checking gensrcOutput, similar to outputDeps.
        Files.createDirectories(requireNonNull(to.getParent()));
        Files.writeString(to, source.source());
      }
      return;
    }
    try (OutputStream os = Files.newOutputStream(path);
        BufferedOutputStream bos = new BufferedOutputStream(os, BUFFER_SIZE);
        JarOutputStream jos = new JarOutputStream(bos)) {
      writeManifest(jos, manifest());
      for (SourceFile source : generatedSources.values()) {
        addEntry(jos, source.path(), source.source().getBytes(UTF_8));
      }
    }
  }

  /** Writes resource files generated by annotation processors. */
  private static void writeResources(
      TurbineOptions options, ImmutableMap<String, byte[]> generatedResources) throws IOException {
    if (!options.resourceOutput().isPresent()) {
      return;
    }
    Path path = Paths.get(options.resourceOutput().get());
    if (Files.isDirectory(path)) {
      for (Map.Entry<String, byte[]> resource : generatedResources.entrySet()) {
        Path to = path.resolve(resource.getKey());
        // TODO: cpovirk - Consider checking resourceOutput, similar to outputDeps.
        Files.createDirectories(requireNonNull(to.getParent()));
        Files.write(to, resource.getValue());
      }
      return;
    }
    try (OutputStream os = Files.newOutputStream(path);
        BufferedOutputStream bos = new BufferedOutputStream(os, BUFFER_SIZE);
        JarOutputStream jos = new JarOutputStream(bos)) {
      for (Map.Entry<String, byte[]> resource : generatedResources.entrySet()) {
        addEntry(jos, resource.getKey(), resource.getValue());
      }
    }
  }

  /** Writes bytecode to the output jar. */
  private static void writeOutput(
      TurbineOptions options,
      Map<String, byte[]> generated,
      Map<String, byte[]> lowered,
      Map<String, byte[]> transitive)
      throws IOException {
    Path path = Paths.get(options.output().get());
    try (OutputStream os = Files.newOutputStream(path);
        BufferedOutputStream bos = new BufferedOutputStream(os, BUFFER_SIZE);
        JarOutputStream jos = new JarOutputStream(bos)) {
      if (options.targetLabel().isPresent()) {
        writeManifest(jos, manifest(options));
      }
      for (Map.Entry<String, byte[]> entry : transitive.entrySet()) {
        addEntry(
            jos,
            ClassPathBinder.TRANSITIVE_PREFIX + entry.getKey() + ClassPathBinder.TRANSITIVE_SUFFIX,
            entry.getValue());
      }
      for (Map.Entry<String, byte[]> entry : lowered.entrySet()) {
        addEntry(jos, entry.getKey() + ".class", entry.getValue());
      }
      for (Map.Entry<String, byte[]> entry : generated.entrySet()) {
        addEntry(jos, entry.getKey(), entry.getValue());
      }
    }
  }

  private static void writeHeaderCompilationOutput(
      TurbineOptions options, Map<String, byte[]> trimmed, Map<String, byte[]> transitive)
      throws IOException {
    Path path = Paths.get(options.headerCompilationOutput().get());
    try (OutputStream os = Files.newOutputStream(path);
        BufferedOutputStream bos = new BufferedOutputStream(os, BUFFER_SIZE);
        JarOutputStream jos = new JarOutputStream(bos)) {
      Manifest manifest = manifest();
      Attributes attributes = manifest.getMainAttributes();
      if (options.output().isPresent()) {
        attributes.put(ORIGINAL_JAR_PATH, options.output().get());
      }
      writeManifest(jos, manifest);
      for (Map.Entry<String, byte[]> entry : transitive.entrySet()) {
        addEntry(
            jos,
            ClassPathBinder.TRANSITIVE_PREFIX + entry.getKey() + ClassPathBinder.TRANSITIVE_SUFFIX,
            entry.getValue());
      }
      for (Map.Entry<String, byte[]> entry : trimmed.entrySet()) {
        addEntry(jos, entry.getKey() + ".class", entry.getValue());
      }
    }
  }

  private static void writeManifestProto(
      TurbineOptions options,
      ImmutableMap<ClassSymbol, SourceTypeBoundClass> units,
      ImmutableMap<String, SourceFile> generatedSources)
      throws IOException {
    ManifestProto.Manifest.Builder manifest = ManifestProto.Manifest.newBuilder();
    for (Map.Entry<ClassSymbol, SourceTypeBoundClass> e : units.entrySet()) {
      manifest.addCompilationUnit(
          CompilationUnit.newBuilder()
              .setPath(e.getValue().source().path())
              .setPkg(e.getKey().packageName())
              .addTopLevel(e.getKey().simpleName())
              .setGeneratedByAnnotationProcessor(
                  generatedSources.containsKey(e.getValue().source().path()))
              .build());
    }
    try (OutputStream os =
        new BufferedOutputStream(
            Files.newOutputStream(Paths.get(options.outputManifest().get())))) {
      manifest.build().writeTo(os);
    }
  }

  /** Normalize timestamps. */
  static final LocalDateTime DEFAULT_TIMESTAMP = LocalDateTime.of(2010, 1, 1, 0, 0, 0);

  private static void addEntry(JarOutputStream jos, String name, byte[] bytes) throws IOException {
    JarEntry je = new JarEntry(name);
    je.setTimeLocal(DEFAULT_TIMESTAMP);
    je.setMethod(ZipEntry.STORED);
    je.setSize(bytes.length);
    je.setCrc(Hashing.crc32().hashBytes(bytes).padToLong());
    jos.putNextEntry(je);
    jos.write(bytes);
  }

  private static void writeManifest(JarOutputStream jos, Manifest manifest) throws IOException {
    addEntry(jos, MANIFEST_DIR, new byte[] {});
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    manifest.write(out);
    addEntry(jos, MANIFEST_NAME, out.toByteArray());
  }

  /** Creates a default {@link Manifest}. */
  private static Manifest manifest() {
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    Attributes.Name createdBy = new Attributes.Name("Created-By");
    if (attributes.getValue(createdBy) == null) {
      attributes.put(createdBy, "bazel");
    }
    return manifest;
  }

  /** Creates a {@link Manifest} that includes the target label and injecting rule kind. */
  private static Manifest manifest(TurbineOptions turbineOptions) {
    Manifest manifest = manifest();
    Attributes attributes = manifest.getMainAttributes();
    if (turbineOptions.targetLabel().isPresent()) {
      attributes.put(TARGET_LABEL, turbineOptions.targetLabel().get());
    }
    if (turbineOptions.injectingRuleKind().isPresent()) {
      attributes.put(INJECTING_RULE_KIND, turbineOptions.injectingRuleKind().get());
    }
    return manifest;
  }

  private static ImmutableList<Path> toPaths(Iterable<String> paths) {
    ImmutableList.Builder<Path> result = ImmutableList.builder();
    for (String path : paths) {
      result.add(Paths.get(path));
    }
    return result.build();
  }

  private Main() {}
}
