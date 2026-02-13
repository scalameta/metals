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

  test("parse-warn") {

    val original =
      """|/Users/tgodzik/Documents/workspaces/hello-world/build.sbt:35: warning: method sonatypeRepo in class ResolverFunctions is deprecated (since 1.7.0): Sonatype OSS Repository Hosting (OSSRH) was sunset on 2025-06-30; remove this resolver. If snapshots are required, use:
         |   resolvers += Resolver.sonatypeCentralSnapshots
         | 
         | resolvers += Resolver.sonatypeRepo("public")
         |
         |""".stripMargin

    val diagnostics = BloopDiagnosticsParser
      .getDiagnosticsFromErrors(original.split('\n'))
      .toList

    diagnostics match {
      case List(diagnostics) =>
        val diagnostic = diagnostics.getDiagnostics().get(0)
        assertNoDiff(
          diagnostic.toString(),
          """|Diagnostic [
             |  range = Range [
             |    start = Position [
             |      line = 34
             |      character = 0
             |    ]
             |    end = Position [
             |      line = 34
             |      character = 2147483647
             |    ]
             |  ]
             |  severity = Warning
             |  code = null
             |  codeDescription = null
             |  source = "sbt"
             |  message = Either [
             |    left = warning: method sonatypeRepo in class ResolverFunctions is deprecated (since 1.7.0): Sonatype OSS Repository Hosting (OSSRH) was sunset on 2025-06-30; remove this resolver. If snapshots are required, use:
             |    right = null
             |  ]
             |  tags = null
             |  relatedInformation = null
             |  data = null
             |]
             |""".stripMargin,
        )

      case _ =>
        fail("Expected 1 diagnostic")
    }

  }
}
