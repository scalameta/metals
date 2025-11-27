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

import com.google.errorprone.annotations.CheckReturnValue;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;

/** Preprocesses Unicode escape characters in Java source code, as described in JLS §3.3. */
public class UnicodeEscapePreprocessor {

  public static final char ASCII_SUB = 0x1A;

  private final SourceFile source;
  private final String input;

  private int idx = 0;
  private int ch;
  private boolean evenLeadingSlashes = true;

  public UnicodeEscapePreprocessor(SourceFile source) {
    this.source = source;
    this.input = source.source();
  }

  /** Returns the current position in the input. */
  public int position() {
    return idx - 1;
  }

  /** Returns true if all input has been read. */
  public boolean done() {
    return idx >= input.length();
  }

  /** Returns the next unescaped Unicode input character. */
  public int next() {
    eat();
    if (ch == '\\' && evenLeadingSlashes) {
      unicodeEscape();
    } else {
      evenLeadingSlashes = true;
    }
    return ch;
  }

  /** Returns a substring of the raw (escaped) input. */
  public String readString(int from, int to) {
    return input.substring(from, to);
  }

  /** Consumes a Unicode escape. */
  private void unicodeEscape() {
    eat();
    if (ch != 'u') {
      idx--;
      ch = '\\';
      evenLeadingSlashes = false;
      return;
    }
    do {
      eat();
    } while (ch == 'u');
    char acc = (char) ((hexDigit(ch) & 0xff) << 12);
    eat();
    acc |= (char) ((hexDigit(ch) & 0xff) << 8);
    eat();
    acc |= (char) ((hexDigit(ch) & 0xff) << 4);
    eat();
    acc |= (char) (hexDigit(ch) & 0xff);
    ch = acc;
    evenLeadingSlashes = ch != '\\';
  }

  /** Consumes a hex digit. */
  private int hexDigit(int d) {
    switch (d) {
      case '0':
      case '1':
      case '2':
      case '3':
      case '4':
      case '5':
      case '6':
      case '7':
      case '8':
      case '9':
        return (d - '0');
      case 'A':
      case 'B':
      case 'C':
      case 'D':
      case 'E':
      case 'F':
        return ((d - 'A') + 10);
      case 'a':
      case 'b':
      case 'c':
      case 'd':
      case 'e':
      case 'f':
        return ((d - 'a') + 10);
      case ASCII_SUB:
        throw error(ErrorKind.UNEXPECTED_EOF);
      default:
        throw error(ErrorKind.INVALID_UNICODE);
    }
  }

  // TURBINE-DIFF START
  /**
   * Consumes a raw input character.
   *
   * <p>Once the input is exhausted, {@code ch} will always be ASCII SUB. JLS §3.5 requires ASCII
   * SUB to be ignored if it is the last character in the escaped input stream, and assuming it
   * terminates the input avoids some bounds checks in the lexer.
   */
  // TURBINE-DIFF END
  private void eat() {
    char hi = done() ? ASCII_SUB : input.charAt(idx);
    idx++;
    if (!Character.isHighSurrogate(hi)) {
      ch = hi;
      return;
    }
    if (done()) {
      throw error(ErrorKind.UNPAIRED_SURROGATE, (int) hi);
    }
    char lo = input.charAt(idx++);
    if (!Character.isLowSurrogate(lo)) {
      throw error(ErrorKind.UNPAIRED_SURROGATE, (int) hi);
    }
    ch = Character.toCodePoint(hi, lo);
  }

  public SourceFile source() {
    return source;
  }

  @CheckReturnValue
  private TurbineError error(ErrorKind kind, Object... args) {
    throw TurbineError.format(
        source(), Math.min(position(), source().source().length() - 1), kind, args);
  }
}
