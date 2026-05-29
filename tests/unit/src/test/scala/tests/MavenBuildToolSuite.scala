package tests

import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.util.Properties

import scala.meta.internal.builds.MavenBuildTool
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtTarget
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaMainClass

class MavenBuildToolSuite extends BaseSuite {

  private def mavenBuildTool(workspace: AbsolutePath): MavenBuildTool = {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val config = UserConfiguration.default.copy(mavenScript = Some("mvn"))
    MavenBuildTool(
      () => config,
      workspace,
      new ShellRunner(Time.system, EmptyWorkDoneProgress, () => config),
      ec,
    )
  }

  private def mbtTarget(
      name: String,
      classDirectory: String,
      configurations: Seq[String] = Nil,
      projectDir: Option[AbsolutePath] = None,
  ): MbtTarget =
    MbtTarget(
      name = name,
      id = new BuildTargetIdentifier(s"mbt://namespace/$name"),
      sources = Nil,
      globMatchers = Nil,
      scalacOptions = Nil,
      javacOptions = Nil,
      dependencyModules = Nil,
      classDirectories = Seq(classDirectory),
      projectPath = projectDir.map(_.toString),
      configurations = configurations,
    )

  test("maven-mbt-compile-command") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt"))
    val target = mbtTarget(
      "com.example:app:1.0.0",
      "target/classes",
      configurations = Seq("-P", "dev,ci"),
    )

    assertEquals(
      mavenBuildTool(workspace).mbtCompileCommand(workspace, target),
      List(
        "mvn", "-q", "-P", "dev,ci", "-pl", ":app", "--also-make", "install",
        "-DskipTests", "-Denforcer.skip=true",
      ),
    )
  }

  test("maven-base-command-uses-project-wrapper") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt"))
    val mvnwName = if (Properties.isWin) "mvnw.cmd" else "mvnw"
    val mvnw = workspace.resolve(mvnwName)
    mvnw.writeText("#!/bin/sh\n")

    assertEquals(
      mavenBuildTool(workspace).mavenBaseCommand(),
      List(s"./$mvnwName"),
    )
  }

  test("maven-mbt-run-command") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt"))
    val mainClass = new ScalaMainClass(
      "a.Main",
      List("Bar").asJava,
      List("-Dproperty=Foo").asJava,
    )

    assertEquals(
      mavenBuildTool(workspace).mbtRunCommand(
        workspace,
        mbtTarget("com.example:app:1.0.0", "target/classes"),
        mainClass,
      ),
      List(
        "mvn", "-q", "exec:exec", "-Dexec.executable=java",
        "-Dexec.args=-Dproperty=Foo -classpath %classpath a.Main Bar",
      ),
    )
  }

  test("maven-mbt-debug-command-uses-module-pom") {
    val workspace = AbsolutePath(Files.createTempDirectory("maven-mbt"))
    val modulePom = workspace.resolve("app/pom.xml")
    modulePom.parent.createDirectories()
    modulePom.writeText("<project></project>")
    val mainClass = new ScalaMainClass(
      "a.Main",
      List("Bar").asJava,
      List("-Dproperty=Foo").asJava,
    )

    assertEquals(
      mavenBuildTool(workspace).mbtDebugCommand(
        workspace,
        mbtTarget(
          "com.example:app:1.0.0",
          "app/target/classes",
          projectDir = Some(workspace.resolve("app")),
        ),
        mainClass,
        "debug-agent",
      ),
      List(
        "mvn",
        "-q",
        "-f",
        modulePom.toString,
        "exec:exec",
        "-Dexec.executable=java",
        "-Dexec.args=debug-agent -Dproperty=Foo -classpath %classpath a.Main Bar",
      ),
    )
  }
}
