/*
 * Copyright 2017 Google Inc. All Rights Reserved.
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

package com.google.turbine.zip;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Supplier;
import com.google.common.primitives.UnsignedInts;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * A fast, minimal, and somewhat garbage zip implementation. This exists because graal <a
 * href="http://mail.openjdk.java.net/pipermail/graal-dev/2017-August/005039.html">doesn't yet
 * support</a> {@link java.util.zip.ZipFile}, and {@link java.util.zip.ZipInputStream} doesn't have
 * the performance we'd like (*). If you're reading this, you almost certainly want {@code ZipFile}
 * instead.
 *
 * <p>If you're reading this because you're fixing a bug, sorry.
 *
 * <p>(*) A benchmark that iterates over all of the entries in rt.jar takes 6.97ms to run with this
 * implementation and 202.99ms with ZipInputStream. (Those are averages across 100 reps, and I
 * verified they're doing the same work.) This is likely largely due to ZipInputStream reading the
 * entire file from the beginning to scan the local headers, whereas this implementation (and
 * ZipFile) only read the central directory. Iterating over the entries (but not reading the data)
 * is an interesting benchmark because we typically only read ~10% of the compile-time classpath, so
 * most time is spent just scanning entry names. And rt.jar is an interesting test case because
 * every compilation has to read it, and it dominates the size of the classpath for small
 * compilations.
 *
 * <p>Implementation notes:
 *
 * <ul>
 *   <li>Leading garbage may be supported, since the archive is read backwards using the central
 *       directory. Archives modified with zip -A may not be supported. Trailing garbage is not
 *       supported.
 *   <li>UTF-8 is the only supported encoding.
 *   <li>STORED and DEFLATE are the only supported compression methods.
 *   <li>Zip files larger than Integer.MAX_VALUE bytes are not supported.
 *   <li>The only supported ZIP64 field is ENDTOT. This implementation assumes that the ZIP64 end
 *       header is present only if ENDTOT in EOCD header is 0xFFFF.
 * </ul>
 */
public final class Zip {

  static final int ZIP64_ENDSIG = 0x06064b50;
  static final int ZIP64_LOCSIG = 0x07064b50;

  static final int LOCHDR = 30; // LOC header size
  static final int CENHDR = 46; // CEN header size
  static final int ENDHDR = 22; // END header size
  static final int ZIP64_LOCHDR = 20; // ZIP64 end locator header size
  static final int ZIP64_ENDHDR = 56; // ZIP64 end header size

  static final int ENDTOT = 10; // total number of entries
  static final int ENDSIZ = 12; // central directory size in bytes
  static final int ENDOFF = 16; // central directory offset
  static final int ENDCOM = 20; // zip file comment length

  static final int CENHOW = 10; // compression method
  static final int CENLEN = 24; // uncompressed size
  static final int CENSIZ = 20; // compressed size
  static final int CENNAM = 28; // filename length
  static final int CENEXT = 30; // extra field length
  static final int CENCOM = 32; // comment length
  static final int CENOFF = 42; // LOC header offset

  static final int LOCEXT = 28; // extra field length

  static final int ZIP64_ENDSIZ = 40; // central directory size in bytes

  static final int ZIP64_MAGICCOUNT = 0xFFFF;

  static final long ZIP64_MAGICVAL = 0xFFFFFFFFL;

  /** Iterates over a zip archive. */
  static class ZipIterator implements Iterator<Entry> {

    /** A reader for the backing storage. */
    private final FileChannel chan;

    private final Path path;
    private int cdindex = 0;
    private final MappedByteBuffer cd;
    private final CharsetDecoder decoder = UTF_8.newDecoder();

    ZipIterator(Path path, FileChannel chan, MappedByteBuffer cd) {
      this.path = path;
      this.chan = chan;
      this.cd = cd;
    }

    @Override
    public boolean hasNext() {
      return cdindex < cd.limit();
    }

    /** Returns a {@link Entry} for the current CEN entry. */
    @Override
    public Entry next() {
      // TODO(cushon): technically we're supposed to throw NSEE
      checkSignature(path, cd, cdindex, 1, 2, "CENSIG");
      int nameLength = cd.getChar(cdindex + CENNAM);
      int extLength = cd.getChar(cdindex + CENEXT);
      int commentLength = cd.getChar(cdindex + CENCOM);
      Entry entry = new Entry(path, chan, string(cd, cdindex + CENHDR, nameLength), cd, cdindex);
      cdindex += CENHDR + nameLength + extLength + commentLength;
      return entry;
    }

