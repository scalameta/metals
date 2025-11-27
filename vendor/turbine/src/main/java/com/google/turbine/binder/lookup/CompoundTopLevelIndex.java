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

package com.google.turbine.binder.lookup;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import org.jspecify.annotations.Nullable;

/** A {@link TopLevelIndex} that aggregates multiple indices into one. */
// Note: this implementation doesn't detect if the indices contain incompatible information,
// e.g. a class name in one index that is a prefix of a package name in another index. This
// shouldn't matter in practice because we rely on javac to reject invalid input, but it would
// be nice to report an error in that case.
// TODO(cushon): improve error handling
public class CompoundTopLevelIndex implements TopLevelIndex {

  private final ImmutableList<TopLevelIndex> indexes;

  private CompoundTopLevelIndex(ImmutableList<TopLevelIndex> indexes) {
    this.indexes = checkNotNull(indexes);
  }

  /** Creates a {@link CompoundTopLevelIndex}. */
  public static CompoundTopLevelIndex of(TopLevelIndex... indexes) {
    return new CompoundTopLevelIndex(ImmutableList.copyOf(indexes));
  }

  private final Scope scope =
      new Scope() {
        @Override
        public @Nullable LookupResult lookup(LookupKey lookupKey) {
          // Return the first matching symbol.
          for (TopLevelIndex index : indexes) {
            LookupResult result = index.scope().lookup(lookupKey);
            if (result != null) {
              return result;
            }
          }
          return null;
        }
      };

  @Override
  public Scope scope() {
    return scope;
  }

  @Override
  public @Nullable PackageScope lookupPackage(Iterable<String> packagename) {
    // When returning package scopes, build up a compound scope containing entries from all
    // indices with matching packages.
    PackageScope result = null;
    for (TopLevelIndex index : indexes) {
      PackageScope packageScope = index.lookupPackage(packagename);
      if (packageScope == null) {
        continue;
      }
      if (result == null) {
        result = packageScope;
      } else {
        result = result.append(packageScope);
      }
    }
    return result;
  }
}
