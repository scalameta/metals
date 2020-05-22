package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.mtags.MtagsEnrichments._

import munit.Location
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}
import tests.BasePCSuite

abstract class BasePcDefinitionSuite extends BasePCSuite {
  def check(
      name: String,
      original: String,
      compat: Map[String, String] = Map.empty
  )(implicit loc: Location): Unit = {
    test(name) {
      val noRange = original
        .replace("<<", "")
        .replace(">>", "")
      val filename = "A.scala"
      val uri = s"file:///$filename"
      val (code, offset) = params(noRange, filename)
      import scala.meta.inputs.Position
      import scala.meta.inputs.Input
      val offsetRange = Position.Range(Input.String(code), offset, offset).toLSP
      val defn = presentationCompiler
        .definition(CompilerOffsetParams(URI.create(uri), code, offset))
        .get()
      val edits = defn.locations().asScala.toList.flatMap { location =>
        if (location.getUri() == uri) {
          List(
            new TextEdit(
              new l.Range(
                location.getRange().getStart(),
                location.getRange().getStart()
              ),
              "<<"
            ),
            new TextEdit(
              new l.Range(
                location.getRange().getEnd(),
                location.getRange().getEnd()
              ),
              ">>"
            )
          )
        } else {
          val filename = location.getUri()
          val comment = s"/*$filename*/"
          if (code.contains(comment)) {
            Nil
          } else {
            List(new TextEdit(offsetRange, comment))
          }
        }
      }
      val obtained = TextEdits.applyEdits(code, edits)
      val expected = original.replace("@@", "")
      assertNoDiff(
        obtained,
        getExpected(expected, compat, scalaVersion)
      )
    }
  }
}
