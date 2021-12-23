package scala.meta.internal.mtags

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

import scala.meta.AbsolutePath
import scala.meta.Classpath
import scala.meta.internal.mtags
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.Semanticdbs.FoundSemanticDbPath
import scala.meta.io.RelativePath

final case class SemanticdbClasspath(
    sourceroot: AbsolutePath,
    classpath: Classpath = Classpath(Nil),
    charset: Charset = StandardCharsets.UTF_8,
    fingerprints: Md5Fingerprints = Md5Fingerprints.empty
) extends Semanticdbs {
  val loader = new ClasspathLoader()
  loader.addClasspath(classpath)

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
    loader.load(resourcePath(scalaOrJavaPath))
  }
  def textDocument(scalaOrJavaPath: AbsolutePath): TextDocumentLookup = {
    Semanticdbs.loadTextDocument(
      scalaOrJavaPath,
      sourceroot,
      charset,
      fingerprints,
      path => loader.load(path).map(FoundSemanticDbPath(_, None))
    )
  }
}

object SemanticdbClasspath {
  def toScala(
      workspace: AbsolutePath,
      semanticdb: AbsolutePath
  ): Option[AbsolutePath] = {
    require(
      semanticdb.toNIO.getFileName.toString.endsWith(".semanticdb"),
      semanticdb
    )
    semanticdb.toNIO.semanticdbRoot.map { root =>
      workspace
        .resolve(
          semanticdb
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
