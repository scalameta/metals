package tests.debug

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.JUnitPlatformTestMain
import scala.meta.io.AbsolutePath

import coursierapi.Dependency
import coursierapi.Fetch
import tests.BaseSuite

class JupiterRunnerSuite extends BaseSuite {
  private lazy val dependencies = Fetch
    .create()
    .withDependencies(
      Dependency.of("org.junit.jupiter", "junit-jupiter-engine", "5.8.2"),
      Dependency.of("org.junit.platform", "junit-platform-launcher", "1.8.2"),
      Dependency.of("org.junit.jupiter", "junit-jupiter-params", "5.8.2"),
    )
    .fetch()
    .asScala

  private val selectionSource =
    """|class ParentTest {
       |  @org.junit.jupiter.api.Test
       |  void inherited() { System.out.println("INHERITED"); }
       |}
       |public class SelectionTest extends ParentTest {
       |  @org.junit.jupiter.api.Test
       |  void selected() { System.out.println("SELECTED"); }
       |  @org.junit.jupiter.api.Test
       |  void unrelated() { throw new AssertionError("UNRELATED"); }
       |  @org.junit.jupiter.params.ParameterizedTest
       |  @org.junit.jupiter.params.provider.ValueSource(strings = {"first", "second"})
       |  void parameterized(String value) { System.out.println("PARAMETER-" + value); }
       |  @org.junit.jupiter.api.Nested
       |  class NestedTests {
       |    @org.junit.jupiter.api.Test
       |    void nested() { System.out.println("NESTED"); }
       |  }
       |}
       |class WholeClassTest {
       |  @org.junit.jupiter.api.Test
       |  void first() { System.out.println("CLASS-FIRST"); }
       |  @org.junit.jupiter.api.Test
       |  void second() { System.out.println("CLASS-SECOND"); }
       |}
       |""".stripMargin

  for (
    (name, arguments, expectedExit, expectedOutput) <- List(
      (
        "select-method",
        List("SelectionTest", "1", "selected"),
        0,
        List("SELECTED"),
      ),
      (
        "select-inherited-method",
        List("SelectionTest", "1", "inherited"),
        0,
        List("INHERITED"),
      ),
      (
        "select-parameterized-method",
        List("SelectionTest", "1", "parameterized"),
        0,
        List("PARAMETER-first", "PARAMETER-second"),
      ),
      (
        "select-nested-method",
        List("SelectionTest$NestedTests", "1", "nested"),
        0,
        List("NESTED"),
      ),
      (
        "select-class",
        List("WholeClassTest", "0"),
        0,
        List("CLASS-FIRST", "CLASS-SECOND"),
      ),
      (
        "reject-unknown-method",
        List("SelectionTest", "1", "missing"),
        1,
        List("Expected one test method"),
      ),
    )
  ) {
    test(name) {
      val directory = Files.createTempDirectory("metals-jupiter-selection")
      try {
        val source = directory.resolve("SelectionTest.java")
        Files.write(source, selectionSource.getBytes(StandardCharsets.UTF_8))
        val classpath = dependencies.mkString(File.pathSeparator)
        assertEquals(
          ToolProvider.getSystemJavaCompiler.run(
            null,
            null,
            null,
            "-cp",
            classpath,
            "-d",
            directory.toString,
            source.toString,
          ),
          0,
        )
        val outputFile = directory.resolve("output.txt")
        val command = List(
          Paths.get(System.getProperty("java.home"), "bin", "java").toString,
          "-cp",
          List(
            JUnitPlatformTestMain.classpathEntry().toString,
            directory.toString,
            classpath,
          ).mkString(File.pathSeparator),
          classOf[JUnitPlatformTestMain].getName,
        ) ++ arguments
        val process = new ProcessBuilder(command.asJava)
          .redirectErrorStream(true)
          .redirectOutput(outputFile.toFile)
          .start()
        try {
          assert(process.waitFor(15, TimeUnit.SECONDS), "Runner did not exit")
          val output =
            new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8)
          assertEquals(process.exitValue(), expectedExit, output)
          expectedOutput.foreach(marker =>
            assert(output.contains(marker), output)
          )
          assert(!output.contains("UNRELATED"), output)
        } finally {
          process.destroyForcibly()
          process.waitFor(5, TimeUnit.SECONDS)
        }
      } finally AbsolutePath(directory).deleteRecursively()
    }
  }

  for (fails <- List(false, true)) {
    test(
      s"exit-with-background-thread-${if (fails) "failure" else "success"}"
    ) {
      val directory = Files.createTempDirectory("metals-jupiter-exit")
      try {
        val source = directory.resolve("BackgroundThreadTest.java")
        Files.write(
          source,
          s"""|public class BackgroundThreadTest {
              |  @org.junit.jupiter.api.Test
              |  void backgroundThread() {
              |    Thread worker = new Thread(() -> {
              |      try {
              |        new java.util.concurrent.CountDownLatch(1).await();
              |      } catch (InterruptedException error) {
              |        Thread.currentThread().interrupt();
              |      }
              |    });
              |    worker.setDaemon(false);
              |    worker.start();
              |    System.out.println("BACKGROUND_THREAD_STARTED");
              |    if ($fails) throw new AssertionError("intentional-test-failure");
              |  }
              |}
              |""".stripMargin.getBytes(StandardCharsets.UTF_8),
        )
        val classpath = dependencies.mkString(File.pathSeparator)
        assertEquals(
          ToolProvider.getSystemJavaCompiler.run(
            null,
            null,
            null,
            "-cp",
            classpath,
            "-d",
            directory.toString,
            source.toString,
          ),
          0,
        )
        val outputFile = directory.resolve("output.txt")
        val process = new ProcessBuilder(
          Paths.get(System.getProperty("java.home"), "bin", "java").toString,
          "-cp",
          List(
            JUnitPlatformTestMain.classpathEntry().toString,
            directory.toString,
            classpath,
          ).mkString(File.pathSeparator),
          classOf[JUnitPlatformTestMain].getName,
          "BackgroundThreadTest",
          "1",
          "backgroundThread",
        ).redirectErrorStream(true).redirectOutput(outputFile.toFile).start()
        try {
          val exited = process.waitFor(15, TimeUnit.SECONDS)
          val output =
            new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8)
          assert(output.contains("BACKGROUND_THREAD_STARTED"), output)
          assert(
            exited,
            s"Runner did not exit after the test finished:\n$output",
          )
          assertEquals(process.exitValue(), if (fails) 1 else 0)
          if (fails) assert(output.contains("intentional-test-failure"), output)
        } finally {
          process.destroyForcibly()
          process.waitFor(5, TimeUnit.SECONDS)
        }
      } finally AbsolutePath(directory).deleteRecursively()
    }
  }
}
