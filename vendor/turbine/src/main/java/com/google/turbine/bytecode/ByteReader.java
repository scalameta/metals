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

package com.google.turbine.bytecode;

import static com.google.common.base.Verify.verify;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;

/** A {@link ByteArrayDataInput} wrapper that tracks the current byte array index. */
public class ByteReader {

  private final byte[] bytes;
  private final IndexedByteArrayInputStream indexed;
  private final ByteArrayDataInput input;

  public ByteReader(byte[] bytes, int pos) {
    this.bytes = bytes;
    this.indexed = new IndexedByteArrayInputStream(bytes, pos, bytes.length);
    this.input = ByteStreams.newDataInput(indexed);
  }

  public ByteArrayDataInput seek(int i) {
    return ByteStreams.newDataInput(bytes, i);
  }

  /** The position in the input buffer. */
  public int pos() {
    return indexed.pos();
  }

  /** Reads an unsigned 8-bit integer. */
  public int u1() {
    return input.readUnsignedByte();
  }

  /** Reads an unsigned 16-bit integer in big-endian byte order. */
  public int u2() {
    return input.readUnsignedShort();
  }

  /** Reads an unsigned 32-bit integer in big-endian byte order. */
  public int u4() {
    return input.readInt();
  }

  /** Skips n bytes of input. */
  public void skip(int n) {
    int skipped = input.skipBytes(n);
    // this is only used with {@link ByteArrayInputStream}.
    verify(skipped == n, "wanted %s, read %s", n, skipped);
  }

  /** {@link #pos} is protected in {@link java.io.ByteArrayInputStream}. */
  static class IndexedByteArrayInputStream extends ByteArrayInputStream {

    IndexedByteArrayInputStream(byte[] buf, int offset, int length) {
      super(buf, offset, length);
    }

    int pos() {
      return pos;
    }
  }
}
