package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.mtags.MtagsEnrichments._

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}
import tests.BasePCSuite
import tests.pc.CrossTestEnrichments._

abstract class BasePcDefinitionSuite extends BasePCSuite {

  def check(
      options: TestOptions,
      original: String,
      compat: Map[String, String] = Map.empty,
  )(implicit loc: Location): Unit = {
    test(options) {
      val filename = "A.scala"
      val uri = s"file:///$filename"

      val (_, offset) = params(original.removeRanges, filename)
      val cleanedCode =
        getExpected(original, compat, scalaVersion).removeRanges.removePos

      import scala.meta.inputs.Position
      import scala.meta.inputs.Input
      val offsetRange =
        Position.Range(Input.String(cleanedCode), offset, offset).toLsp
      val defn = presentationCompiler
        .definition(CompilerOffsetParams(URI.create(uri), cleanedCode, offset))
        .get()
      val edits = defn.locations().asScala.toList.flatMap { location =>
        if (location.getUri() == uri) {
          List(
            new TextEdit(
              new l.Range(
                location.getRange().getStart(),
                location.getRange().getStart(),
              ),
              "<<",
            ),
            new TextEdit(
              new l.Range(
                location.getRange().getEnd(),
                location.getRange().getEnd(),
              ),
              ">>",
            ),
          )
        } else {
          val filename = location.getUri()
          val comment = s"/*$filename*/"
          List(new TextEdit(offsetRange, comment))
        }
      }
      val obtained = TextEdits.applyEdits(cleanedCode, edits)
      val expected = getExpected(original, compat, scalaVersion).removePos
      assertNoDiff(
        obtained,
        expected,
      )
    }
  }

}
