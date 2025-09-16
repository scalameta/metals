package scala.meta.internal.metals.mbt

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets

import scala.sys.process.Process
import scala.sys.process.ProcessIO
import scala.util.Try

import scala.meta.io.AbsolutePath

/**
 * Represents a file object read from git's cat-file output.
 *
 * @param oid   The object ID (SHA-1 hash) of the file.
 * @param bytes The raw byte content of the file.
 */
case class GitBlobBytes(oid: String, bytes: Array[Byte])

object GitCat {

  def batch(
      stdin: String,
      cwd: AbsolutePath,
  ): Iterator[GitBlobBytes] with Closeable = {
    // NOTE: it's not great to read the full output into memory here. Someone
    // could create a multi-gigabyte Scala/Java file and cause OOM. It's
    // probably worth adding more safe-guards like 1) forwarding into a file and
    // 2) skipping files above a certain size, which we can implement efficiently
    // inside the parseOutput method.
    val baos = new ByteArrayOutputStream()
    val catFileExit = (Process(
      List("git", "cat-file", "--batch"),
      cwd = cwd.toFile,
    )
      .run(
        new ProcessIO(
          in => {
            in.write(stdin.getBytes(StandardCharsets.UTF_8))
            in.close()
          },
          out => {
            out.transferTo(baos)
          },
          err => {
            err.transferTo(System.err)
          },
        )
      ))
      .exitValue()

    if (catFileExit != 0) {
      throw new IOException(
        s"Error while running git cat-file --batch in dir '${cwd}': $catFileExit"
      )
    }
    parseOutput(baos.toByteArray)
  }

  /**
   * Reads the output of a `git cat-file --batch` command in a streaming fashion.
   *
   * The `git cat-file --batch` command produces a stream of object data. Each entry consists of:
   * 1. A header line: `<oid> <type> <size>\n`
   * 2. The raw object content of exactly `<size>` bytes.
   * 3. A trailing newline character `\n`.
   *
   * This class parses that stream lazily via an Iterator, ensuring that only one
   * git object needs to be in memory at a time.
   */
  private def parseOutput(
      bytes: Array[Byte]
  ): Iterator[GitBlobBytes] with Closeable = {
    val inputStream = new DataInputStream(
      new BufferedInputStream(new ByteArrayInputStream(bytes))
    )

    // NOTE(olafurpg) rest of this method is ~100% AI generated. Feel free to
    // throw it away and replace it with another attempt if there's a bug.

    new Iterator[GitBlobBytes] with Closeable {
      private var nextEntry: Option[GitBlobBytes] = None
      private var isStreamClosed = false

      private def closeStream(): Unit = {
        if (!isStreamClosed) {
          Try(inputStream.close())
          isStreamClosed = true
        }
      }

      def close(): Unit = {
        closeStream()
      }

      private object Number {
        def unapply(str: String): Option[Int] = {
          str.toIntOption
        }
      }

      private def fetchNext(): Unit = {
        // DataInputStream.readLine() is deprecated but this is what the LLM
        // generated and we don't have to deal with non-ASCII letter.
        val header = inputStream.readLine()

        if (header == null) {
          nextEntry = None
          closeStream()
          return
        }

        header match {
          case s"$oid blob ${Number(size)}" =>
            val content = new Array[Byte](size)
            try {
              inputStream.readFully(content)
            } catch {
              case e: EOFException =>
                closeStream()
                throw new IllegalStateException(
                  s"Unexpected end of file while reading content for OID $oid.",
                  e,
                )
            }

            val trailingByte = inputStream.read()
            if (trailingByte != '\n' && trailingByte != -1) {
              closeStream()
              throw new IllegalStateException(
                s"Expected a newline after content for OID $oid, but found: '$trailingByte'"
              )
            }
            nextEntry = Some(GitBlobBytes(oid, content))
          case _ =>
            closeStream()
            throw new IllegalStateException(
              s"Invalid git-cat-file header format: '$header'"
            )
        }

      }

      fetchNext()

      override def hasNext: Boolean = {
        val has = nextEntry.isDefined
        if (!has) closeStream()
        has
      }

      override def next(): GitBlobBytes = {
        if (!hasNext) {
          throw new NoSuchElementException(
            "next() called on an empty iterator."
          )
        }
        val result = nextEntry.get
        fetchNext()
        result
      }
    }
  }
}
