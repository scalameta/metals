package tests

import java.nio.file.Files
import java.nio.file.Paths

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

import scala.meta.internal.builds.GradleBuildTool
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtTarget
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.bsp4j.ScalaTestSuiteSelection
import ch.epfl.scala.bsp4j.ScalaTestSuites

class GradleBuildToolSuite extends BaseSuite {

  private def gradleBuildTool(workspace: AbsolutePath): GradleBuildTool = {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val config = UserConfiguration.default.copy(gradleScript = Some("gradle"))
    GradleBuildTool(() => config, workspace)
  }

  private def mbtTarget(
      name: String,
      sources: Seq[String] = Seq("src/main/java"),
      gradleProjectPath: String = ":",
      classDirectories: Seq[String] = Nil,
      testClassDirectory: Seq[String] = Nil,
  ): MbtTarget =
    MbtTarget(
      name = name,
      id = new BuildTargetIdentifier(s"mbt://namespace/$name"),
      sources = sources,
      globMatchers = Nil,
      scalacOptions = Nil,
      javacOptions = Nil,
      dependencyModules = Nil,
      projectPath = Some(gradleProjectPath),
      classDirectories = classDirectories,
      testClassDirectory = testClassDirectory,
    )

  test("gradle-mbt-compile-command") {
    val workspace = AbsolutePath(Files.createTempDirectory("gradle-mbt"))

    assertEquals(
      gradleBuildTool(workspace)
        .mbtCompileCommand(workspace, mbtTarget("app")),
      List("gradle", "--console=plain", "classes"),
    )
  }

  test("gradle-mbt-compile-command-compiles-test-classes") {
    val workspace = AbsolutePath(Files.createTempDirectory("gradle-mbt"))

    assertEquals(
      gradleBuildTool(workspace)
        .mbtCompileCommand(
          workspace,
          mbtTarget("app", sources = Seq("src/main/java", "src/test/java")),
        ),
      List("gradle", "--console=plain", "testClasses"),
    )
  }

  test("gradle-mbt-run-command-uses-init-script") {
    val workspace = AbsolutePath(Files.createTempDirectory("gradle-mbt"))
    val mainClass = new ScalaMainClass(
      "a.Main",
      List("Bar").asJava,
      List("-Dproperty=Foo").asJava,
    )

    val command =
      gradleBuildTool(workspace)
        .mbtRunCommand(
          workspace,
          mbtTarget("app"),
          mainClass,
        )

    assertEquals(
      command.take(2),
      List("gradle", "--console=plain"),
    )
    assert(command.contains("--init-script"))
    assert(!command.contains("-x"))
    assertEquals(command.last, "__metalsRun")
    val script =
      Files.readString(Paths.get(command(command.indexOf("--init-script") + 1)))
    assert(script.contains("project.tasks.register('__metalsRun', JavaExec)"))
    assert(script.contains("main.runtimeClasspath"))
    assert(script.contains("task.mainClass.set('a.Main')"))
    assert(script.contains("task.jvmArgs(['-Dproperty=Foo'])"))
    assert(script.contains("task.args(['Bar'])"))
  }

  test("gradle-mbt-debug-command-uses-debug-agent") {
    val workspace = AbsolutePath(Files.createTempDirectory("gradle-mbt"))
    val mainClass = new ScalaMainClass("a.Main", Nil.asJava, Nil.asJava)

    val command =
      gradleBuildTool(workspace)
        .mbtDebugCommand(
          workspace,
          mbtTarget("app"),
          mainClass,
          "debug-agent",
        )

    assertEquals(
      command.take(2),
      List("gradle", "--console=plain"),
    )
    assert(command.contains("--init-script"))
    assert(!command.contains("-x"))
    assertEquals(command.last, "__metalsRun")
    val script =
      Files.readString(Paths.get(command(command.indexOf("--init-script") + 1)))
    assert(script.contains("task.jvmArgs(['debug-agent'])"))
    assert(script.contains("task.args([])"))
  }

