package tests

import java.nio.file.Files

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.DependencySources
import scala.meta.internal.metals.TargetData
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.SourceItem
import ch.epfl.scala.bsp4j.SourceItemKind

class DependencySourcesSuite extends BaseTablesSuite {
  def dependencySources: DependencySources = tables.dependencySources
  test("basic") {
    val textDocument = workspace.resolve("a.scala")
    val buildTarget = new BuildTargetIdentifier("core")
    val buildTarget2 = new BuildTargetIdentifier("core2")
    assertDiffEqual(
      dependencySources.setBuildTarget(textDocument, buildTarget),
      1,
    )
    assertDiffEqual(
      dependencySources.getBuildTarget(textDocument).get,
      buildTarget,
    )
    assertDiffEqual(
      dependencySources.setBuildTarget(textDocument, buildTarget2),
      1,
    )
    assertDiffEqual(
      dependencySources.getBuildTarget(textDocument).get,
      buildTarget2,
    )
  }

  test("source-item-symlink-target-lookup") {
    val buildTarget = new BuildTargetIdentifier("core")
    val data = new TargetData()
    val (aliasRoot, aliasFile) = symlinkedSourceRoot()
    data.addSourceItem(aliasRoot, buildTarget)
    val buildTargets = BuildTargets.from(workspace, data, tables)

    assertEquals(buildTargets.inverseSources(aliasFile), Some(buildTarget))
  }

  test("dependency-source-symlink-target-lookup") {
    val buildTarget = new BuildTargetIdentifier("core")
    val data = new TargetData()
    val (aliasRoot, aliasFile) = symlinkedSourceRoot()
    data.addDependencySource(aliasRoot, buildTarget)
    val buildTargets = BuildTargets.from(workspace, data, tables)
    val realFile = AbsolutePath(aliasFile.toNIO.toRealPath())

    assertEquals(
      buildTargets.dependencySourceBuildTargets(realFile).map(_.toList),
      Some(List(buildTarget)),
    )
    assertEquals(buildTargets.inverseSources(realFile), Some(buildTarget))
    assert(buildTargets.isDependencySource(realFile))
  }

  test("generated-source-symlink-target-lookup") {
    val buildTarget = new BuildTargetIdentifier("core")
    val data = new TargetData()
    val (aliasRoot, aliasFile) = symlinkedSourceRoot()
    data.addSourceItem(
      new SourceItem(
        aliasRoot.toURI.toString,
        SourceItemKind.DIRECTORY,
        true,
      ),
      buildTarget,
    )
    val buildTargets = BuildTargets.from(workspace, data, tables)
    val realFile = AbsolutePath(aliasFile.toNIO.toRealPath())

    assert(buildTargets.checkIfGeneratedSource(realFile.toNIO))
  }

  private def symlinkedSourceRoot() = {
    val real = workspace.resolve("real")
    val alias = workspace.resolve("alias")
    Files.createDirectories(real.toNIO)
    Files.createSymbolicLink(alias.toNIO, real.toNIO)
    val aliasRoot = alias.resolve("root")
    val aliasFile = aliasRoot.resolve("com/foo/Foo.java")
    Files.createDirectories(aliasFile.toNIO.getParent)
    Files.writeString(aliasFile.toNIO, "package com.foo; public class Foo {}")
    (aliasRoot, aliasFile)
  }
}
