package tests.mcp

import scala.meta.internal.metals.mcp.McpMessages

import munit.FunSuite

class McpMessagesSuite extends FunSuite {

  test("find-dep-version-message-truncates-pre-releases") {
    assertNoDiff(
      McpMessages.FindDep.versionMessage(
        Some("1.0.0"),
        Seq("1.0.0-RC4", "1.0.0-RC3", "1.0.0-RC2", "1.0.0-RC1"),
      ),
      """|Latest stable version found: 1.0.0
         |Development/pre-release matches: 1.0.0-RC4, 1.0.0-RC3, 1.0.0-RC2, [...]
         |""".stripMargin,
    )
  }
}