    public String string(ByteBuffer buf, int offset, int length) {
      buf = buf.duplicate();
      buf.position(offset);
      buf.limit(offset + length);
      decoder.reset();
      try {
        return decoder.decode(buf).toString();
      } catch (CharacterCodingException e) {
        throw new IOError(e);
      }
    }
  }

  /** Provides an {@link Iterable} of {@link Entry} over a zip archive. */
  public static class ZipIterable implements Iterable<Entry>, Closeable {

    private final Path path;
    private final FileChannel chan;
    private final MappedByteBuffer cd;

    public ZipIterable(Path path) throws IOException {
      this.path = path;
      this.chan = FileChannel.open(path, StandardOpenOption.READ);
      // Locate the EOCD
      long size = chan.size();
      if (size < ENDHDR) {
        throw new ZipException("invalid zip archive");
      }
      long eocdOffset = size - ENDHDR;
      MappedByteBuffer eocd = chan.map(MapMode.READ_ONLY, eocdOffset, ENDHDR);
      eocd.order(ByteOrder.LITTLE_ENDIAN);
      int index = 0;
      int commentSize = 0;
      if (!isSignature(eocd, 0, 5, 6)) {
        // The archive may contain a zip file comment; keep looking for the EOCD.
        long start = Math.max(0, size - ENDHDR - 0xFFFF);
        eocd = chan.map(MapMode.READ_ONLY, start, (size - start));
        eocd.order(ByteOrder.LITTLE_ENDIAN);
        index = (int) ((size - start) - ENDHDR);
        while (index > 0) {
          index--;
          eocd.position(index);
          if (isSignature(eocd, index, 5, 6)) {
            commentSize = (int) ((size - start) - ENDHDR) - index;
            eocdOffset = start + index;
            break;
          }
        }
      }
      checkSignature(path, eocd, index, 5, 6, "ENDSIG");
      int totalEntries = eocd.getChar(index + ENDTOT);
      long cdsize = UnsignedInts.toLong(eocd.getInt(index + ENDSIZ));
      long cdoffset = UnsignedInts.toLong(eocd.getInt(index + ENDOFF));
      int actualCommentSize = eocd.getChar(index + ENDCOM);
      if (commentSize != actualCommentSize) {
        throw new ZipException(
            String.format(
                "zip file comment length was %d, expected %d", commentSize, actualCommentSize));
      }
      // If zip64 sentinal values are present, check if the archive has a zip64 EOCD locator.
      if (totalEntries == ZIP64_MAGICCOUNT
          || cdsize == ZIP64_MAGICVAL
          || cdoffset == ZIP64_MAGICVAL) {
        // Assume the zip64 EOCD has the usual size; we don't support zip64 extensible data sectors.
        long zip64eocdOffset = size - ENDHDR - ZIP64_LOCHDR - ZIP64_ENDHDR;
        // Note that zip reading is necessarily best-effort, since an archive could contain 0xFFFF
        // entries and the last entry's data could contain a ZIP64_ENDSIG. Some implementations
        // read the full EOCD records and compare them.
        long zip64cdsize = zip64cdsize(chan, zip64eocdOffset);
        if (zip64cdsize != -1) {
          eocdOffset = zip64eocdOffset;
          cdsize = zip64cdsize;
        } else {
          // If we couldn't find a zip64 EOCD at a fixed offset, either it doesn't exist
          // or there was a zip64 extensible data sector, so try going through the
          // locator. This approach doesn't work if data was prepended to the archive
          // without updating the offset in the locator.
          MappedByteBuffer zip64loc =
              chan.map(MapMode.READ_ONLY, size - ENDHDR - ZIP64_LOCHDR, ZIP64_LOCHDR);
          zip64loc.order(ByteOrder.LITTLE_ENDIAN);
          if (zip64loc.getInt(0) == ZIP64_LOCSIG) {
            zip64eocdOffset = zip64loc.getLong(8);
            zip64cdsize = zip64cdsize(chan, zip64eocdOffset);
            if (zip64cdsize != -1) {
              eocdOffset = zip64eocdOffset;
              cdsize = zip64cdsize;
            }
          }
        }
      }
      this.cd = chan.map(MapMode.READ_ONLY, eocdOffset - cdsize, cdsize);
      cd.order(ByteOrder.LITTLE_ENDIAN);
    }

    static long zip64cdsize(FileChannel chan, long eocdOffset) throws IOException {
      MappedByteBuffer zip64eocd = chan.map(MapMode.READ_ONLY, eocdOffset, ZIP64_ENDHDR);
      zip64eocd.order(ByteOrder.LITTLE_ENDIAN);
      if (zip64eocd.getInt(0) == ZIP64_ENDSIG) {
        return zip64eocd.getLong(ZIP64_ENDSIZ);
      }
      return -1;
    }

