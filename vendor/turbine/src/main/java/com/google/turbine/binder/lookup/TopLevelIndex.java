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

package com.google.turbine.binder.lookup;

import org.jspecify.annotations.Nullable;

/**
 * An index of canonical type names.
 *
 * <p>Used to resolve top-level qualified type names in the classpath, and the sources being
 * compiled. Also provides lookup scopes for individual packages.
 *
 * <p>Only top-level classes are stored. Nested type names can't usually be assumed to be canonical
 * (the qualifier may inherited the named type, rather than declaring it directly), so nested types
 * are resolved separately with appropriate handling of non-canonical names. For bytecode we may end
 * up storing desugared nested classes (e.g. {@code Map$Entry}), but we can't tell until the class
 * file has been read and we have access to the InnerClasses attribtue.
 */
public interface TopLevelIndex {

  /** Returns a scope to look up top-level qualified type names. */
  Scope scope();

  /** Returns a scope to look up members of the given package. */
  @Nullable PackageScope lookupPackage(Iterable<String> packagename);
}
