package scala.meta.internal.mtags

import java.util.concurrent.ConcurrentHashMap

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.internal.mtags.proto.ProtobufToplevelMtags
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.Schema
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

final class Mtags(implicit rc: ReportContext) {
  def totalLinesOfCode: Long = lineCounts.values.asScala.sum
  def totalSymbols: Long = symbolsCount
  def totalLinesOfScala: Long =
    lineCounts.getOrDefault(Semanticdb.Language.SCALA, 0L)
  def totalLinesOfProtobuf: Long =
    lineCounts.getOrDefault(Semanticdb.Language.PROTOBUF, 0L)
  def totalLinesOfJava: Long =
    lineCounts.getOrDefault(Semanticdb.Language.JAVA, 0L)

  def allSymbols(path: AbsolutePath, dialect: Dialect): TextDocument = {
    index(path, dialect)
  }

  def toplevels(
      path: AbsolutePath,
      dialect: Dialect = dialects.Scala213
  ): TextDocument = {
    val input = path.toInput
    val language = input.toJLanguage

    if (language.isJava || language.isScala) {
      val mtags =
        if (language.isJava)
          new JavaToplevelMtags(input, includeInnerClasses = false)
        else
          new ScalaToplevelMtags(
            input,
            includeInnerClasses = false,
            includeMembers = false,
            dialect
          )
      addLines(language, input.text)
      Mtags.stdLibPatches.patchDocument(
        path,
        mtags.index()
      )
    } else if (language.isProtobuf) {
      new ProtobufToplevelMtags(input, includeGeneratedSymbols = true).index()
    } else {
      TextDocument()
    }
  }

  def indexWithOverrides(
      path: AbsolutePath,
      dialect: Dialect = dialects.Scala213,
      includeMembers: Boolean = false
  ): (TextDocument, MtagsIndexer.AllOverrides) = {
    val input = path.toInput
    val language = input.toJLanguage
    if (language.isJava || language.isScala) {
      val mtags =
        if (language.isJava)
          new JavaToplevelMtags(input, includeInnerClasses = true)
        else if (language.isScala)
          new ScalaToplevelMtags(
            input,
            includeInnerClasses = true,
            includeMembers,
            dialect
          )
        else
          new EmptyToplevelMtags(input)

      addLines(language, input.text)
      val doc =
        Mtags.stdLibPatches.patchDocument(
          path,
          mtags.index()
        )
      val overrides = mtags.overrides()
      (doc, overrides)
    } else if (language.isProtobuf) {
      new ProtobufToplevelMtags(input, includeGeneratedSymbols = true).index()
      (TextDocument(), Nil)
    } else {
      (TextDocument(), Nil)
    }
  }

  def topLevelSymbols(
      path: AbsolutePath,
      dialect: Dialect = dialects.Scala213
  ): List[String] = {
    toplevels(path, dialect).occurrences.iterator
      .filterNot(_.symbol.isPackage)
      .map(_.symbol)
      .toList
  }

  def index(
      path: AbsolutePath,
      dialect: Dialect
  ): TextDocument = {
    val language = path.toJLanguage
    val input = path.toInput
    addLines(language, input.text)
    val result =
      if (language == Semanticdb.Language.JAVA) {
        JavaMtags
          .index(input, includeMembers = true)
          .index()
      } else if (language == Semanticdb.Language.SCALA) {
        ScalaMtags.index(input, dialect).index()
      } else if (language == Semanticdb.Language.PROTOBUF) {
        new ProtobufToplevelMtags(input, includeGeneratedSymbols = true).index()
      } else {
        TextDocument()
      }
    Mtags.stdLibPatches
      .patchDocument(
        path,
        result
      )
      .withUri(input.path)
      .withText(input.text)
  }

  def indexToplevelSymbols(
      language: Semanticdb.Language,
      input: Input.VirtualFile,
      dialect: Dialect
  ): TextDocument = {
    addLines(language, input.text)
    val result =
      if (language == Semanticdb.Language.JAVA) {
        new JavaToplevelMtags(input, includeInnerClasses = true)
          .index()
      } else if (language == Semanticdb.Language.SCALA) {
        new ScalaToplevelMtags(
          input,
          includeInnerClasses = true,
          includeMembers = true,
          dialect
        ).index()
      } else if (language == Semanticdb.Language.PROTOBUF) {
        new ProtobufToplevelMtags(input, includeGeneratedSymbols = true).index()
      } else {
        TextDocument()
      }
    symbolsCount += result.occurrences.length
    result
      .withUri(input.path)
      .withText("")
      .withSchema(Schema.SEMANTICDB4)
      // NOTE: we loose the protobuf language here, can only recover it from the URI.
      .withLanguage(language.toLanguage)
  }
  private var symbolsCount: Long = 0L
  private val lineCounts = new ConcurrentHashMap[Semanticdb.Language, Long]

  private def addLines(language: Semanticdb.Language, text: String): Unit = {
    val linesCount = text.linesIterator.length
    lineCounts.compute(
      language,
      (_, existingLinesCount) =>
        linesCount + Option(existingLinesCount).getOrElse(0L)
    )
  }
}
object Mtags {
  def index(path: AbsolutePath, dialect: Dialect)(implicit
      rc: ReportContext = EmptyReportContext
  ): TextDocument = {
    new Mtags().index(path, dialect)
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
  )(implicit rc: ReportContext = EmptyReportContext): TextDocument =
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

  def toplevels(
      path: AbsolutePath,
      dialect: Dialect = dialects.Scala213
  )(implicit rc: ReportContext = EmptyReportContext): TextDocument = {
    new Mtags().toplevels(path, dialect)
  }

  def indexWithOverrides(
      path: AbsolutePath,
      dialect: Dialect = dialects.Scala213,
      includeMembers: Boolean = false
  )(implicit
      rc: ReportContext = EmptyReportContext
  ): (TextDocument, MtagsIndexer.AllOverrides) = {
    new Mtags().indexWithOverrides(path, dialect, includeMembers)
  }

  def topLevelSymbols(
      path: AbsolutePath,
      dialect: Dialect = dialects.Scala213
  )(implicit rc: ReportContext = EmptyReportContext): List[String] = {
    new Mtags().topLevelSymbols(path, dialect)
  }

  /**
   * Scala 3 has a specific package that adds / replaces some symbols in scala.Predef + scala.language
   * https://github.com/lampepfl/dotty/blob/main/library/src/scala/runtime/stdLibPatches/
   * We need to do the same to correctly provide location for symbols obtained from semanticdb.
   */
  private object stdLibPatches {
    val packageName = "scala/runtime/stdLibPatches"

    private def isScala3Library(jar: AbsolutePath): Boolean =
      jar.filename.startsWith("scala3-library_3")

    private def isScala3LibraryPatchSource(file: AbsolutePath): Boolean = {
      !file.parent.isRoot &&
      file.parent.filename == "stdLibPatches" &&
      file.jarPath.exists(isScala3Library(_))
    }

    private def patchSymbol(sym: String): String =
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
