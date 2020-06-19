package scala.meta.internal.builds

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

import scala.util.control.NonFatal
import scala.xml.Node

import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Trees
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath

case class Digest(
    md5: String,
    status: Status,
    millis: Long
)

object Digest {

  /**
   * Bump up this version if parameters outside of the sbt sources themselves require
   * re-running `bloopInstall`. For example a SemanticDB or Bloop version upgrade.
   */
  val version: String = "v5"
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
    def all: List[Status] =
      List(
        Requested,
        Started,
        Rejected,
        Failed,
        Installed,
        Cancelled
      )
  }

  def digestDirectory(
      path: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    if (!path.isDirectory) true
    else {
      path.list.forall { file => digestFile(file, digest) }
    }
  }

  def digestFileBytes(
      path: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    if (path.isFile) {
      digest.update(path.readAllBytes)
    }
    true
  }

  def digestFile(
      path: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    val ext = PathIO.extension(path.toNIO)
    val isScala = Set("sbt", "scala", "sc")(ext)
    // we can have both gradle and gradle.kts and build plugins can be written in any of three languages
    val isGradle =
      Set("gradle", "groovy", "gradle.kts", "java", "kts").exists(
        path.toString().endsWith(_)
      )
    val isXml = ext == "xml"

    if (isScala && path.isFile) {
      digestScala(path, digest)
    } else if (isGradle && path.isFile) {
      digestGeneralJvm(path, digest)
    } else if (isXml) {
      digestXml(path, digest)
    } else if (path.isBuild) {
      digestFileBytes(path, digest)
    } else {
      true
    }
  }

  def digestXml(
      file: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    import scala.xml.XML
    def digestElement(node: Node): Boolean = {
      digest.update(node.label.getBytes())
      for {
        attr <- node.attributes
        _ = digest.update(attr.key.getBytes())
        value <- attr.value
      } digest.update(value.toString().getBytes())

      val chldrenSuccessful: Seq[Boolean] = for {
        child <- node.child
      } yield digestElement(child)
      chldrenSuccessful.forall(p => p)
    }
    try {
      val xml = XML.loadFile(file.toNIO.toFile)
      digestElement(xml)
      xml.text.split("\\s+").foreach(word => digest.update(word.getBytes))
      true
    } catch {
      case NonFatal(_) =>
        false
    }
  }

  def digestGeneralJvm(
      file: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    try {
      Files
        .readAllLines(file.toNIO)
        .asScala
        .mkString("\n")
        .replaceAll("""//.*""", "") // replace any inline comment
        .split("\\s+")
        .foreach { word => digest.update(word.getBytes()) }
      true
    } catch {
      case NonFatal(_) =>
        false
    }
  }

  def digestScala(
      file: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    try {
      val input = file.toInput
      val tokens = Trees.defaultDialect(input).tokenize.get
      tokens.foreach {
        case token if token.isWhiteSpaceOrComment => // Do nothing
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

trait Digestable {
  def current(workspace: AbsolutePath): Option[String] = {
    if (!workspace.isDirectory) None
    else {
      val digest = MessageDigest.getInstance("MD5")
      // we skip the version in tests, so that we don't have to manually update the digests in tests
      // when changing the version
      if (System.getProperty("metals.testing") == null) {
        digest.update(Digest.version.getBytes(StandardCharsets.UTF_8))
      }

      digest.update(workspace.toString.getBytes(StandardCharsets.UTF_8))
      val isSuccess = digestWorkspace(workspace, digest)
      if (isSuccess) Some(MD5.bytesToHex(digest.digest()))
      else None
    }
  }

  protected def digestWorkspace(
      absolutePath: AbsolutePath,
      digest: MessageDigest
  ): Boolean
}
