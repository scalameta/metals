package scala.meta.internal.mtags

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

import scala.meta.AbsolutePath
import scala.meta.Classpath
import scala.meta.internal.mtags
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.internal.mtags.Semanticdbs.FoundSemanticDbPath
import scala.meta.io.RelativePath

final case class SemanticdbClasspath(
    sourceroot: AbsolutePath,
    classpath: Classpath = Classpath(Nil),
    charset: Charset = StandardCharsets.UTF_8,
    fingerprints: Md5Fingerprints = Md5Fingerprints.empty
) extends Semanticdbs {
  val loader = new OpenClassLoader()
  loader.addClasspath(classpath.entries.map(_.toNIO))

  def getSemanticdbPath(scalaOrJavaPath: AbsolutePath): AbsolutePath = {
    semanticdbPath(scalaOrJavaPath).getOrElse(
      throw new NoSuchElementException(scalaOrJavaPath.toString())
    )
  }
  def resourcePath(scalaOrJavaPath: AbsolutePath): RelativePath = {
    mtags.SemanticdbClasspath.fromScalaOrJava(
      scalaOrJavaPath.toRelative(sourceroot)
    )
  }
  def semanticdbPath(scalaOrJavaPath: AbsolutePath): Option[AbsolutePath] = {
    loader.resolve(resourcePath(scalaOrJavaPath).toNIO).map(AbsolutePath.apply)
  }

  def textDocument(scalaOrJavaPath: AbsolutePath): TextDocumentLookup = {
    Semanticdbs.loadTextDocument(
      scalaOrJavaPath,
      sourceroot,
      charset,
      fingerprints,
      path =>
        loader
          .resolve(path.toNIO)
          .map(AbsolutePath(_))
          .map(FoundSemanticDbPath(_, None))
    )
  }
}

object SemanticdbClasspath {
  def toScala(
      workspace: AbsolutePath,
      semanticdb: SemanticdbPath
  ): Option[AbsolutePath] = {
    semanticdb.toNIO.semanticdbRoot.map { root =>
      workspace
        .resolve(
          semanticdb.absolutePath
            .resolveSibling(_.stripSuffix(".semanticdb"))
            .toRelative(AbsolutePath(root))
        )
        .dealias
    }
  }

  def fromScalaOrJava(path: RelativePath): RelativePath = {
    require(path.isScalaOrJavaFilename, path.toString)
    val semanticdbSibling = path.resolveSibling(_ + ".semanticdb")
    val semanticdbPrefix = RelativePath("META-INF").resolve("semanticdb")
    semanticdbPrefix.resolve(semanticdbSibling)
  }
}
