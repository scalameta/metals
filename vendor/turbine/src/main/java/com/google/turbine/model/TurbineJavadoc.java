/*
 * Copyright 2025 Google Inc. All Rights Reserved.
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

package com.google.turbine.model;

/**
 * A token representing a javadoc comment.
 *
 * @param startPosition the position of the leading {@code /**} for the javadoc
 * @param endPosition the position of the trailing {@code *}{@code /} for the javadoc
 * @param source the source file containing the javadoc comment
 */
// TODO: b/459423956 - add support for markdown javadoc comments
public record TurbineJavadoc(int startPosition, int endPosition, String source) {

  /**
   * Returns the value of the javadoc comment, excluding the leading {@code /**} and trailing {@code
   * *}{@code /}
   */
  public String value() {
    return source.substring(startPosition + "/**".length(), endPosition - "*".length());
  }
}
