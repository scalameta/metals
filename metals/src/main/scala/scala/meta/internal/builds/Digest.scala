package scala.meta.internal.builds

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.stream.Collectors

import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.io.PathIO
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.tokens.Token
import scala.util.control.NonFatal
import scala.collection.JavaConverters._

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
  val version: String = "v4"
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

  def digestDirectory(
      path: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    if (!path.isDirectory) true
    else {
      Files.list(path.toNIO).iterator().asScala.forall { file =>
        digestFile(AbsolutePath(file), digest)
      }
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
    val isScala = Set("sbt", "scala")(ext)
    // we can have both gradle and gradle.kts and build plugins can be written in any of three languages
    val isGradle =
      Set("gradle", "groovy", "gradle.kts", "java", "kts").exists(
        path.toString().endsWith(_)
      )

    if (isScala && path.isFile) {
      digestScala(path, digest)
    } else if (isGradle && path.isFile) {
      digestGeneralJvm(path, digest)
    } else {
      true
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
        .foreach { word =>
          digest.update(word.getBytes())
        }
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

object SbtDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    val project = workspace.resolve("project")
    Digest.digestDirectory(workspace, digest) &&
    Digest.digestFileBytes(project.resolve("build.properties"), digest) &&
    Digest.digestDirectory(project, digest) &&
    Digest.digestDirectory(project.resolve("project"), digest)
  }
}

object GradleDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    val buildSrc = workspace.resolve("buildSrc")
    val buildSrcDigest = if (buildSrc.isDirectory) {
      digestBuildSrc(buildSrc, digest)
    } else {
      true
    }
    buildSrcDigest && Digest.digestDirectory(workspace, digest) && digestSubProjects(
      workspace,
      digest
    )
  }

  def digestBuildSrc(path: AbsolutePath, digest: MessageDigest): Boolean = {
    Files.walk(path.toNIO).iterator().asScala.forall { file =>
      Digest.digestFile(AbsolutePath(file), digest)
    }
  }

  def digestSubProjects(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    val (subprojects, dirs) = Files
      .list(workspace.toNIO)
      .filter(Files.isDirectory(_))
      .collect(Collectors.toList[Path])
      .asScala
      .partition { file =>
        Files
          .list(file)
          .anyMatch { path =>
            val stringPath = path.toString
            stringPath.endsWith(".gradle") || stringPath.endsWith("gradle.kts")
          }
      }
    /*
     If a dir contains a gradle file we need to treat is as a workspace
     */
    val isSuccessful = subprojects.forall { file =>
      digestWorkspace(
        AbsolutePath(file),
        digest
      )
    }

    /*
     If it's a dir we need to keep searching since gradle can have non trivial workspace layouts
     */
    isSuccessful && dirs.forall { file =>
      digestSubProjects(AbsolutePath(file), digest)
    }
  }
}
