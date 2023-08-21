package tests

import java.net.URI
import java.{util => ju}

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.metals.CompilerSyntheticDecorationsParams
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.pc.DecorationKind
import scala.meta.pc.InlayHintPart
import scala.meta.pc.SyntheticDecoration

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}

class BaseSyntheticDecorationsSuite extends BasePCSuite {

  def check(
      name: TestOptions,
      base: String,
      expected: String,
      compat: Map[String, String] = Map.empty,
      kind: Option[Int] = None,
  )(implicit location: Location): Unit =
    test(name) {
      def pkgWrap(text: String) =
        if (text.contains("package")) text
        else s"package ${scala.meta.Term.Name(name.name)}\n$text"

      val withPkg = pkgWrap(base)
      val rangeParams = CompilerRangeParams(
        URI.create("file:/Decorations.scala"),
        withPkg,
        0,
        withPkg.length(),
      )
      val pcParams = CompilerSyntheticDecorationsParams(
        rangeParams,
        Nil.asJava,
        true,
        true,
        true,
      )
      val allDecorations = presentationCompiler
        .syntheticDecorations(
          pcParams
        )
        .get()
        .asScala
        .toList

      val decorations = kind match {
        case Some(k) => allDecorations.filter(_.kind == k)
        case None => allDecorations
      }

      val edits = decorations.flatMap(toEdits)

      val obtained = TextEdits.applyEdits(withPkg, edits)

      assertEquals(
        obtained,
        pkgWrap(getExpected(expected, compat, scalaVersion)),
      )

    }

  def checkInferredType(
      name: TestOptions,
      value: String,
      expectedType: String,
      template: String = "",
      compat: Map[String, String] = Map.empty,
  )(implicit location: Location) = {
    val (code, expected) = inferredTypeTemplate(
      value,
      getExpected(expectedType, compat, scalaVersion),
      template,
    )
    check(
      name,
      code,
      expected,
      compat = Map.empty,
      kind = Some(DecorationKind.TypeParameter),
    )
  }

  private def inferredTypeTemplate(
      value: String,
      expectedType: String,
      template: String,
  ): (String, String) = {
    val base = s"""|object Main {
                   |  $template
                   |  def hello[T](t: T) = t
                   |  val x = hello($value)
                   |}
                   |""".stripMargin
    val expected = s"""|object Main {
                       |  $template
                       |  def hello[T](t: T) = t
                       |  val x = hello[$expectedType]($value)
                       |}
                       |""".stripMargin
    (base, expected)
  }

  private def toText(labelParts: ju.List[InlayHintPart]): String =
    labelParts.asScala.map(_.label()).mkString("")

  private def toEdits(decoration: SyntheticDecoration): List[TextEdit] = {
    decoration.kind() match {
      case DecorationKind.ImplicitParameter =>
        new TextEdit(
          decoration.range(),
          "(" + toText(decoration.labelParts()) + ")",
        ) :: Nil
      case DecorationKind.ImplicitConversion =>
        val start = decoration.range().getStart()
        val end = decoration.range().getEnd()
        List(
          new TextEdit(
            new l.Range(start, start),
            toText(decoration.labelParts()) + "(",
          ),
          new TextEdit(new l.Range(end, end), ")"),
        )
      case DecorationKind.TypeParameter =>
        new TextEdit(
          decoration.range(),
          "[" + toText(decoration.labelParts()) + "]",
        ) :: Nil
      case _ => Nil
    }
  }

}
