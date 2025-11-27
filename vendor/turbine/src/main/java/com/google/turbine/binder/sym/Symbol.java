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

package com.google.turbine.binder.sym;

import com.google.errorprone.annotations.Immutable;

/** The top interface for all symbols. */
@Immutable
public interface Symbol {
  /** The symbol kind. */
  enum Kind {
    CLASS,
    TY_PARAM,
    METHOD,
    FIELD,
    PARAMETER,
    RECORD_COMPONENT,
    MODULE,
    PACKAGE
  }

  /** The symbol kind. */
  Kind symKind();
}