    @Override
    public Iterator<Entry> iterator() {
      return new ZipIterator(path, chan, cd);
    }

    @Override
    public void close() throws IOException {
      chan.close();
    }
  }

  /** An entry in a zip archive. */
  public static class Entry implements Supplier<byte[]> {

    private final Path path;
    private final FileChannel chan;
    private final String name;
    private final ByteBuffer cd;
    private final int cdindex;

    public Entry(Path path, FileChannel chan, String name, ByteBuffer cd, int cdindex) {
      this.path = path;
      this.chan = chan;
      this.name = name;
      this.cd = cd;
      this.cdindex = cdindex;
    }

    /** The entry name. */
    public String name() {
      return name;
    }

    /** The entry data. */
    public byte[] data() {
      // Read the offset and variable lengths from the central directory and then try to map in the
      // data section in one shot.
      long offset = UnsignedInts.toLong(cd.getInt(cdindex + CENOFF));
      if (offset == ZIP64_MAGICVAL) {
        // TODO(cushon): read the offset from the 'Zip64 Extended Information Extra Field'
        throw new AssertionError(
            String.format("%s: %s requires missing zip64 support, please file a bug", path, name));
      }
      int nameLength = cd.getChar(cdindex + CENNAM);
      int extLength = cd.getChar(cdindex + CENEXT);
      int compression = cd.getChar(cdindex + CENHOW);
      switch (compression) {
        case 0x8:
          return getBytes(
              offset,
              nameLength,
              extLength,
              UnsignedInts.toLong(cd.getInt(cdindex + CENSIZ)),
              /* deflate= */ true);
        case 0x0:
          return getBytes(
              offset,
              nameLength,
              extLength,
              UnsignedInts.toLong(cd.getInt(cdindex + CENLEN)),
              /* deflate= */ false);
        default:
          throw new AssertionError(
              String.format("unsupported compression mode: 0x%x", compression));
      }
    }

    /**
     * Number of extra bytes to read for each file, to avoid re-mapping the data if the local header
     * reports more extra field data than the central directory.
     */
    static final int EXTRA_FIELD_SLACK = 128;

    private byte[] getBytes(
        long offset, int nameLength, int cenExtLength, long size, boolean deflate) {
      if (size > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("unsupported zip entry size: " + size);
      }
      try {
        MappedByteBuffer fc =
            chan.map(
                MapMode.READ_ONLY,
                offset,
                Math.min(
                    LOCHDR + nameLength + cenExtLength + size + EXTRA_FIELD_SLACK,
                    chan.size() - offset));
        fc.order(ByteOrder.LITTLE_ENDIAN);
        checkSignature(path, fc, /* index= */ 0, 3, 4, "LOCSIG");
        int locExtLength = fc.getChar(LOCEXT);
        if (locExtLength > cenExtLength + EXTRA_FIELD_SLACK) {
          // If the local header's extra fields don't match the central directory and we didn't
          // leave enough slac, re-map the data section with the correct extra field length.
          fc = chan.map(MapMode.READ_ONLY, offset + LOCHDR + nameLength + locExtLength, size);
          fc.order(ByteOrder.LITTLE_ENDIAN);
        } else {
          // Otherwise seek past the local header, name, and extra fields to the data.
          fc.position(LOCHDR + nameLength + locExtLength);
          fc.limit((int) (LOCHDR + nameLength + locExtLength + size));
        }
        byte[] bytes = new byte[(int) size];
        fc.get(bytes);
        if (deflate) {
          Inflater inf = new Inflater(/* nowrap= */ true);
          bytes = new InflaterInputStream(new ByteArrayInputStream(bytes), inf).readAllBytes();
          inf.end();
        }
        return bytes;
      } catch (IOException e) {
        throw new IOError(e);
      }
    }

    @Override
    public byte[] get() {
      return data();
    }
  }

  static void checkSignature(
      Path path, MappedByteBuffer buf, int index, int i, int j, String name) {
    if (!isSignature(buf, index, i, j)) {
      throw new AssertionError(
          String.format(
              "%s: bad %s (expected: 0x%02x%02x%02x%02x, actual: 0x%08x)",
              path, name, i, j, (int) 'K', (int) 'P', buf.getInt(index)));
    }
  }

  static boolean isSignature(MappedByteBuffer buf, int index, int i, int j) {
    return (buf.get(index) == 'P')
        && (buf.get(index + 1) == 'K')
        && (buf.get(index + 2) == i)
        && (buf.get(index + 3) == j);
  }

  private Zip() {}
}
