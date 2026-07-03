package diagnostics

import java.net.URI

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.CancelToken
import scala.meta.pc.VirtualFileParams

import munit.Location
import munit.TestOptions
import tests.pc.BaseJavaPCSuite

/**
 * Verifies that the Java presentation compiler reports diagnostics (errors)
 * through `didChange`.
 */
class JavaDiagnosticSuite extends BaseJavaPCSuite {

  check(
    "no-errors",
    """|package example;
       |
       |public class Example {
       |  public static String message = "Hello, World!";
       |}
       |""".stripMargin,
    "",
  )

  check(
    "type-error",
    """|package example;
       |
       |public class Example {
       |  public static int number = "not a number";
       |}
       |""".stripMargin,
    "3:29-3:43 Error: incompatible types: java.lang.String cannot be converted to int",
  )

  check(
    "module-class-no-error",
    """|package example;
       |
       |import com.sun.tools.javac.util.Context;
       |
       |public class Example {
       |  public static Context context = new Context();
       |}
       |""".stripMargin,
    """|2:26-2:31 Error: package com.sun.tools.javac.util is not visible
       |  (package com.sun.tools.javac.util is declared in module jdk.compiler, which does not export it to the unnamed module)""".stripMargin,
  )

  def check(
      name: TestOptions,
      code: String,
      expected: String,
      filename: String = "Example.java",
  )(implicit loc: Location): Unit = {
    test(name) {
      val vparams = new VirtualFileParams {
        override def uri(): URI = URI.create(s"file:///$filename")
        override def text(): String = code
        override def token(): CancelToken = EmptyCancelToken
        override def shouldReturnDiagnostics(): Boolean = true
      }
      val diags = presentationCompiler
        .didChange(vparams)
        .get
        .asScala
        .map { diag =>
          val start = diag.getRange().getStart()
          val end = diag.getRange().getEnd()
          val severity = diag.getSeverity()
          val message = diag.getMessage().getLeft()
          s"${start.getLine()}:${start.getCharacter()}-${end.getLine()}:${end.getCharacter()} $severity: $message"
        }
        .toList

      assertNoDiff(diags.mkString("\n"), expected)
    }
  }
}
