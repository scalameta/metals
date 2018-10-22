package scala.meta.internal.metals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import scala.meta.internal.io.PathIO
import scala.meta.internal.mtags.Enrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath
import scala.meta.tokens.Token
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
      val tokens = input.tokenize.get
      tokens.foreach {
        case _: Token.Space | _: Token.Tab | _: Token.CR | _: Token.LF |
            _: Token.LFLF | _: Token.FF | _: Token.Comment | _: Token.BOF |
            _: Token.EOF => // Do nothing
        case token =>
          val bytes = StandardCharsets.UTF_8.encode(token.pos.text)
          digest.update(token.productPrefix.getBytes())
          digest.update(bytes)
      }
      true
    } catch {
      case NonFatal(e) =>
        scribe.error(s"sbt checksum error: $file", e)
        false
    }
  }
}
