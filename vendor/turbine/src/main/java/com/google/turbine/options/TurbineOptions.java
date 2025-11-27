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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Optional;

/** Header compilation options. */
@AutoValue
public abstract class TurbineOptions {

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

  /** Paths to the Java source files to compile. */
  public abstract ImmutableList<String> sources();

  /** Paths to classpath artifacts. */
  public abstract ImmutableList<String> classPath();

  /** Paths to compilation bootclasspath artifacts. */
  public abstract ImmutableSet<String> bootClassPath();

  /** The language version. */
  public abstract LanguageVersion languageVersion();

  /** The target platform's system modules. */
  public abstract Optional<String> system();

  /** The output jar. */
  public abstract Optional<String> output();

  /** The header compilation output jar. */
  public abstract Optional<String> headerCompilationOutput();

  /** Paths to annotation processor artifacts. */
  public abstract ImmutableList<String> processorPath();

  /** Annotation processor class names. */
  public abstract ImmutableSet<String> processors();

  /** Class names of annotation processor that are built in. */
  public abstract ImmutableSet<String> builtinProcessors();

  /** Source jars for compilation. */
  public abstract ImmutableList<String> sourceJars();

  /** Output jdeps file. */
  public abstract Optional<String> outputDeps();

  /** Output manifest file. */
  public abstract Optional<String> outputManifest();

  /** The direct dependencies. */
  public abstract ImmutableSet<String> directJars();

  /** The label of the target being compiled. */
  public abstract Optional<String> targetLabel();

  /**
   * If present, the name of the rule that injected an aspect that compiles this target.
   *
   * <p>Note that this rule will have a completely different label to {@link #targetLabel} above.
   */
  public abstract Optional<String> injectingRuleKind();

  /** The .jdeps artifacts for direct dependencies. */
  public abstract ImmutableList<String> depsArtifacts();

  /** Print usage information. */
  public abstract boolean help();

  /** Additional Java compiler flags. */
  public abstract ImmutableList<String> javacOpts();

  /** The reduced classpath optimization mode. */
  public abstract ReducedClasspathMode reducedClasspathMode();

  /** An optional path for profiling output. */
  public abstract Optional<String> profile();

  /** An optional path for generated source output. */
  public abstract Optional<String> gensrcOutput();

  /** An optional path for generated resource output. */
  public abstract Optional<String> resourceOutput();

  public abstract int fullClasspathLength();

  public abstract int reducedClasspathLength();

  public static Builder builder() {
    return new AutoValue_TurbineOptions.Builder()
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
  @AutoValue.Builder
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
