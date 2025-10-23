package tests.pc

import java.net.URI
import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.pc.OffsetParams

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}

abstract class BaseJavaDefinitionSuite extends BaseJavaPCSuite {

  implicit class XtensionString(s: String) {
    def removeRanges: String =
      s.replace("<<", "")
        .replace(">>", "")
        .replaceAll("/\\*.+\\*/", "")

    def removePos: String = s.replace("@@", "")
  }

  def isTypeDefinitionSuite: Boolean

  def definitions(offsetParams: OffsetParams): List[l.Location] = {
    val defn = if (isTypeDefinitionSuite) {
      presentationCompiler
        .typeDefinition(offsetParams)
    } else {
      presentationCompiler
        .definition(offsetParams)
    }
    defn.get().locations().asScala.toList
  }

  def check(
      testOpt: TestOptions,
      original: String,
      automaticPackage: Boolean = true,
      uri: URI = Paths.get("Definition.java").toUri(),
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val filename = "Definition.java"
      val pkg = packageName(testOpt.name)
      val noRange = original.removeRanges

      val packagePrefix =
        if (automaticPackage) s"package $pkg;\n"
        else ""

      val codeOriginal = packagePrefix + noRange
      val (cleanedCode, offset) = params(codeOriginal, filename)

      import scala.meta.inputs.Position
      import scala.meta.inputs.Input
      val offsetRange =
        Position.Range(Input.String(cleanedCode), offset, offset).toLsp

      val locs = definitions(
        CompilerOffsetParams(URI.create(uri.toString), cleanedCode, offset)
      )

      val edits = locs.flatMap { location =>
        if (location.getUri() == uri.toString) {
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
      val expectedCode =
        if (automaticPackage) packagePrefix + original.removePos
        else original.removePos

      assertNoDiff(
        obtained,
        expectedCode,
      )
    }
  }

  private def packageName(name: String): String = {
    name.toLowerCase.split(" ").mkString("_").replaceAll("-", "_")
  }
}
