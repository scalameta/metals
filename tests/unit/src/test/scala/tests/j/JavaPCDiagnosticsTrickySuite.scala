package tests.j

import java.nio.file.Files

/**
 * Tests for tricky Java PC workflows involving adding/modifying/deleting files.
 *
 * These tests exercise the TurbineClasspathFileManager which has two sources
 * for class definitions:
 * 1. CLASS_PATH - compiled classfiles from the turbine compiler
 * 2. SOURCE_PATH - source files that are pending compilation
 *
 * In these tests, the turbine recompile delay is set to 100ms (testing default)
 * and we always await (`<-`) on file changes. This means turbine recompilation
 * typically completes before the PC uses the class, so these tests primarily
 * exercise the CLASS_PATH mode after recompilation.
 *
 * For tests that specifically exercise the SOURCE_PATH fallback (before turbine
 * recompiles), see JavaPCTurbineSourcepathSuite which disables turbine recompilation.
 */
class JavaPCDiagnosticsTrickySuite
    extends BaseJavaPCSuite("java-pc-diagnostics-tricky") {

  // Test adding a new public symbol and using it downstream
  test("add-new-symbol-and-use") {
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

      // Add a new method to Calculator
      _ <- server.didChange("a/src/main/java/a/Calculator.java")(
        _.replace(
          "return x + y;",
          """return x + y;
            |  }
            |  public static int multiply(int x, int y) {
            |    return x * y;""".stripMargin,
        )
      )
      _ <- server.didSave("a/src/main/java/a/Calculator.java")

      // Use the new method in Main - the PC should find it via sourcepath
      // while turbine recompile is pending, or via classpath after it completes
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace(
          "Calculator.add(1, 2)",
          "Calculator.multiply(3, 4)",
        )
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

  // Test adding a new file and using it
  test("add-new-file-and-use") {
    cleanWorkspace()
    val helper = "a/src/main/java/a/Helper.java"
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
           |  public static String compute() {
           |    return "Hello";
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ = assertNoDiagnostics()

      // Create a new file Helper.java
      helperPath = workspace.resolve(helper)
      _ = Files.createDirectories(helperPath.toNIO.getParent())
      _ = Files.write(
        helperPath.toNIO,
        """|package a;
           |
           |public class Helper {
           |  public static String greet(String name) {
           |    return "Hello, " + name + "!";
           |  }
           |}
           |""".stripMargin.getBytes(),
      )
      _ <- server.didChangeWatchedFiles(
        helperPath.toURI.toString(),
        org.eclipse.lsp4j.FileChangeType.Created,
      )
      _ <- server.didOpen(helper)

      // Now use Helper in Main
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace(
          "return \"Hello\";",
          "return Helper.greet(\"World\");",
        )
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

  // Test adding a new class in a new package and using it
  test("add-new-package-and-use") {
    cleanWorkspace()
    val util = "a/src/main/java/a/util/StringUtils.java"
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
           |  public static String format(String s) {
           |    return s.toUpperCase();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ = assertNoDiagnostics()

      // Create a new file in a new package
      utilPath = workspace.resolve(util)
      _ = Files.createDirectories(utilPath.toNIO.getParent())
      _ = Files.write(
        utilPath.toNIO,
        """|package a.util;
           |
           |public class StringUtils {
           |  public static String capitalize(String s) {
           |    if (s == null || s.isEmpty()) return s;
           |    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
           |  }
           |}
           |""".stripMargin.getBytes(),
      )
      _ <- server.didChangeWatchedFiles(
        utilPath.toURI.toString(),
        org.eclipse.lsp4j.FileChangeType.Created,
      )
      _ <- server.didOpen(util)

      // Now use StringUtils in Main
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace(
          "return s.toUpperCase();",
          "return a.util.StringUtils.capitalize(s);",
        )
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

  // Test deleting a file that is used
  test("delete-file-diagnostics") {
    cleanWorkspace()
    val helper = "a/src/main/java/a/Helper.java"
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Helper.java
           |package a;
           |
           |public class Helper {
           |  public static String greet() {
           |    return "Hello";
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public static String compute() {
           |    return Helper.greet();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ <- server.didOpen(helper)
      _ = assertNoDiagnostics()

      // Delete Helper.java
      helperPath = workspace.resolve(helper)
      _ = Files.delete(helperPath.toNIO)
      _ <- server.didChangeWatchedFiles(
        helperPath.toURI.toString(),
        org.eclipse.lsp4j.FileChangeType.Deleted,
      )

      // Main should now have errors since Helper is gone
      _ <- server.didFocus("a/src/main/java/a/Main.java")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/a/Main.java:5:12: error: cannot find symbol
           |  symbol:   variable Helper
           |  location: class a.Main
           |    return Helper.greet();
           |           ^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // Test renaming a class (delete old, create new) within the same package.
  // This tests that the old binary name (a/OldName) is properly invalidated
  // and the new binary name (a/NewName) is available.
  test("rename-class") {
    cleanWorkspace()
    val oldName = "a/src/main/java/a/OldName.java"
    val newName = "a/src/main/java/a/NewName.java"
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/OldName.java
           |package a;
           |
           |public class OldName {
           |  public static String value() {
           |    return "old";
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public static String compute() {
           |    return OldName.value();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ <- server.didOpen(oldName)
      _ = assertNoDiagnostics()

      // Rename OldName to NewName (simulated as delete + create)
      oldPath = workspace.resolve(oldName)
      newPath = workspace.resolve(newName)
      _ = Files.move(oldPath.toNIO, newPath.toNIO)
      _ = Files.write(
        newPath.toNIO,
        """|package a;
           |
           |public class NewName {
           |  public static String value() {
           |    return "new";
           |  }
           |}
           |""".stripMargin.getBytes(),
      )
      _ <- server.didChangeWatchedFiles(
        oldPath.toURI.toString(),
        org.eclipse.lsp4j.FileChangeType.Deleted,
      )
      _ <- server.didChangeWatchedFiles(
        newPath.toURI.toString(),
        org.eclipse.lsp4j.FileChangeType.Created,
      )
      _ <- server.didOpen(newName)

      // Update Main to use NewName
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace("OldName.value()", "NewName.value()")
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

  // Test moving a class to a different package.
  // This tests that:
  // 1. The old package index entry (a/) is cleaned up
  // 2. The new package index entry (a/util/) is added
  // 3. The old binary name (a/Helper) is invalidated
  // 4. The new binary name (a/util/Helper) is available
  test("move-to-new-package") {
    cleanWorkspace()
    val oldPath = "a/src/main/java/a/Helper.java"
    val newPath = "a/src/main/java/a/util/Helper.java"
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Helper.java
           |package a;
           |
           |public class Helper {
           |  public static String greet() {
           |    return "Hello";
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public static String compute() {
           |    return Helper.greet();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ <- server.didOpen(oldPath)
      _ = assertNoDiagnostics()

      // Move Helper from package a to package a.util
      oldFile = workspace.resolve(oldPath)
      newFile = workspace.resolve(newPath)
      _ = Files.createDirectories(newFile.toNIO.getParent())
      _ = Files.move(oldFile.toNIO, newFile.toNIO)
      // Update package declaration in the moved file
      _ = Files.write(
        newFile.toNIO,
        """|package a.util;
           |
           |public class Helper {
           |  public static String greet() {
           |    return "Hello from util";
           |  }
           |}
           |""".stripMargin.getBytes(),
      )
      _ <- server.didChangeWatchedFiles(
        oldFile.toURI.toString(),
        org.eclipse.lsp4j.FileChangeType.Deleted,
      )
      _ <- server.didChangeWatchedFiles(
        newFile.toURI.toString(),
        org.eclipse.lsp4j.FileChangeType.Created,
      )
      _ <- server.didOpen(newPath)

      // Update Main to use the new package
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace("Helper.greet()", "a.util.Helper.greet()")
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

  // Test modifying a method signature and checking downstream errors
  // TODO: This test requires the Java PC to pick up changes from one file
  // when compiling another file. The PC caches compiled classes so this
  // doesn't work reliably.
  test("change-method-signature".ignore) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Api.java
           |package a;
           |
           |public class Api {
           |  public static String format(String s) {
           |    return s.toUpperCase();
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public static String compute() {
           |    return Api.format("hello");
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ <- server.didOpen("a/src/main/java/a/Api.java")
      _ = assertNoDiagnostics()

      // Change Api.format to require two arguments
      _ <- server.didChange("a/src/main/java/a/Api.java")(
        _.replace(
          "public static String format(String s)",
          "public static String format(String s, String prefix)",
        ).replace(
          "return s.toUpperCase();",
          "return prefix + s.toUpperCase();",
        )
      )
      _ <- server.didSave("a/src/main/java/a/Api.java")

      // Main should now have errors
      _ <- server.didFocus("a/src/main/java/a/Main.java")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/a/Main.java:5:15: error: method format in class a.Api cannot be applied to given types;
           |  required: java.lang.String,java.lang.String
           |  found:    java.lang.String
           |  reason: actual and formal argument lists differ in length
           |    return Api.format("hello");
           |              ^^^^^^^
           |""".stripMargin,
      )

      // Fix the call site
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace(
          """Api.format("hello")""",
          """Api.format("hello", "PREFIX: ")""",
        )
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

  // Test adding a new public field to a class
  test("add-new-field-and-use") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Config.java
           |package a;
           |
           |public class Config {
           |  public static String NAME = "MyApp";
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public static String getName() {
           |    return Config.NAME;
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ = assertNoDiagnostics()

      // Add a new field to Config
      _ <- server.didChange("a/src/main/java/a/Config.java")(
        _.replace(
          "public static String NAME = \"MyApp\";",
          """public static String NAME = "MyApp";
            |  public static String VERSION = "1.0.0";""".stripMargin,
        )
      )
      _ <- server.didSave("a/src/main/java/a/Config.java")

      // Use the new field in Main
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace(
          "return Config.NAME;",
          "return Config.NAME + \" v\" + Config.VERSION;",
        )
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

  // Test multiple file changes in sequence
  // TODO: This test requires the Java PC to pick up transitive changes
  // (changing Base.java should cause errors in Middle.java). This doesn't
  // work because the PC caches compiled classes and doesn't recompile
  // dependencies when a source file changes.
  test("cascade-changes".ignore) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Base.java
           |package a;
           |
           |public class Base {
           |  public static int value() {
           |    return 1;
           |  }
           |}
           |/a/src/main/java/a/Middle.java
           |package a;
           |
           |public class Middle {
           |  public static int doubled() {
           |    return Base.value() * 2;
           |  }
           |}
           |/a/src/main/java/a/Top.java
           |package a;
           |
           |public class Top {
           |  public static int quadrupled() {
           |    return Middle.doubled() * 2;
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Top.java")
      _ = assertNoDiagnostics()

      // Change Base to return String instead of int
      _ <- server.didChange("a/src/main/java/a/Base.java")(
        _.replace("public static int value()", "public static String value()")
          .replace("return 1;", "return \"1\";")
      )
      _ <- server.didSave("a/src/main/java/a/Base.java")

      // Middle should now have errors
      _ <- server.didFocus("a/src/main/java/a/Middle.java")
      _ = assert(
        client.workspaceDiagnostics.contains("error"),
        s"Expected errors but got: ${client.workspaceDiagnostics}",
      )

      // Fix Middle
      _ <- server.didChange("a/src/main/java/a/Middle.java")(
        _.replace(
          "public static int doubled()",
          "public static String doubled()",
        )
          .replace(
            "return Base.value() * 2;",
            "return Base.value() + Base.value();",
          )
      )
      _ <- server.didSave("a/src/main/java/a/Middle.java")

      // Top should now have errors
      _ <- server.didFocus("a/src/main/java/a/Top.java")
      _ = assert(
        client.workspaceDiagnostics.contains("error"),
        s"Expected errors but got: ${client.workspaceDiagnostics}",
      )

      // Fix Top
      _ <- server.didChange("a/src/main/java/a/Top.java")(
        _.replace(
          "public static int quadrupled()",
          "public static String quadrupled()",
        ).replace(
          "return Middle.doubled() * 2;",
          "return Middle.doubled() + Middle.doubled();",
        )
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

  // Test that diagnostics work after adding and then modifying a new file
  test("add-file-then-modify") {
    cleanWorkspace()
    val helper = "a/src/main/java/a/Helper.java"
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
           |  public static String compute() {
           |    return "Hello";
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ = assertNoDiagnostics()

      // Create a new file with an error
      helperPath = workspace.resolve(helper)
      _ = Files.createDirectories(helperPath.toNIO.getParent())
      _ = Files.write(
        helperPath.toNIO,
        """|package a;
           |
           |public class Helper {
           |  public static int getValue() {
           |    return "not an int";
           |  }
           |}
           |""".stripMargin.getBytes(),
      )
      _ <- server.didChangeWatchedFiles(
        helperPath.toURI.toString(),
        org.eclipse.lsp4j.FileChangeType.Created,
      )
      _ <- server.didOpen(helper)
      _ <- server.didFocus(helper)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/a/Helper.java:5:12: error: incompatible types: java.lang.String cannot be converted to int
           |    return "not an int";
           |           ^^^^^^^^^^^^
           |""".stripMargin,
      )

      // Fix the error
      _ <- server.didChange(helper)(
        _.replace("return \"not an int\";", "return 42;")
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

  // Test adding inner class and using it
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
           |  public static String getValue() {
           |    return "outer";
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |public class Main {
           |  public static String compute() {
           |    return Outer.getValue();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Main.java")
      _ = assertNoDiagnostics()

      // Add an inner class to Outer
      _ <- server.didChange("a/src/main/java/a/Outer.java")(
        _.replace(
          "return \"outer\";",
          """return "outer";
            |  }
            |
            |  public static class Inner {
            |    public static String getInnerValue() {
            |      return "inner";
            |    }""".stripMargin,
        )
      )
      _ <- server.didSave("a/src/main/java/a/Outer.java")

      // Use the inner class in Main
      _ <- server.didChange("a/src/main/java/a/Main.java")(
        _.replace(
          "return Outer.getValue();",
          "return Outer.Inner.getInnerValue();",
        )
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

}
