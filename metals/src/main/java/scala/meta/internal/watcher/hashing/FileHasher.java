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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * A function to convert a Path to a hash code used to check if the file content is changed. This is
 * called by DirectoryWatcher after checking that the path exists and is not a directory. Therefore
 * this hasher can generally assume that those two things are true.
 *
 * <p>By default, this hasher may throw an IOException, which will be treated as a `null` hash by
 * the watcher, meaning the associated event will be ignored. If you want to handle that exception
 * you can catch/rethrow it.
 */
@FunctionalInterface
public interface FileHasher {
  /** The default file hasher instance, which uses Murmur3. */
  FileHasher DEFAULT_FILE_HASHER =
      path -> {
        Murmur3F murmur = new Murmur3F();
        try (InputStream is = new BufferedInputStream(Files.newInputStream(path))) {
          int b;
          while ((b = is.read()) != -1) {
            murmur.update(b);
          }
        }
        return HashCode.fromBytes(murmur.getValueBytesBigEndian());
      };

  /**
   * A file hasher that returns the last modified time provided by the OS.
   *
   * <p>This only works reliably on certain file systems and JDKs that support at least
   * millisecond-level precision.
   */
  FileHasher LAST_MODIFIED_TIME =
      path -> {
        Instant modifyTime = Files.getLastModifiedTime(path).toInstant();
        ByteBuffer buffer = ByteBuffer.allocate(2 * Long.BYTES);
        buffer.putLong(modifyTime.getEpochSecond());
        buffer.putLong(modifyTime.getNano());
        return new HashCode(buffer.array());
      };

  HashCode hash(Path path) throws IOException;
}
