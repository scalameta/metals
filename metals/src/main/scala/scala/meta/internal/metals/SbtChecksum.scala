package scala.meta.internal.metals

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import scala.meta.dialects
import scala.meta.internal.io.PathIO
import scala.meta.internal.mtags.Enrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.internal.tokenizers.LegacyScanner
import scala.meta.internal.tokenizers.LegacyToken._
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal

object SbtChecksum {
  def digest(workspace: AbsolutePath): Option[String] = {
    if (!workspace.isDirectory) None
    else {
      val digest = MessageDigest.getInstance("MD5")
      val isSuccess =
        digestDirectory(workspace, digest) &&
          digestDirectory(workspace.resolve("project"), digest)
      if (isSuccess) Some(MD5.bytesToHex(digest.digest()))
      else None
    }
  }

  private def digestDirectory(
      path: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    if (!path.isDirectory) true
    else {
      var isSuccess = true
      Files.list(path.toNIO).forEach { file =>
        isSuccess = isSuccess && digestFile(AbsolutePath(file), digest)
      }
      isSuccess
    }
  }

  private def digestFile(
      path: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    val isScala = PathIO.extension(path.toNIO) match {
      case "sbt" | "scala" => true
      case _ => false
    }
    if (isScala) {
      digestScala(path, digest)
    } else {
      true
    }
  }

  private def digestScala(
      file: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    try {
      val input = file.toInput
      val scanner = new LegacyScanner(input, dialects.Scala212)
      scanner.foreach { data =>
        data.token match {
          case WHITESPACE | COMMENT | EOF => ()
          case _ =>
            val start = data.offset
            val end = data.token match {
              case IDENTIFIER | BACKQUOTED_IDENT | STRINGLIT | STRINGPART |
                  LONGLIT | CHARLIT | DOUBLELIT | FLOATLIT | INTLIT |
                  SYMBOLLIT | XMLLIT | ARROW | LARROW | ELLIPSIS | UNQUOTE =>
                data.endOffset + 1
              case _ =>
                data.endOffset
            }
            val length = end - start
            val chars = CharBuffer.wrap(input.chars, start, length)
            val bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
            digest.update(data.token.toByte)
            digest.update(bytes)
        }
      }
      true
    } catch {
      case NonFatal(e) =>
        scribe.error(s"failed to checksum path $file", e)
        false
    }
  }
}
