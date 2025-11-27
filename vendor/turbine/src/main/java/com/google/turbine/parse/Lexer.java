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

package com.google.turbine.parse;

import com.google.turbine.diag.SourceFile;

/** A Java lexer. */
public interface Lexer {
  /** Returns the next token in the input stream, or {@code EOF}. */
  Token next();

  /** Returns the string value of the current literal or identifier token. */
  String stringValue();

  /** Returns the current position in the input. */
  int position();

  /** Returns the source file for diagnostics. */
  SourceFile source();

  /** Returns a saved javadoc comment. */
  String javadoc();
}
