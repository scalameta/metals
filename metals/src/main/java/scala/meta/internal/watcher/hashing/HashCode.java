/*
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
 *
 * NOTE: That this class is taken verbatim from io.methvin:directory-watcher.
 * Seeing that we have replaced the directory-watcher functionality with swoval
 * we are instead just re-utilizing the few DirectoryChangeEvent related
 * classes.
 */
package scala.meta.internal.watcher.hashing;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Formatter;

/** A class representing the hash code of a file. */
public class HashCode {
  private final byte[] value;

  public static HashCode fromBytes(byte[] value) {
    return new HashCode(Arrays.copyOf(value, value.length));
  }

  public static HashCode fromLong(long value) {
    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
    buffer.putLong(value);
    return new HashCode(buffer.array());
  }

  public static HashCode empty() {
    return new HashCode(new byte[0]);
  }

  HashCode(byte[] value) {
    this.value = value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    HashCode hashCode = (HashCode) o;
    return Arrays.equals(value, hashCode.value);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }

  @Override
  public String toString() {
    Formatter formatter = new Formatter();
    for (byte b : value) {
      formatter.format("%02x", b);
    }
    return formatter.toString();
  }
}
