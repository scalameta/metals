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

package com.google.turbine.options;

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Optional;

/**
 * Header compilation options.
 *
 * @param sources Paths to the Java source files to compile.
 * @param classPath Paths to classpath artifacts.
 * @param bootClassPath Paths to compilation bootclasspath artifacts.
 * @param languageVersion The language version.
 * @param system The target platform's system modules.
 * @param output The output jar.
 * @param headerCompilationOutput The header compilation output jar.
 * @param processorPath Paths to annotation processor artifacts.
 * @param processors Annotation processor class names.
 * @param builtinProcessors Class names of annotation processor that are built in.
 * @param sourceJars Source jars for compilation.
 * @param outputDeps Output jdeps file.
 * @param outputManifest Output manifest file.
 * @param directJars The direct dependencies.
 * @param targetLabel The label of the target being compiled.
 * @param injectingRuleKind If present, the name of the rule that injected an aspect that compiles
 *     this target.
 *     <p>Note that this rule will have a completely different label to {@link #targetLabel} above.
 * @param depsArtifacts The .jdeps artifacts for direct dependencies.
 * @param help Print usage information.
 * @param javacOpts Additional Java compiler flags.
 * @param reducedClasspathMode The reduced classpath optimization mode.
 * @param profile An optional path for profiling output.
 * @param gensrcOutput An optional path for generated source output.
 * @param resourceOutput An optional path for generated resource output.
 */
public record TurbineOptions(
    ImmutableList<String> sources,
    ImmutableList<String> classPath,
    ImmutableSet<String> bootClassPath,
    LanguageVersion languageVersion,
    Optional<String> system,
    Optional<String> output,
    Optional<String> headerCompilationOutput,
    ImmutableList<String> processorPath,
    ImmutableSet<String> processors,
    ImmutableSet<String> builtinProcessors,
    ImmutableList<String> sourceJars,
    Optional<String> outputDeps,
    Optional<String> outputManifest,
    ImmutableSet<String> directJars,
    Optional<String> targetLabel,
    Optional<String> injectingRuleKind,
    ImmutableList<String> depsArtifacts,
    boolean help,
    ImmutableList<String> javacOpts,
    ReducedClasspathMode reducedClasspathMode,
    Optional<String> profile,
    Optional<String> gensrcOutput,
    Optional<String> resourceOutput,
    int fullClasspathLength,
    int reducedClasspathLength) {
  public TurbineOptions {
    requireNonNull(sources, "sources");
    requireNonNull(classPath, "classPath");
    requireNonNull(bootClassPath, "bootClassPath");
    requireNonNull(languageVersion, "languageVersion");
    requireNonNull(system, "system");
    requireNonNull(output, "output");
    requireNonNull(headerCompilationOutput, "headerCompilationOutput");
    requireNonNull(processorPath, "processorPath");
    requireNonNull(processors, "processors");
    requireNonNull(builtinProcessors, "builtinProcessors");
    requireNonNull(sourceJars, "sourceJars");
    requireNonNull(outputDeps, "outputDeps");
    requireNonNull(outputManifest, "outputManifest");
    requireNonNull(directJars, "directJars");
    requireNonNull(targetLabel, "targetLabel");
    requireNonNull(injectingRuleKind, "injectingRuleKind");
    requireNonNull(depsArtifacts, "depsArtifacts");
    requireNonNull(javacOpts, "javacOpts");
    requireNonNull(reducedClasspathMode, "reducedClasspathMode");
    requireNonNull(profile, "profile");
    requireNonNull(gensrcOutput, "gensrcOutput");
    requireNonNull(resourceOutput, "resourceOutput");
  }

  /**
   * This modes controls how a probablistic Java classpath reduction is used. For each mode except
   * {@code NONE} a speculative compilation is performed against a subset of the original classpath.
   * If it fails due to a missing symbol, it is retried with the original transitive classpath.
   */
  public enum ReducedClasspathMode {
    /**
     * Bazel performs classpath reduction, and invokes turbine passing only the reduced classpath.
     * If the compilation fails and requires fallback, turbine finishes with exit code 0 but records
     * that the reduced classpath compilation failed in the jdeps proto.
     */
    BAZEL_REDUCED,
    /**
     * Indicates that the reduced classpath compilation failed when Bazel previously invoked
     * turbine, and that we are retrying with a transitive classpath.
     */
    BAZEL_FALLBACK,
    /**
     * Turbine implements reduced classpaths locally, with in-process fallback if the compilation
     * fails.
     */
    JAVABUILDER_REDUCED,
    /** Reduced classpaths are disabled, and a full transitive classpath is used. */
    NONE
  }

  public static Builder builder() {
    return new AutoBuilder_TurbineOptions_Builder()
        .setSources(ImmutableList.of())
        .setClassPath(ImmutableList.of())
        .setBootClassPath(ImmutableList.of())
        .setProcessorPath(ImmutableList.of())
        .setProcessors(ImmutableList.of())
        .setBuiltinProcessors(ImmutableList.of())
        .setSourceJars(ImmutableList.of())
        .setDirectJars(ImmutableList.of())
        .setDepsArtifacts(ImmutableList.of())
        .addAllJavacOpts(ImmutableList.of())
        .setLanguageVersion(LanguageVersion.createDefault())
        .setReducedClasspathMode(ReducedClasspathMode.NONE)
        .setHelp(false)
        .setFullClasspathLength(0)
        .setReducedClasspathLength(0);
  }

  /** A {@link Builder} for {@link TurbineOptions}. */
  @AutoBuilder
  public abstract static class Builder {
    public abstract Builder setOutput(String output);

    public abstract Builder setClassPath(ImmutableList<String> classPath);

    public abstract Builder setBootClassPath(ImmutableList<String> bootClassPath);

    public abstract Builder setLanguageVersion(LanguageVersion languageVersion);

    public abstract Builder setSystem(String system);

    public abstract Builder setSources(ImmutableList<String> sources);

    public abstract Builder setProcessorPath(ImmutableList<String> processorPath);

    public abstract Builder setProcessors(ImmutableList<String> processors);

    public abstract Builder setBuiltinProcessors(ImmutableList<String> builtinProcessors);

    public abstract Builder setSourceJars(ImmutableList<String> sourceJars);

    public abstract Builder setOutputDeps(String outputDeps);

    public abstract Builder setHeaderCompilationOutput(String headerCompilationOutput);

    public abstract Builder setOutputManifest(String outputManifest);

    public abstract Builder setTargetLabel(String targetLabel);

    public abstract Builder setInjectingRuleKind(String injectingRuleKind);

    public abstract Builder setDepsArtifacts(ImmutableList<String> depsArtifacts);

    public abstract Builder setHelp(boolean help);

    abstract ImmutableList.Builder<String> javacOptsBuilder();

    @CanIgnoreReturnValue
    public Builder addAllJavacOpts(Iterable<String> javacOpts) {
      javacOptsBuilder().addAll(javacOpts);
      return this;
    }

    public abstract Builder setReducedClasspathMode(ReducedClasspathMode reducedClasspathMode);

    public abstract Builder setDirectJars(ImmutableList<String> jars);

    public abstract Builder setProfile(String profile);

    public abstract Builder setGensrcOutput(String gensrcOutput);

    public abstract Builder setResourceOutput(String resourceOutput);

    public abstract Builder setFullClasspathLength(int fullClasspathLength);

    public abstract Builder setReducedClasspathLength(int reducedClasspathLength);

    public abstract TurbineOptions build();
  }
}
