package scala.meta.internal.mtags

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

final class Mtags(implicit rc: ReportContext) {
  def totalLinesOfCode: Long = javaLines + scalaLines
  def totalLinesOfScala: Long = scalaLines
  def totalLinesOfJava: Long = javaLines

  def allSymbols(path: AbsolutePath, dialect: Dialect): TextDocument = {
    val language = path.toLanguage
    val input = path.toInput

    Mtags.stdLibPatches.patchDocument(
      path,
      index(language, input, dialect)
    )
  }

  def toplevels(
      input: Input.VirtualFile,
      dialect: Dialect = dialects.Scala213
  ): TextDocument = {
    val language = input.toLanguage

    if (language.isJava || language.isScala) {
      val mtags =
        if (language.isJava)
          new JavaToplevelMtags(input)
        else
          new ScalaToplevelMtags(
            input,
            includeInnerClasses = false,
            includeMembers = false,
            dialect
          )
      addLines(language, input.text)
      mtags.index()
    } else {
      TextDocument()
    }
  }

  def topLevelSymbols(
      input: Input.VirtualFile,
      dialect: Dialect = dialects.Scala213
  ): List[String] = {
    toplevels(input, dialect).occurrences.iterator
      .filterNot(_.symbol.isPackage)
      .map(_.symbol)
      .toList
  }

  def index(
      language: Language,
      input: Input.VirtualFile,
      dialect: Dialect
  ): TextDocument = {
    addLines(language, input.text)
    val result =
      if (language.isJava) {
        JavaMtags
          .index(input, includeMembers = true)
          .index()
      } else if (language.isScala) {
        ScalaMtags.index(input, dialect).index()
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
      javaLines += text.linesIterator.length
    } else if (language.isScala) {
      scalaLines += text.linesIterator.length
    }
  }
}
object Mtags {
  def index(input: Input.VirtualFile, dialect: Dialect)(implicit
      rc: ReportContext = EmptyReportContext
  ): TextDocument = {
    new Mtags().index(input.toLanguage, input, dialect)
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

  def allToplevels(
      input: Input.VirtualFile,
      dialect: Dialect,
      includeMembers: Boolean = true
  )(implicit rc: ReportContext = EmptyReportContext): TextDocument = {
    input.toLanguage match {
      case Language.JAVA =>
        new JavaMtags(input, includeMembers = true).index()
      case Language.SCALA =>
        val mtags =
          new ScalaToplevelMtags(input, true, includeMembers, dialect)
        mtags.index()
      case _ =>
        TextDocument()
    }
  }

  def toplevels(
      input: Input.VirtualFile,
      dialect: Dialect = dialects.Scala213
  )(implicit rc: ReportContext = EmptyReportContext): TextDocument = {
    new Mtags().toplevels(input, dialect)
  }

  def topLevelSymbols(
      input: Input.VirtualFile,
      dialect: Dialect = dialects.Scala213
  )(implicit rc: ReportContext = EmptyReportContext): List[String] = {
    new Mtags().topLevelSymbols(input, dialect)
  }

  /**
   * Scala 3 has a specific package that adds / replaces some symbols in scala.Predef + scala.language
   * https://github.com/lampepfl/dotty/blob/main/library/src/scala/runtime/stdLibPatches/
   * We need to do the same to correctly provide location for symbols obtained from semanticdb.
   */
  object stdLibPatches {
    val packageName = "scala/runtime/stdLibPatches"

    def isScala3Library(jar: AbsolutePath): Boolean =
      jar.filename.startsWith("scala3-library_3")

    def isScala3LibraryPatchSource(file: AbsolutePath): Boolean = {
      file.jarPath.exists(
        isScala3Library(_)
      ) && file.parent.filename == "stdLibPatches"
    }

    def patchSymbol(sym: String): String =
      sym.replace(packageName, "scala")

    def patchDocument(
        file: AbsolutePath,
        doc: TextDocument
    ): TextDocument = {
      if (isScala3LibraryPatchSource(file)) {
        val occs =
          doc.occurrences.map(occ => occ.copy(symbol = patchSymbol(occ.symbol)))

        doc.copy(occurrences = occs)
      } else doc
    }

  }
}
