package scala.meta.internal.metals.mcp.tests

import scala.meta.internal.metals.mcp.McpQueryEngine
import scala.meta.internal.metals.mcp.McpQueryEngine.TargetChoice
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import tests.BaseSuite

class McpUsagesSourceSuite extends BaseSuite {

  test("skips-first-target-without-sources") {
    val mainTarget = new BuildTargetIdentifier("file:/workspace/?id=main")
    val testTarget = new BuildTargetIdentifier("file:/workspace/?id=test")
    val mainSource = AbsolutePath.workingDirectory.resolve("main/A.scala")
    assertEquals(
      McpQueryEngine.usagesSource(
        TargetChoice.Guessed(Some(testTarget), Seq(testTarget)),
        path = None,
        targets = Seq(testTarget, mainTarget),
        firstSource = Map(mainTarget -> mainSource).get,
      ),
      Some(mainSource),
    )
  }
}
