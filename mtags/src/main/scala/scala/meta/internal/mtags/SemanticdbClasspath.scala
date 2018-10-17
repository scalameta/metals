package scala.meta.internal.mtags

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import scala.meta.AbsolutePath
import scala.meta.io.RelativePath
import scala.meta.Classpath
import scala.meta.internal.mtags.Enrichments._

class SemanticdbClasspath(
    sourceroot: AbsolutePath,
    classpath: Classpath,
    charset: Charset = StandardCharsets.UTF_8
) {
  private val loader = new ClasspathLoader(classpath)
  def getSemanticdbPath(scalaPath: AbsolutePath): AbsolutePath = {
    semanticdbPath(scalaPath).getOrElse(
      throw new NoSuchElementException(scalaPath.toString())
    )
  }
  def semanticdbPath(scalaPath: AbsolutePath): Option[AbsolutePath] = {
    loader.load(fromScala(scalaPath.toRelative(sourceroot)))
  }
  def textDocument(scalaPath: AbsolutePath): TextDocumentLookup = {
    val scalaRelativePath = scalaPath.toRelative(sourceroot)
    val semanticdbRelativePath = fromScala(scalaRelativePath)
    loader.load(semanticdbRelativePath) match {
      case None =>
        TextDocumentLookup.NotFound(scalaPath)
      case Some(semanticdbPath) =>
        Semanticdbs.loadTextDocument(
          scalaPath,
          scalaRelativePath,
          semanticdbPath,
          charset
        )
    }
  }

  private def fromScala(path: RelativePath): RelativePath = {
    require(path.toNIO.toLanguage.isScala, path.toString)
    val semanticdbSibling = path.resolveSibling(_ + ".semanticdb")
    val semanticdbPrefix = RelativePath("META-INF").resolve("semanticdb")
    semanticdbPrefix.resolve(semanticdbSibling)
  }
}
