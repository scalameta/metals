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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.turbine.options.TurbineOptions.ReducedClasspathMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;

/** A command line options parser for {@link TurbineOptions}. */
public final class TurbineOptionsParser {

  /**
   * Parses command line options into {@link TurbineOptions}, expanding any {@code @params} files.
   */
  public static TurbineOptions parse(Iterable<String> args) throws IOException {
    TurbineOptions.Builder builder = TurbineOptions.builder();
    parse(builder, args);
    return builder.build();
  }

  /**
   * Parses command line options into a {@link TurbineOptions.Builder}, expanding any
   * {@code @params} files.
   */
  public static void parse(TurbineOptions.Builder builder, Iterable<String> args)
      throws IOException {
    Deque<String> argumentDeque = new ArrayDeque<>();
    expandParamsFiles(argumentDeque, args);
    parse(builder, argumentDeque);
  }

  private static void parse(TurbineOptions.Builder builder, Deque<String> argumentDeque) {
    while (!argumentDeque.isEmpty()) {
      String next = argumentDeque.removeFirst();
      switch (next) {
        case "--output":
          builder.setOutput(readOne(next, argumentDeque));
          break;
        case "--source_jars":
          builder.setSourceJars(readList(argumentDeque));
          break;
        case "--temp_dir":
          // TODO(cushon): remove this when Bazel no longer passes the flag
          readOne(next, argumentDeque);
          break;
        case "--processors":
          builder.setProcessors(readList(argumentDeque));
          break;
        case "--builtin_processors":
          builder.setBuiltinProcessors(readList(argumentDeque));
          break;
        case "--processorpath":
          builder.setProcessorPath(readList(argumentDeque));
          break;
        case "--classpath":
          builder.setClassPath(readList(argumentDeque));
          break;
        case "--bootclasspath":
          builder.setBootClassPath(readList(argumentDeque));
          break;
        case "--system":
          builder.setSystem(readOne(next, argumentDeque));
          break;
        case "--javacopts":
          ImmutableList<String> javacOpts = readJavacopts(argumentDeque);
          builder.setLanguageVersion(LanguageVersion.fromJavacopts(javacOpts));
          builder.addAllJavacOpts(javacOpts);
          break;
        case "--sources":
          builder.setSources(readList(argumentDeque));
          break;
        case "--output_deps_proto":
        case "--output_deps":
          builder.setOutputDeps(readOne(next, argumentDeque));
          break;
        case "--header_compilation_output":
          builder.setHeaderCompilationOutput(readOne(next, argumentDeque));
          break;
        case "--output_manifest_proto":
          builder.setOutputManifest(readOne(next, argumentDeque));
          break;
        case "--direct_dependencies":
          builder.setDirectJars(readList(argumentDeque));
          break;
        case "--deps_artifacts":
          builder.setDepsArtifacts(readList(argumentDeque));
          break;
        case "--target_label":
          builder.setTargetLabel(readOne(next, argumentDeque));
          break;
        case "--injecting_rule_kind":
          builder.setInjectingRuleKind(readOne(next, argumentDeque));
          break;
        case "--javac_fallback":
        case "--nojavac_fallback":
          // TODO(cushon): remove this case once blaze stops passing the flag
          break;
        case "--reduce_classpath":
          builder.setReducedClasspathMode(ReducedClasspathMode.JAVABUILDER_REDUCED);
          break;
        case "--noreduce_classpath":
          builder.setReducedClasspathMode(ReducedClasspathMode.NONE);
          break;
        case "--reduce_classpath_mode":
          builder.setReducedClasspathMode(
              ReducedClasspathMode.valueOf(readOne(next, argumentDeque)));
          break;
        case "--full_classpath_length":
          builder.setFullClasspathLength(Integer.parseInt(readOne(next, argumentDeque)));
          break;
        case "--reduced_classpath_length":
          builder.setReducedClasspathLength(Integer.parseInt(readOne(next, argumentDeque)));
          break;
        case "--profile":
          builder.setProfile(readOne(next, argumentDeque));
          break;
        case "--generated_sources_output":
        case "--gensrc_output":
          builder.setGensrcOutput(readOne(next, argumentDeque));
          break;
        case "--resource_output":
          builder.setResourceOutput(readOne(next, argumentDeque));
          break;
        case "--help":
          builder.setHelp(true);
          break;
        case "--experimental_fix_deps_tool":
        case "--strict_java_deps":
        case "--native_header_output":
          // accepted (and ignored) for compatibility with JavaBuilder command lines
          readOne(next, argumentDeque);
          break;
        case "--post_processor":
          // accepted (and ignored) for compatibility with JavaBuilder command lines
          ImmutableList<String> unused = readList(argumentDeque);
          break;
        case "--compress_jar":
          // accepted (and ignored) for compatibility with JavaBuilder command lines
          break;
        default:
          throw new IllegalArgumentException("unknown option: " + next);
      }
    }
  }

  /**
   * Pre-processes an argument list, expanding arguments of the form {@code @filename} by reading
   * the content of the file and appending whitespace-delimited options to {@code argumentDeque}.
   */
  private static void expandParamsFiles(Deque<String> argumentDeque, Iterable<String> args)
      throws IOException {
    for (String arg : args) {
      if (arg.isEmpty()) {
        continue;
      }
      if (arg.charAt(0) == '\'') {
        // perform best-effort unescaping as a concession to ninja, see:
        // https://android.googlesource.com/platform/external/ninja/+/6f903faaf5488dc052ffc4e3e0b12757b426e088/src/util.cc#274
        checkArgument(arg.charAt(arg.length() - 1) == '\'', arg);
        arg = arg.substring(1, arg.length() - 1);
        checkArgument(!arg.contains("'"), arg);
      }
      if (arg.startsWith("@@")) {
        argumentDeque.addLast(arg.substring(1));
      } else if (arg.startsWith("@")) {
        Path paramsPath = Paths.get(arg.substring(1));
        if (!Files.exists(paramsPath)) {
          throw new AssertionError("params file does not exist: " + paramsPath);
        }
        expandParamsFiles(argumentDeque, Files.readAllLines(paramsPath));
      } else {
        argumentDeque.addLast(arg);
      }
    }
  }

  /**
   * Returns the value of an option, or throws {@link IllegalArgumentException} if the value is not
   * present.
   */
  private static String readOne(String flag, Deque<String> argumentDeque) {
    if (argumentDeque.isEmpty() || argumentDeque.getFirst().startsWith("-")) {
      throw new IllegalArgumentException("missing required argument for: " + flag);
    }
    return argumentDeque.removeFirst();
  }

  /** Returns a list of option values. */
  private static ImmutableList<String> readList(Deque<String> argumentDeque) {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    while (!argumentDeque.isEmpty() && !argumentDeque.getFirst().startsWith("--")) {
      result.add(argumentDeque.removeFirst());
    }
    return result.build();
  }

  /**
   * Returns a list of javacopts. Reads options until a terminating {@code "--"} is reached, to
   * support parsing javacopts that start with {@code --} (e.g. --release).
   */
  private static ImmutableList<String> readJavacopts(Deque<String> argumentDeque) {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    while (!argumentDeque.isEmpty()) {
      String arg = argumentDeque.removeFirst();
      if (arg.equals("--")) {
        return result.build();
      }
      result.add(arg);
    }
    throw new IllegalArgumentException("javacopts should be terminated by `--`");
  }

  private TurbineOptionsParser() {}
}
