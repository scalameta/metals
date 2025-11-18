package tests

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.JavacMtags
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.mtags.SemanticdbPrinter
import scala.meta.internal.mtags.SemanticdbRanges._
import scala.meta.internal.{semanticdb => s}

import tests.InputFile

class JavacMtagsExpectSuite(assertCompat: Boolean)
    extends DirectoryExpectSuite("mtags-javac") {
  def this() = this(true)
  override lazy val input: InputProperties = InputProperties.scala2()
  lazy val semanticdbs: SemanticdbClasspath = SemanticdbClasspath(
    input.sourceroot,
    input.classpath,
    input.semanticdbTargets,
  )
  def testCases(): List[ExpectTestCase] = for {
    file <- input.allFiles
    if file.file.isJava
    // if file.file.toNIO.endsWith("JavaInterface.java")
  } yield {
    ExpectTestCase(
      file,
      () => {
        val indexer = new JavacMtags(
          file.input,
          includeMembers = true,
          includeFuzzyReferences = true,
          includeUniqueFuzzyReferences = false,
        )(EmptyReportContext)

        val sdoc = indexer.index()
        val mdoc = Semanticdb.TextDocument.parseFrom(sdoc.toByteArray)
        if (assertCompat) {
          assertCompatWithSemanticdbJavac(file)
        }
        SemanticdbPrinter.printDocument(mdoc, includeInfo = true)
      },
    )
  }

  // Asserts that whatever mtags-javac produces matches exactly what semanticdb-javac produces,
  // ensuring consistent code navigation when mixing symbols from both indexers including
  // trickier corner cases like method overloading, implicit constructors, records, etc.
  private def assertCompatWithSemanticdbJavac(file: InputFile): Unit = {
    val mtagsNew = new JavacMtags(
      file.input,
      includeMembers = true,
      includeFuzzyReferences = true,
      includeUniqueFuzzyReferences = false,
    )(EmptyReportContext)
    val mtagsNewDoc = mtagsNew.index()
    val mtagsNewDocClean = mtagsNewDoc.copy(
      symbols = Nil,
      occurrences = mtagsNewDoc.occurrences.filter(occ =>
        occ.role == s.SymbolOccurrence.Role.DEFINITION
      ),
    )
    val mtagsOld = semanticdbs.textDocument(file.file).get
    val mtagsOldClean = mtagsOld.copy(
      symbols = Nil,
      occurrences = mtagsOld.occurrences.filter(occ =>
        occ.role == s.SymbolOccurrence.Role.DEFINITION &&
          !occ.symbol.startsWith("local")
      ),
    )
    val sdocNew =
      Semanticdb.TextDocument.parseFrom(mtagsNewDocClean.toByteArray)
    val sdocOld =
      Semanticdb.TextDocument.parseFrom(mtagsOldClean.toByteArray)
    // pprint.log(sdocNew.getOccurrencesList().asScala)
    assertEquals(
      sdocNew.getOccurrencesList().asScala.sorted,
      sdocOld.getOccurrencesList().asScala.sorted,
    )
  }
}
