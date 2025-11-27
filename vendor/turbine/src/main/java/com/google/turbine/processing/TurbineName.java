/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

package com.google.turbine.processing;

import static java.util.Objects.requireNonNull;

import javax.lang.model.element.Name;
import org.jspecify.annotations.Nullable;

/** An implementation of {@link Name} backed by a {@link CharSequence}. */
public class TurbineName implements Name {

  private final String name;

  public TurbineName(String name) {
    requireNonNull(name);
    this.name = name;
  }

  @Override
  public boolean contentEquals(CharSequence cs) {
    return name.contentEquals(cs);
  }

  @Override
  public int length() {
    return name.length();
  }

  @Override
  public char charAt(int index) {
    return name.charAt(index);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return name.subSequence(start, end);
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    return obj instanceof TurbineName && contentEquals(((TurbineName) obj).name);
  }
}