  test("gradle-mbt-run-command-uses-subproject-task-path") {
    val workspace = AbsolutePath(Files.createTempDirectory("gradle-mbt"))
    val mainClass = new ScalaMainClass("a.Main", Nil.asJava, Nil.asJava)

    val command =
      gradleBuildTool(workspace)
        .mbtRunCommand(
          workspace,
          mbtTarget("app", gradleProjectPath = ":app"),
          mainClass,
        )

    assertEquals(command.last, ":app:__metalsRun")
  }

  test("gradle-mbt-test-command-uses-test-filter") {
    val workspace = AbsolutePath(Files.createTempDirectory("gradle-mbt"))
    val testSuites = new ScalaTestSuites(
      List(
        new ScalaTestSuiteSelection(
          "a.FooTest",
          List("testAddition").asJava,
        )
      ).asJava,
      Nil.asJava,
      Nil.asJava,
    )

    val command =
      gradleBuildTool(workspace)
        .mbtTestCommand(
          workspace,
          mbtTarget("app", gradleProjectPath = ":app"),
          testSuites,
        )

    assertEquals(
      command,
      List(
        "gradle", "--console=plain", ":app:test", "--tests",
        "a.FooTest.testAddition",
      ),
    )
  }

  test("gradle-mbt-test-debug-command-uses-init-script") {
    val workspace = AbsolutePath(Files.createTempDirectory("gradle-mbt"))
    val testSuites = new ScalaTestSuites(
      List(
        new ScalaTestSuiteSelection(
          "a.FooTest",
          Nil.asJava,
        )
      ).asJava,
      List("-Dproperty=Foo").asJava,
      Nil.asJava,
    )

    val command =
      gradleBuildTool(workspace)
        .mbtTestDebugCommand(
          workspace,
          mbtTarget("app"),
          testSuites,
          "debug-agent",
        )

    assertEquals(command.take(2), List("gradle", "--console=plain"))
    assert(command.contains("--init-script"))
    assertEquals(command.takeRight(3), List("test", "--tests", "a.FooTest"))
    val script =
      Files.readString(Paths.get(command(command.indexOf("--init-script") + 1)))
    assert(script.contains("task.jvmArgs(['debug-agent', '-Dproperty=Foo'])"))
  }

  test("gradle-mbt-test-debug-classpath-uses-test-output") {
    val workspace = AbsolutePath(Files.createTempDirectory("gradle-mbt"))
    val testClasses =
      workspace.resolve("build/classes/java/test")
    Files.createDirectories(testClasses.toNIO)

    assertEquals(
      mbtTarget("app")
        .runClassDirectories(
          workspace,
          GradleBuildTool.name,
          includeTests = true,
        ),
      List(testClasses),
    )
  }

  test("gradle-mbt-test-debug-classpath-includes-explicit-test-output") {
    val workspace = AbsolutePath(Files.createTempDirectory("gradle-mbt"))

    assertEquals(
      mbtTarget(
        "app",
        classDirectories = Seq("build/classes/java/main"),
        testClassDirectory = Seq("build/classes/java/test"),
      ).runClassDirectories(
        workspace,
        GradleBuildTool.name,
        includeTests = true,
      ),
      List(
        workspace.resolve("build/classes/java/main"),
        workspace.resolve("build/classes/java/test"),
      ),
    )
  }

  test("gradle-mbt-compile-command-uses-nested-subproject-path") {
    val workspace = AbsolutePath(Files.createTempDirectory("gradle-mbt"))

    val command =
      gradleBuildTool(workspace)
        .mbtCompileCommand(
          workspace,
          mbtTarget("bar", gradleProjectPath = ":foo:bar"),
        )

    assertEquals(command.last, ":foo:bar:classes")
  }

  test("gradle-mbt-run-command-escapes-special-groovy-chars") {
    val workspace = AbsolutePath(Files.createTempDirectory("gradle-mbt"))
    val mainClass = new ScalaMainClass(
      "a.Main",
      List("it's", "line1\nline2").asJava,
      List("-Dpath=C:\\foo").asJava,
    )

    val command =
      gradleBuildTool(workspace)
        .mbtRunCommand(workspace, mbtTarget("app"), mainClass)

    val script =
      Files.readString(Paths.get(command(command.indexOf("--init-script") + 1)))
    assert(script.contains("'it\\'s'"))
    assert(script.contains("'line1\\nline2'"))
    assert(script.contains("'-Dpath=C:\\\\foo'"))
  }
}
