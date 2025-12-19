package tests.j

import java.nio.file.Files

import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.TurbineRecompileDelayConfig

/**
 * Tests that specifically stress the SOURCE_PATH fallback mode in turbine-classpath.
 *
 * These tests use a very long turbine recompile delay (effectively disabled) to ensure
 * that all class resolution happens via SOURCE_PATH. This tests that:
 * 1. New files are correctly resolved via SOURCE_PATH
 * 2. Modified files are correctly resolved via SOURCE_PATH
 * 3. The `hasPendingSource` check in TurbineClasspathFileManager works correctly
 *
 * Unlike JavaPCDiagnosticsTrickySuite, these tests do NOT await turbine recompilation.
 * The turbine delay is set to a very high value to simulate an environment where
 * turbine never recompiles, forcing all resolution to go through SOURCE_PATH.
 */
class JavaPCTurbineSourcepathSuite
    extends BaseJavaPCSuite("java-pc-turbine-sourcepath") {

  // Only test turbine-classpath with SOURCE_PATH fallback
  override def javaSymbolLoaderMode: Option[JavaSymbolLoaderConfig] =
    Some(JavaSymbolLoaderConfig.turbineClasspath)

  // 1 hour delay effectively disables turbine recompilation
  override def turbineRecompileDelayConfig: TurbineRecompileDelayConfig =
    TurbineRecompileDelayConfig.disabled

  // Tests that modifying an existing class (adding a method) is visible via SOURCE_PATH.
  // This is the basic case: an already-indexed file changes, and javac should see
  // the updated source instead of stale turbine-compiled classfiles.
  test("modify-existing-class") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Calculator.java
           |package a;
           |
           |public class Calculator {
           |  public static int add(int x, int y) {
           |    return x + y;
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public static int compute() {
           |    return Calculator.add(1, 2);
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ = assertNoDiagnostics()

      // Add a new multiply method to Calculator - this should be available
      // immediately via SOURCE_PATH without waiting for turbine recompile
      _ <- server.didChange("a/src/main/java/a/Calculator.java")(
        _.replace(
          "public static int add(int x, int y) {",
          """public static int multiply(int x, int y) {
            |    return x * y;
            |  }
            |  public static int add(int x, int y) {""".stripMargin,
        )
      )
      // Note: No await here - we use `_ =` instead of `_ <-`
      _ = server.didSave("a/src/main/java/a/Calculator.java")

      // Use the new multiply method in Main - it should be available via SOURCE_PATH
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace(
          "return Calculator.add(1, 2);",
          "return Calculator.multiply(Calculator.add(1, 2), 3);",
        )
      )
      // Should have no errors because SOURCE_PATH provides the updated Calculator
      _ = assertNoDiagnostics()
    } yield ()
  }

  // Tests that a completely new file (not in the initial index) is visible via SOURCE_PATH.
  // This is different from modifying an existing file because:
  // 1. The file has a new binary name that turbine has never seen
  // 2. The package index must be updated to include the new file
  // 3. TurbineClasspathFileManager.hasPendingSource must find it
  test("add-new-file") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public static String compute(String s) {
           |    return s.toUpperCase();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ = assertNoDiagnostics()

      // Create a new Helper class - this file was never indexed
      helper = workspace.resolve("a/src/main/java/a/Helper.java")
      _ = Files.createDirectories(helper.toNIO.getParent)
      _ = Files.write(
        helper.toNIO,
        """|package a;
           |
           |public class Helper {
           |  public static String greet(String name) {
           |    return "Hello, " + name;
           |  }
           |}
           |""".stripMargin.getBytes,
      )
      _ <- server.didChangeWatchedFiles(
        helper.toURI.toString(),
        org.eclipse.lsp4j.FileChangeType.Created,
      )
      _ <- server.didOpen(helper.toURI.toString)

      // Use Helper in Main - should work via SOURCE_PATH
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace(
          "return s.toUpperCase();",
          "return Helper.greet(s.toUpperCase());",
        )
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

  // Tests that adding a new inner class is visible via SOURCE_PATH.
  // This is different from adding a top-level class because:
  // 1. Inner classes have different binary names (e.g., "a/Outer$Inner" vs "a/Outer")
  // 2. If we tracked deleted/pending binary names incorrectly, inner classes could be missed
  // 3. With SOURCE_PATH, javac parses the whole file so inner classes are found naturally,
  //    but this test ensures we don't accidentally filter them out in hasPendingSource checks
  test("add-inner-class") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Outer.java
           |package a;
           |
           |public class Outer {
           |  public static String getName() {
           |    return "outer";
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public static String compute() {
           |    return Outer.getName();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ = assertNoDiagnostics()

      // Add an inner class to Outer - binary name will be "a/Outer$Inner"
      _ <- server.didChange("a/src/main/java/a/Outer.java")(
        _.replace(
          "public static String getName() {",
          """public static class Inner {
            |    public static String getInnerName() {
            |      return "inner";
            |    }
            |  }
            |  public static String getName() {""".stripMargin,
        )
      )
      _ = server.didSave("a/src/main/java/a/Outer.java")

      // Use the new inner class in Main
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace(
          "return Outer.getName();",
          "return Outer.getName() + \"-\" + Outer.Inner.getInnerName();",
        )
      )
      _ = assertNoDiagnostics()
    } yield ()
  }
}
