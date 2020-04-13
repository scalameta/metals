package tests.pc

import tests.BasePCSuite
import munit.Location
import org.eclipse.{lsp4j => l}
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits

abstract class BasePcDefinitionSuite extends BasePCSuite {

  protected def definitions(
      offsetParams: CompilerOffsetParams
  ): List[l.Location]

  def check(
      name: String,
      original: String,
      compat: Map[String, String] = Map.empty
  )(implicit loc: Location): Unit = {
    test(name) {
      val uri = "A.scala"
      val (code, offset) = codeAndRequestOffset(original, uri)
      val locations = definitions(CompilerOffsetParams(uri, code, offset))
      val obtained = codeWithLocations(code, uri, offset, locations)
      val expected = original.replaceAllLiterally("@@", "")
      assertNoDiff(obtained, getExpected(expected, compat))
    }
  }

  private def codeAndRequestOffset(
      original: String,
      uri: String
  ): (String, Int) = {
    val (code, offset) = params(
      original
        .replaceAllLiterally("<<", "")
        .replaceAllLiterally(">>", ""),
      uri
    )
    (code, offset)
  }

  private def codeWithLocations(
      code: String,
      uri: String,
      offset: Int,
      locations: List[l.Location]
  ): String = {
    val edits = locations.flatMap { location =>
      locationEdit(code, uri, offset, location)
    }
    TextEdits.applyEdits(code, edits)
  }

  private def locationEdit(
      code: String,
      uri: String,
      offset: Int,
      location: l.Location
  ): List[l.TextEdit] = {
    if (location.getUri == uri) {
      List(
        new l.TextEdit(
          new l.Range(
            location.getRange.getStart,
            location.getRange.getStart
          ),
          "<<"
        ),
        new l.TextEdit(
          new l.Range(
            location.getRange.getEnd,
            location.getRange.getEnd
          ),
          ">>"
        )
      )
    } else {
      val filename = location.getUri
      val comment = s"/*$filename*/"
      if (code.contains(comment)) {
        Nil
      } else {
        import scala.meta.inputs.Position
        import scala.meta.inputs.Input
        import scala.meta.internal.mtags.MtagsEnrichments._
        val offsetRange =
          Position.Range(Input.String(code), offset, offset).toLSP
        List(new l.TextEdit(offsetRange, comment))
      }
    }
  }
}
