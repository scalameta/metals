package tests.compiler

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.languageserver.Linter
import scala.meta.languageserver.Semanticdbs
import scala.meta.languageserver.providers.SquiggliesProvider
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath
import scala.meta.internal.inputs._
import langserver.messages.PublishDiagnostics
import org.langmeta.languageserver.InputEnrichments._

object SquiggliesTest extends CompilerSuite {
  val tmp: Path = Files.createTempDirectory("metaserver")
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

  // negative teest: the presentation compiler only runs up to typer
  // and deprecation warnings are reported during a later phase refchecks.
  check(
    "deprecation.scala",
    """
      |import scala.collection.mutable.Stack
      |object c {
      |  val x: Stack[Int] = ???
      |}
    """.stripMargin,
    ""
  )

}
