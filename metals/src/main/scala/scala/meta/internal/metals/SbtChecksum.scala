package scala.meta.internal.metals

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.SbtChecksum.Status
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath
import scala.meta.tokens.Token
import scala.util.control.NonFatal

case class SbtChecksum(
    md5Digest: String,
    status: Status
)

object SbtChecksum {
  sealed abstract class Status(val value: Int)
      extends Product
      with Serializable {
    import Status._
    def isRequested: Boolean = this == Requested
    def isStarted: Boolean = this == Started
    def isRejected: Boolean = this == Rejected
    def isFailed: Boolean = this == Failed
    def isInstalled: Boolean = this == Installed
    def isCancelled: Boolean = this == Cancelled
  }
  object Status {
    case object Requested extends Status(0)
    case object Started extends Status(1)
    case object Rejected extends Status(2)
    case object Failed extends Status(3)
    case object Installed extends Status(4)
    case object Cancelled extends Status(5)
    case class Unknown(n: Int) extends Status(n)
    def all: List[Status] = List(
      Requested,
      Started,
      Rejected,
      Failed,
      Installed,
      Cancelled
    )
  }

  def current(workspace: AbsolutePath): Option[String] = {
    if (!workspace.isDirectory) None
    else {
      val digest = MessageDigest.getInstance("MD5")
      val project = workspace.resolve("project")
      val isSuccess =
        digestDirectory(workspace, digest) &&
          digestFileBytes(project.resolve("build.properties"), digest) &&
          digestDirectory(project, digest)
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

  private def digestFileBytes(
      path: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    if (path.isFile) {
      digest.update(path.readAllBytes)
    }
    true
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
        false
    }
  }
}
