package tests.parsing

import scala.meta.internal.parsing.BloopDiagnosticsParser

import tests.BaseSuite

class BloopDiagnosticsParserSuite extends BaseSuite {
  test("parse multiple errors") {

    val original =
      """/project/build.sbt:15: error: type mismatch;
        | found   : Int(42)
        | required: String
        |val x: String = 42
        |                ^
        |/project/build.sbt:20: error: value ++== is not a member of sbt.SettingKey[Seq[sbt.librarymanagement.ModuleID]]
        |  Expression does not convert to assignment because receiver is not assignable.
        |    libraryDependencies ++== deps
        |                        ^
        |sbt.compiler.EvalException: Type error in expression""".stripMargin

    val diagnostics = BloopDiagnosticsParser
      .getDiagnosticsFromErrors(original.split('\n'))
      .toList

    assert(
      diagnostics.size == 1 && diagnostics.exists(_.getDiagnostics.size() == 2)
    )
  }
}
