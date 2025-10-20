package tests.j

import scala.meta.internal.jpc.JavacDiagnostic

class JavacDiagnosticSuite extends BaseJavaPruneCompilerSuite {
  def checkSymbolsNotFound(
      name: munit.TestOptions,
      layout: String,
      mainPath: String,
      expectedMissingSymbols: String,
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      val diagnostics = compileFiles(layout, mainPath).diagnostics
      val obtained = diagnostics.collect {
        case JavacDiagnostic.CannotFindSymbol(err) =>
          val location =
            if (err.location.isEmpty) "NO LOCATION" else err.location
          s"${err.kind} - ${err.symbol} - ${location}"
      }

      assertNoDiff(obtained.sorted.mkString("\n"), expectedMissingSymbols)
    }
  }

  checkSymbolsNotFound(
    "class",
    """
      |/a/A.java
      |package a;
      |public class A {
      |  public void foo() {
      |    var foo = new Foo();
      |  }
      |}
      |""".stripMargin,
    "a/A.java",
    "class - Foo - class a.A",
  )

  checkSymbolsNotFound(
    "method",
    """
      |/a/A.java
      |package a;
      |public static class A {
      |  public static class B {}
      |  public void foo() {
      |    B.qux(1, "foo");
      |  }
      |}
      |""".stripMargin,
    "a/A.java",
    "method - qux(int,java.lang.String) - class a.A.B",
  )

  checkSymbolsNotFound(
    "class-no-location",
    """
      |/a/A.java
      |package a;
      |public static class A {
      |  public void foo() {
      |    java.nio.file.Files.walkFileTree(null, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
      |      @Override
      |      public FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
      |        return java.nio.file.FileVisitResult.CONTINUE;
      |      }
      |    });
      |  }
      |}
      |""".stripMargin,
    "a/A.java",
    "class - FileVisitResult - NO LOCATION",
  )
}
