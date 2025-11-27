/*
 * Copyright 2021 Google Inc. All Rights Reserved.
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
import com.google.common.primitives.Ints;
import java.util.Iterator;
import java.util.OptionalInt;
import javax.lang.model.SourceVersion;

/**
 * The language version being compiled, corresponding to javac's {@code -source}, {@code -target},
 * and {@code --release} flags.
 */
@AutoValue
public abstract class LanguageVersion {

  /** The source version. */
  public abstract int source();

  /** The target version. */
  public abstract int target();

  /**
   * The release version.
   *
   * <p>If set, system APIs will be resolved from the host JDK's ct.sym instead of using the
   * provided {@code --bootclasspath}.
   */
  public abstract OptionalInt release();

  /** The class file major version corresponding to the {@link #target}. */
  public int majorVersion() {
    return target() + 44;
  }

  public SourceVersion sourceVersion() {
    try {
      return SourceVersion.valueOf("RELEASE_" + source());
    } catch (IllegalArgumentException unused) {
      return SourceVersion.latestSupported();
    }
  }

  private static LanguageVersion create(int source, int target, OptionalInt release) {
    return new AutoValue_LanguageVersion(source, target, release);
  }

  /** The default language version. Currently Java 8. */
  public static LanguageVersion createDefault() {
    return create(DEFAULT, DEFAULT, OptionalInt.empty());
  }

  private static final int DEFAULT = 8;

  /** Returns the effective {@code LanguageVersion} for the given list of javac options. */
  public static LanguageVersion fromJavacopts(ImmutableList<String> javacopts) {
    int sourceVersion = DEFAULT;
    int targetVersion = DEFAULT;
    OptionalInt release = OptionalInt.empty();
    Iterator<String> it = javacopts.iterator();
    while (it.hasNext()) {
      String option = it.next();
      switch (option) {
        case "-source":
        case "--source":
          if (!it.hasNext()) {
            throw new IllegalArgumentException(option + " requires an argument");
          }
          sourceVersion = parseVersion(it.next());
          release = OptionalInt.empty();
          break;
        case "-target":
        case "--target":
          if (!it.hasNext()) {
            throw new IllegalArgumentException(option + " requires an argument");
          }
          targetVersion = parseVersion(it.next());
          release = OptionalInt.empty();
          break;
        case "--release":
          if (!it.hasNext()) {
            throw new IllegalArgumentException(option + " requires an argument");
          }
          String value = it.next();
          Integer n = Ints.tryParse(value);
          if (n == null) {
            throw new IllegalArgumentException("invalid --release version: " + value);
          }
          release = OptionalInt.of(n);
          sourceVersion = n;
          targetVersion = n;
          break;
        default:
          break;
      }
    }
    return create(sourceVersion, targetVersion, release);
  }

  private static int parseVersion(String value) {
    boolean hasPrefix = value.startsWith("1.");
    Integer version = Ints.tryParse(hasPrefix ? value.substring("1.".length()) : value);
    if (version == null || !isValidVersion(version, hasPrefix)) {
      throw new IllegalArgumentException("invalid -source version: " + value);
    }
    return version;
  }

  private static boolean isValidVersion(int version, boolean hasPrefix) {
    if (version < 5) {
      // the earliest source version supported by JDK 8 is Java 5
      return false;
    }
    if (hasPrefix && version > 10) {
      // javac supports legacy `1.*` version numbers for source versions up to Java 10
      return false;
    }
    return true;
  }
}
