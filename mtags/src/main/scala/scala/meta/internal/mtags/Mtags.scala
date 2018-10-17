package scala.meta.internal.mtags

import scala.meta.inputs.Input
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.mtags.Enrichments._

class Mtags {
  def totalLinesOfCode: Long = javaLines + scalaLines
  def totalLinesOfScala: Long = scalaLines
  def totalLinesOfJava: Long = javaLines
  def toplevels(input: Input.VirtualFile): List[String] = {
    val language = input.toLanguage
    if (language.isJava) {
      // NOTE(olafur): this is incorrect in the following cases:
      // - the source file has multiple top-level classes, in which case we
      //   don't index the package private classes.
      // - if the path is not relative to the source directory, in which case
      //   the produced symbol is incorrect.
      val toplevelClass = input.path.stripPrefix("/").stripSuffix(".java") + "#"
      List(toplevelClass)
    } else if (language.isScala) {
      addLines(language, input.text)
      new ScalaToplevelMtags(input)
        .index()
        .occurrences
        .iterator
        .filterNot(_.symbol.isPackage)
        .map(_.symbol)
        .toList
    } else {
      Nil
    }
  }
  def index(language: Language, input: Input.VirtualFile): TextDocument = {
    addLines(language, input.text)
    val result =
      if (language.isJava) {
        JavaMtags.index(input).index()
      } else if (language.isScala) {
        ScalaMtags.index(input).index()
      } else {
        TextDocument()
      }
    result
      .withUri(input.path)
      .withText(input.text)
  }
  private var javaLines: Long = 0L
  private var scalaLines: Long = 0L
  private def addLines(language: Language, text: String): Unit = {
    if (language.isJava) {
      javaLines += text.lines.length
    } else if (language.isScala) {
      scalaLines += text.lines.length
    }
  }
}
object Mtags {
  def index(input: Input.VirtualFile): TextDocument = {
    new Mtags().index(input.toLanguage, input)
  }

  def toplevels(document: TextDocument): List[String] = {
    document.occurrences.iterator
      .filter { occ =>
        occ.role.isDefinition &&
        Symbol(occ.symbol).isToplevel
      }
      .map(_.symbol)
      .toList
  }

  def toplevels(input: Input.VirtualFile): List[String] = {
    new Mtags().toplevels(input)
  }
}
