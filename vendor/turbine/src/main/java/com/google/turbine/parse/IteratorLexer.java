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
import java.util.Iterator;

/**
 * A {@link Lexer} that wraps an iterator over an existing token stream.
 *
 * <p>Used when parsing pre-processed constant expression initializers.
 */
public class IteratorLexer implements Lexer {

  private final SourceFile source;
  private final Iterator<SavedToken> it;
  private SavedToken curr;

  public IteratorLexer(SourceFile source, Iterator<SavedToken> it) {
    this.source = source;
    this.it = it;
  }

  @Override
  public SourceFile source() {
    return source;
  }

  @Override
  public Token next() {
    if (it.hasNext()) {
      curr = it.next();
      return curr.token;
    }
    return Token.EOF;
  }

  @Override
  public String stringValue() {
    return curr.value;
  }

  @Override
  public int position() {
    return curr.position;
  }

  @Override
  public String javadoc() {
    return null;
  }
}
