package scala.meta.internal.mtags

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

import scala.meta.AbsolutePath
import scala.meta.Classpath
import scala.meta.internal.mtags
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.RelativePath

final case class SemanticdbClasspath(
    sourceroot: AbsolutePath,
    classpath: Classpath = Classpath(Nil),
    charset: Charset = StandardCharsets.UTF_8,
    fingerprints: Md5Fingerprints = Md5Fingerprints.empty
) extends Semanticdbs {
  val loader = new ClasspathLoader()
  loader.addClasspath(classpath)

  def getSemanticdbPath(scalaPath: AbsolutePath): AbsolutePath = {
    semanticdbPath(scalaPath).getOrElse(
      throw new NoSuchElementException(scalaPath.toString())
    )
  }
  def resourcePath(scalaPath: AbsolutePath): RelativePath = {
    mtags.SemanticdbClasspath.fromScala(scalaPath.toRelative(sourceroot))
  }
  def semanticdbPath(scalaPath: AbsolutePath): Option[AbsolutePath] = {
    loader.load(resourcePath(scalaPath))
  }
  def textDocument(scalaPath: AbsolutePath): TextDocumentLookup = {
    Semanticdbs.loadTextDocument(
      scalaPath,
      sourceroot,
      charset,
      fingerprints,
      path => loader.load(path)
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
      workspace.resolve(
        semanticdb
          .resolveSibling(_.stripSuffix(".semanticdb"))
          .toRelative(AbsolutePath(root))
      )
    }
  }
  def fromScala(path: RelativePath): RelativePath = {
    require(path.isScalaFilename, path.toString)
    val semanticdbSibling = path.resolveSibling(_ + ".semanticdb")
    val semanticdbPrefix = RelativePath("META-INF").resolve("semanticdb")
    semanticdbPrefix.resolve(semanticdbSibling)
  }
}
