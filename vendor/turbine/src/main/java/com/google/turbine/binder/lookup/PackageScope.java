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

package com.google.turbine.binder.lookup;

import com.google.common.collect.Iterables;
import com.google.turbine.binder.sym.ClassSymbol;
import org.jspecify.annotations.Nullable;

/**
 * A scope that corresponds to a particular package, which supports iteration over its enclosed
 * classes.
 */
public interface PackageScope extends Scope {

  /** Returns the top-level classes enclosed by this package. */
  Iterable<ClassSymbol> classes();

  default PackageScope append(PackageScope next) {
    return concat(this, next);
  }

  static PackageScope concat(PackageScope base, PackageScope next) {
    return new PackageScope() {
      @Override
      public Iterable<ClassSymbol> classes() {
        return Iterables.concat(base.classes(), next.classes());
      }

      @Override
      public @Nullable LookupResult lookup(LookupKey lookupKey) {
        LookupResult result = base.lookup(lookupKey);
        if (result != null) {
          return result;
        }
        return next.lookup(lookupKey);
      }
    };
  }
}
