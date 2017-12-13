package tests.compiler

import java.nio.file.Files
import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.languageserver.Linter
import scala.meta.languageserver.Semanticdbs
import scala.meta.languageserver.providers.SquiggliesProvider
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath
import play.api.libs.json.Json
import scala.meta.internal.inputs._
import langserver.messages.PublishDiagnostics
import org.langmeta.languageserver.InputEnrichments._

object SquiggliesTest extends CompilerSuite {
  val tmp = Files.createTempDirectory("metaserver")
  Files.write(
    tmp.resolve(".scalafix.conf"),
    """
      |rules = [ NoInfer ]
    """.stripMargin.getBytes()
  )
  val linter: Linter = new Linter(AbsolutePath(tmp), System.out)
  def check(name: String, original: String, expected: String): Unit = {
    test(name) {
      val input = Input.VirtualFile(name, original)
      val doc = Semanticdbs.toSemanticdb(input, compiler)
      val PublishDiagnostics(_, diagnostics) :: Nil =
        SquiggliesProvider.squigglies(doc, linter)
      val obtained = diagnostics.map { d =>
        val pos = input.toPosition(d.range)
        pos.formatMessage(d.severity.getOrElse(???).toString, d.message)
      }
      assertNoDiff(obtained.mkString("\n"), expected)
    }
  }

  check(
    "NoInfer.scala",
    """
      |object a {
      |  List(1, "")
      |}
    """.stripMargin,
    """
      |NoInfer.scala:3: Error: Inferred Any
      |  List(1, "")
      |      ^
    """.stripMargin
  )

  check(
    "ParseError.scala",
    """
      |object b {
      |  List(
      |}
    """.stripMargin,
    """
      |ParseError.scala:4: Error: illegal start of simple expression
      |}
      |^
    """.stripMargin
  )
  check(
    "Combined.scala",
    """
      |object b {
      |  List(1, "")
      |  val x: Int = ""
      |}
    """.stripMargin,
    """
      |Combined.scala:4: Error: type mismatch;
      | found   : String("")
      | required: Int
      |  val x: Int = ""
      |               ^
      |Combined.scala:3: Error: Inferred Any
      |  List(1, "")
      |      ^
    """.stripMargin
  )

//  this test messes up with the PC
//  [10..10): [error] constructor a is defined twice;
//  the conflicting constructor a was defined at line 2:10 of 'NoInfer.scala'
//  [20..20): [error] illegal start of simple expression
//  [25..25): [error] constructor definition not allowed here
//  [25..25): [error] self constructor arguments cannot reference unconstructed `this`
//  [31..31): [error] constructor definition not allowed here
//  [31..31): [error] self constructor arguments cannot reference unconstructed `this`
//
//  X tests.compiler.SquiggliesTest.ParseError.scala 49ms
//  java.lang.IllegalArgumentException: 31 is not a valid offset, allowed [0..26]
//  org.langmeta.internal.inputs.InternalInput.offsetToLine(InternalInput.scala:42)
//  org.langmeta.internal.inputs.InternalInput.offsetToLine$(InternalInput.scala:36)
//  org.langmeta.inputs.Input$VirtualFile.offsetToLine(Input.scala:74)
//  org.langmeta.inputs.Position$Range.startLine(Position.scala:38)
//  scala.meta.languageserver.ScalametaEnrichments$XtensionPositionRangeLSP$.toRange$extension(Scalame
//      taEnrichments.scala:142)
//  scala.meta.languageserver.ScalametaEnrichments$XtensionMessageLSP$.toLSP$extension(ScalametaEnrich
//      ments.scala:22)
//  scala.meta.languageserver.providers.SquiggliesProvider$.$anonfun$squigglies$2(SquiggliesProvider.s
//      cala:16)
//  check(
//    "ParseError.scala",
//    """
//      |object a {
//      |  List(
//      |}
//    """.stripMargin,
//    ""
//  )

}
