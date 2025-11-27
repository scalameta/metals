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

import static java.util.Objects.requireNonNull;

import com.google.turbine.binder.sym.Symbol;
import org.jspecify.annotations.Nullable;

/** An {@link Env} that chains two existing envs together. */
public class CompoundEnv<S extends Symbol, V> implements Env<S, V> {

  private final @Nullable Env<S, ? extends V> base;
  private final Env<S, ? extends V> env;

  private CompoundEnv(@Nullable Env<S, ? extends V> base, Env<S, ? extends V> env) {
    this.base = base;
    this.env = requireNonNull(env);
  }

  @Override
  public @Nullable V get(S sym) {
    V result = env.get(sym);
    if (result != null) {
      return result;
    }
    return base != null ? base.get(sym) : null;
  }

  /** A chainable compound env with a single entry. */
  public static <S extends Symbol, V> CompoundEnv<S, V> of(Env<S, ? extends V> env) {
    return new CompoundEnv<>(null, env);
  }

  /** Adds an env to the chain. */
  public CompoundEnv<S, V> append(Env<S, ? extends V> env) {
    return new CompoundEnv<>(this, env);
  }
}
