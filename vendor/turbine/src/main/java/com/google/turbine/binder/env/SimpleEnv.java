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

package com.google.turbine.binder.env;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.turbine.binder.sym.Symbol;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A simple {@link ImmutableMap}-backed {@link Env}. */
public class SimpleEnv<K extends Symbol, V> implements Env<K, V> {

  private final ImmutableMap<K, V> map;

  public SimpleEnv(ImmutableMap<K, V> map) {
    this.map = map;
  }

  public static <K extends Symbol, V> Builder<K, V> builder() {
    return new Builder<>();
  }

  public ImmutableMap<K, V> asMap() {
    return map;
  }

  /** A builder for {@link SimpleEnv}static. */
  public static class Builder<K extends Symbol, V> {
    private final Map<K, V> map = new LinkedHashMap<>();

    // TODO(cushon): audit the cases where this return value is being ignored
    @CanIgnoreReturnValue
    public @Nullable V put(K sym, V v) {
      return map.put(sym, v);
    }

    public SimpleEnv<K, V> build() {
      return new SimpleEnv<>(ImmutableMap.copyOf(map));
    }
  }

  @Override
  public @Nullable V get(K sym) {
    return map.get(sym);
  }
}
