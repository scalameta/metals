package tests

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerSyntheticDecorationsParams
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.TextEdits

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.TextEdit

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
        true,
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

      val edits = decorations.map(d => new TextEdit(d.range(), d.label()))
      val obtained = TextEdits.applyEdits(withPkg, edits)

      assertEquals(
        obtained,
        pkgWrap(getExpected(expected, compat, scalaVersion)),
      )

    }
}
