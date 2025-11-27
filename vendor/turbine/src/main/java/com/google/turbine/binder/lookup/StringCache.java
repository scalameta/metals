/*
 * Copyright 2024 Google Inc. All Rights Reserved.
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

package com.google.turbine.binder.lookup;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.Maps;
import java.util.HashMap;
import org.jspecify.annotations.Nullable;

/**
 * A cache for canonicalizing strings and string-like data.
 *
 * <p>This class is intended to reduce GC overhead in code where lots of duplicate strings might be
 * allocated. As such, the internals are optimized not make allocations while searching for cached
 * string instances.
 *
 * <p>Searches can be made with a variety of keys, without materializing the actual string they
 * represent. Materialization only happens if the search fails.
 */
public final class StringCache {

  /**
   * A map from strings to themselves.
   *
   * <p>The key-type is {@link Object} so that {@link SubstringKey} can be used to search the map.
   * Otherwise we could use a {@link Set}.
   *
   * <p>This approach exploits the (documented!) fact that {@link HashMap#get} only ever calls
   * {@link #equals} on the key parameter, never the stored keys. This allows us to inject our own
   * definition of equality, without needing to wrap the keys at rest.
   */
  private final HashMap<Object, String> cache;

  private final SubstringKey substringKey = new SubstringKey();

  public StringCache(int expectedSize) {
    this.cache = Maps.newHashMapWithExpectedSize(expectedSize);
  }

  public String get(String str) {
    String result = cache.putIfAbsent(str, str);
    return (result == null) ? str : result;
  }

  public String getSubstring(String superstring, int start, int end) {
    checkArgument(0 <= start && start <= end && end <= superstring.length());

    this.substringKey.fill(superstring, start, end);
    String result = cache.get(this.substringKey);
    if (result == null) {
      result = superstring.substring(start, end);
      cache.put(result, result);
    }
    return result;
  }

  /**
   * A key based on a substring view.
   *
   * <p>There is only one instance of SubstringKey per cache. This is possible because it's only
   * ever used for searches, never to store values. This reuse prevents a lot of garbage generation.
   */
  private static final class SubstringKey {
    String superstring;
    int start;
    int end;
    int length;

    public void fill(String superstring, int start, int end) {
      this.superstring = superstring;
      this.start = start;
      this.end = end;
      this.length = end - start;
    }

    @Override
    @SuppressWarnings({"EqualsBrokenForNull", "EqualsUnsafeCast", "dereference"})
    public boolean equals(@Nullable Object that) {
      String thatString = (String) that;
      return (thatString.length() == this.length)
          && thatString.regionMatches(0, this.superstring, this.start, this.length);
    }

    @Override
    public int hashCode() {
      // This implementation must exactly match the documented behavior of String.hashCode().
      int result = 0;
      for (int i = this.start; i < this.end; i++) {
        result = 31 * result + this.superstring.charAt(i);
      }
      return result;
    }
  }
}
