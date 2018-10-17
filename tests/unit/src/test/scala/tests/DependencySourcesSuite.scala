package tests

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import scala.meta.internal.metals.DependencySources

object DependencySourcesSuite extends BaseTablesSuite {
  def dependencySources: DependencySources = tables.dependencySources
  test("basic") {
    val textDocument = workspace.resolve("a.scala")
    val buildTarget = new BuildTargetIdentifier("core")
    val buildTarget2 = new BuildTargetIdentifier("core2")
    assertEquals(
      dependencySources.setBuildTarget(textDocument, buildTarget),
      1
    )
    assertEquals(
      dependencySources.getBuildTarget(textDocument).get,
      buildTarget
    )
    assertEquals(
      dependencySources.setBuildTarget(textDocument, buildTarget2),
      1
    )
    assertEquals(
      dependencySources.getBuildTarget(textDocument).get,
      buildTarget2
    )
  }
}
