package tests

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerSyntheticDecorationsParams
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.pc.DecorationKind

import munit.Location
import munit.TestOptions

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
      val vFile = CompilerVirtualFileParams(
        URI.create("file:/Decorations.scala"),
        withPkg,
      )

      val pcParams = CompilerSyntheticDecorationsParams(
        vFile,
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

      val edits = TestSyntheticDecorations.toEdits(decorations)
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
  )(implicit location: Location): Unit = {
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

}
