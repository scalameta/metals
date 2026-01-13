package tests

class JavaDiagnosticsLspSuite extends BaseLspSuite("java-diagnostics") {
  test("java-diagnostics-cleanup".ignore) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {
           |     "javacOptions": [
           |       "-Xlint:all"
           |     ]
           |   }
           |}
           |/a/src/main/java/a/Example.java
           |package a;
           |import java.util.List;
           |public class Example {
           |  public void foo(List l) { // raw type warning
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Example.java")
      _ <- server.didSave("a/src/main/java/a/Example.java")
      // Assert diagnostics present
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/java/a/Example.java:4:19: warning:  [rawtypes] found raw type: List
           |  public void foo(List l) { // raw type warning
           |                  ^
           |""".stripMargin,
      )

      // Fix the warning
      _ <- server.didChange("a/src/main/java/a/Example.java")(
        _.replace("List l", "List<String> l")
      )
      _ <- server.didSave("a/src/main/java/a/Example.java")

      // Assert diagnostics cleared
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  test("java-diagnostics-persistence".ignore) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {
           |     "javacOptions": [ "-Xlint:all" ]
           |   }
           |}
           |/a/src/main/java/a/A.java
           |package a;
           |import java.util.List;
           |public class A {
           |  public void foo(List l) {}
           |}
           |/a/src/main/java/a/B.java
           |package a;
           |import java.util.List;
           |public class B {
           |  public void bar(List l) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/A.java")
      _ <- server.didOpen("a/src/main/java/a/B.java")
      _ <- server.didSave(
        "a/src/main/java/a/A.java"
      ) // Compile A and B (same target)

      // Both should have errors
      _ = assert(
        client.pathDiagnostics("a/src/main/java/a/A.java").nonEmpty,
        "A should have diagnostics",
      )
      _ = assert(
        client.pathDiagnostics("a/src/main/java/a/B.java").nonEmpty,
        "B should have diagnostics",
      )

      // Fix A
      _ <- server.didChange("a/src/main/java/a/A.java")(
        _.replace("List l", "List<String> l")
      )
      _ <- server.didSave("a/src/main/java/a/A.java")

      // A should be clean
      _ = assertNoDiff(client.pathDiagnostics("a/src/main/java/a/A.java"), "")

      // B should STILL have error
      _ = assert(
        client.pathDiagnostics("a/src/main/java/a/B.java").nonEmpty,
        "B should still have diagnostics",
      )
    } yield ()
  }
}
