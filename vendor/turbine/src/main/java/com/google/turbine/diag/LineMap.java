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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableRangeMap;
import com.google.common.collect.Range;

/** Converts source positions to line and column information, for diagnostic formatting. */
public class LineMap {

  private final String source;
  private final ImmutableRangeMap<Integer, Integer> lines;

  private LineMap(String source, ImmutableRangeMap<Integer, Integer> lines) {
    this.source = source;
    this.lines = lines;
  }

  public static LineMap create(String source) {
    int last = 0;
    int line = 1;
    ImmutableRangeMap.Builder<Integer, Integer> builder = ImmutableRangeMap.builder();
    for (int idx = 0; idx < source.length(); idx++) {
      char ch = source.charAt(idx);
      switch (ch) {
        // handle CR line endings
        case '\r':
          // ...and CRLF
          if (idx + 1 < source.length() && source.charAt(idx + 1) == '\n') {
            idx++;
          }
        // falls through
        case '\n':
          builder.put(Range.closedOpen(last, idx + 1), line++);
          last = idx + 1;
          break;
        default:
          break;
      }
    }
    // no trailing newline
    if (last < source.length()) {
      builder.put(Range.closedOpen(last, source.length()), line++);
    }
    return new LineMap(source, builder.build());
  }

  /** The zero-indexed column number of the given source position. */
  public int column(int position) {
    checkArgument(0 <= position && position < source.length(), "%s", position);
    // requireNonNull is safe because `lines` covers the whole file length.
    return position - requireNonNull(lines.getEntry(position)).getKey().lowerEndpoint();
  }

  /** The one-indexed line number of the given source position. */
  public int lineNumber(int position) {
    checkArgument(0 <= position && position < source.length(), "%s", position);
    // requireNonNull is safe because `lines` covers the whole file length.
    return requireNonNull(lines.get(position));
  }

  /** The one-indexed line of the given source position. */
  public String line(int position) {
    checkArgument(0 <= position && position < source.length(), "%s", position);
    // requireNonNull is safe because `lines` covers the whole file length.
    Range<Integer> range = requireNonNull(lines.getEntry(position)).getKey();
    return source.substring(range.lowerEndpoint(), range.upperEndpoint());
  }
}
