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

package com.google.turbine.diag;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A source file. */
public class SourceFile {

  private final String path;
  private final String source;

  private final Supplier<LineMap> lineMap =
      Suppliers.memoize(
          new Supplier<LineMap>() {
            @Override
            public LineMap get() {
              return LineMap.create(source);
            }
          });

  public SourceFile(String path, String source) {
    this.path = path;
    this.source = source;
  }

  /** The path. */
  public String path() {
    return path;
  }

  /** The source. */
  public String source() {
    return source;
  }

  LineMap lineMap() {
    return lineMap.get();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof SourceFile)) {
      return false;
    }
    SourceFile that = (SourceFile) obj;
    return Objects.equals(path, that.path) && source.equals(that.source);
  }

  @Override
  public int hashCode() {
    return path != null ? path.hashCode() : 0;
  }
}
