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

package com.google.turbine.main;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;

/** A command-line usage error. */
class UsageException extends RuntimeException {

  private static final String[] USAGE = {
    "",
    "Usage: turbine [options]",
    "",
    "Options:",
    "  --output",
    "    The jar output file.",
    "  --sources",
    "    The sources to compile.",
    "  --source_jars",
    "    jar archives of sources to compile.",
    "  --classpath",
    "    The compilation classpath.",
    "  --bootclasspath",
    "    The compilation bootclasspath.",
    "  --help",
    "    Print this usage statement.",
    "  @<filename>",
    "    Read options and filenames from file.",
    "",
  };

  UsageException() {
    super(buildMessage(null));
  }

  UsageException(String message) {
    super(buildMessage(requireNonNull(message)));
  }

  private static String buildMessage(String message) {
    StringBuilder builder = new StringBuilder();
    if (message != null) {
      builder.append(message).append('\n');
    }
    Joiner.on('\n').appendTo(builder, USAGE).append('\n');
    return builder.toString();
  }
}
