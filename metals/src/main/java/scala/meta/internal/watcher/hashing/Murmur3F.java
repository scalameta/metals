/*
 * Code adapted from Greenrobot Essentials Murmur3F.java (https://git.io/fAG0Z)
 *
 * Copyright (C) 2014-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NOTE: That this class is taken verbatim from io.methvin:directory-watcher.
 * Seeing that we have replaced the directory-watcher functionality with swoval
 * we are instead just re-utilizing the few DirectoryChangeEvent related
 * classes.
 */
package scala.meta.internal.watcher.hashing;

import java.math.BigInteger;
import java.util.zip.Checksum;

/** Murmur3F (MurmurHash3_x64_128) */
public class Murmur3F implements Checksum {

  private static final long C1 = 0x87c37b91114253d5L;
  private static final long C2 = 0x4cf5ad432745937fL;

  private final long seed;

  private long h1;
  private long h2;
  private int length;

  private int partialPos;
  private long partialK1;
  private long partialK2;

  private boolean finished;
  private long finishedH1;
  private long finishedH2;

  public Murmur3F() {
    seed = 0;
  }

  public Murmur3F(int seed) {
    this.seed = seed & 0xffffffffL; // unsigned 32 bit -> long
    h1 = h2 = this.seed;
  }

  @Override
  public void update(int b) {
    finished = false;
    switch (partialPos) {
      case 0:
        partialK1 = 0xff & b;
        break;
      case 1:
        partialK1 |= (0xff & b) << 8;
        break;
      case 2:
        partialK1 |= (0xff & b) << 16;
        break;
      case 3:
        partialK1 |= (0xffL & b) << 24;
        break;
      case 4:
        partialK1 |= (0xffL & b) << 32;
        break;
      case 5:
        partialK1 |= (0xffL & b) << 40;
        break;
      case 6:
        partialK1 |= (0xffL & b) << 48;
        break;
      case 7:
        partialK1 |= (0xffL & b) << 56;
        break;
      case 8:
        partialK2 = 0xff & b;
        break;
      case 9:
        partialK2 |= (0xff & b) << 8;
        break;
      case 10:
        partialK2 |= (0xff & b) << 16;
        break;
      case 11:
        partialK2 |= (0xffL & b) << 24;
        break;
      case 12:
        partialK2 |= (0xffL & b) << 32;
        break;
      case 13:
        partialK2 |= (0xffL & b) << 40;
        break;
      case 14:
        partialK2 |= (0xffL & b) << 48;
        break;
      case 15:
        partialK2 |= (0xffL & b) << 56;
        break;
    }

    partialPos++;
    if (partialPos == 16) {
      applyKs(partialK1, partialK2);
      partialPos = 0;
    }
    length++;
  }

  public void update(byte[] b) {
    update(b, 0, b.length);
  }

  @Override
  public void update(byte[] b, int off, int len) {
    finished = false;
    while (partialPos != 0 && len > 0) {
      update(b[off]);
      off++;
      len--;
    }

    int remainder = len & 0xF;
    int stop = off + len - remainder;
    for (int i = off; i < stop; i += 16) {
      long k1 = getLongLE(b, i);
      long k2 = getLongLE(b, i + 8);
      applyKs(k1, k2);
    }
    length += stop - off;

    for (int i = 0; i < remainder; i++) {
      update(b[stop + i]);
    }
  }

  private void applyKs(long k1, long k2) {
    k1 *= C1;
    k1 = Long.rotateLeft(k1, 31);
    k1 *= C2;
    h1 ^= k1;

    h1 = Long.rotateLeft(h1, 27);
    h1 += h2;
    h1 = h1 * 5 + 0x52dce729;

    k2 *= C2;
    k2 = Long.rotateLeft(k2, 33);
    k2 *= C1;
    h2 ^= k2;

    h2 = Long.rotateLeft(h2, 31);
    h2 += h1;
    h2 = h2 * 5 + 0x38495ab5;
  }

  private void checkFinished() {
    if (!finished) {
      finished = true;
      finishedH1 = h1;
      finishedH2 = h2;
      if (partialPos > 0) {
        if (partialPos > 8) {
          long k2 = partialK2 * C2;
          k2 = Long.rotateLeft(k2, 33);
          k2 *= C1;
          finishedH2 ^= k2;
        }
        long k1 = partialK1 * C1;
        k1 = Long.rotateLeft(k1, 31);
        k1 *= C2;
        finishedH1 ^= k1;
      }

      finishedH1 ^= length;
      finishedH2 ^= length;

      finishedH1 += finishedH2;
      finishedH2 += finishedH1;

      finishedH1 = fmix64(finishedH1);
      finishedH2 = fmix64(finishedH2);

      finishedH1 += finishedH2;
      finishedH2 += finishedH1;
    }
  }

  private long fmix64(long k) {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccdL;
    k ^= k >>> 33;
    k *= 0xc4ceb9fe1a85ec53L;
    k ^= k >>> 33;
    return k;
  }

  @Override
  /**
   * Returns the lower 64 bits of the 128 bit hash (you can use just this value this as a 64 bit
   * hash).
   */
  public long getValue() {
    checkFinished();
    return finishedH1;
  }

  /** Returns the higher 64 bits of the 128 bit hash. */
  public long getValueHigh() {
    checkFinished();
    return finishedH2;
  }

  /** Positive value. */
  public BigInteger getValueBigInteger() {
    byte[] bytes = getValueBytesBigEndian();
    return new BigInteger(1, bytes);
  }

  /** Padded with leading 0s to ensure length of 32. */
  public String getValueHexString() {
    checkFinished();
    return getPaddedHexString(finishedH2) + getPaddedHexString(finishedH1);
  }

  private String getPaddedHexString(long value) {
    String string = Long.toHexString(value);
    while (string.length() < 16) {
      string = '0' + string;
    }
    return string;
  }

  public byte[] getValueBytesBigEndian() {
    checkFinished();
    byte[] bytes = new byte[16];
    for (int i = 0; i < 8; i++) {
      bytes[i] = (byte) ((finishedH2 >>> (56 - i * 8)) & 0xff);
    }
    for (int i = 0; i < 8; i++) {
      bytes[8 + i] = (byte) ((finishedH1 >>> (56 - i * 8)) & 0xff);
    }
    return bytes;
  }

  public byte[] getValueBytesLittleEndian() {
    checkFinished();
    byte[] bytes = new byte[16];
    for (int i = 0; i < 8; i++) {
      bytes[i] = (byte) ((finishedH1 >>> (i * 8)) & 0xff);
    }
    for (int i = 0; i < 8; i++) {
      bytes[8 + i] = (byte) ((finishedH2 >>> (i * 8)) & 0xff);
    }
    return bytes;
  }

  @Override
  public void reset() {
    h1 = h2 = seed;
    length = 0;
    partialPos = 0;
    finished = false;

    // The remainder is not really necessary, but looks nicer when debugging
    partialK1 = partialK2 = 0;
    finishedH1 = finishedH2 = 0;
  }

  private long getLongLE(byte[] bytes, int index) {
    return (bytes[index] & 0xff)
        | ((bytes[index + 1] & 0xff) << 8)
        | ((bytes[index + 2] & 0xff) << 16)
        | ((bytes[index + 3] & 0xffL) << 24)
        | ((bytes[index + 4] & 0xffL) << 32)
        | ((bytes[index + 5] & 0xffL) << 40)
        | ((bytes[index + 6] & 0xffL) << 48)
        | (((long) bytes[index + 7]) << 56);
  }
}
