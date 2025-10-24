package signatureHelp

import tests.pc.BaseJavaSignatureHelpSuite

class SignatureHelpSuite extends BaseJavaSignatureHelpSuite {

  check(
    "ambiguous-method",
    """
      |class A {
      |    public static void log(int i, String message) {}
      |    public static void log(String message) {}
      |    public void run() {
      |        A.log(1@@);
      |    }
      |}
      |""".stripMargin,
    """|=> log(int i, java.lang.String message)
       |       ^^^^^
       |   log(java.lang.String message)
       |""".stripMargin,
  )

  check(
    "constructor",
    """
      |class A {
      |    public A(String message) {}
      |    public void run() {
      |        new A(@@);
      |    }
      |}
      |""".stripMargin,
    """|=> A(java.lang.String message)
       |     ^^^^^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin,
  )

  check(
    "valid-args",
    """package com.app;
      |
      |import java.io.IOException;
      |import java.nio.file.FileVisitResult;
      |import java.nio.file.SimpleFileVisitor;
      |import java.nio.file.attribute.BasicFileAttributes;
      |import java.nio.file.FileVisitResult;
      |import java.nio.file.Path;
      |import java.nio.file.Paths;
      |
      |public class Main {
      |	public static void blah() throws IOException {
      |		java.nio.file.Files.walkFileTree(java.nio@@.file.Paths.get(""), new SimpleFileVisitor<Path>() {
      |
      |			@Override
      |			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      |
      |				System.out.println(file);
      |				return FileVisitResult.CONTINUE;
      |			}
      |		});
      |	}
      |}
      |""".stripMargin,
    """|walkFileTree(java.nio.file.Path start, java.util.Set<java.nio.file.FileVisitOption> options, int maxDepth, java.nio.file.FileVisitor<? super java.nio.file.Path> visitor)
       |=> walkFileTree(java.nio.file.Path start, java.nio.file.FileVisitor<? super java.nio.file.Path> visitor)
       |                ^^^^^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin,
  )

  check(
    "annotation",
    """
      |@Deprecated(since @@= , forRemoval = true)
      |public class A {
      |}
      |""".stripMargin,
    """|=> @Deprecated(java.lang.String since() default "", boolean forRemoval() default false)
       |               ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin,
  )

  check(
    "chain",
    """
      |public class A {
      |    public A self(String message) { return this; }
      |    public void foo(String message) {
      |        new A().self("bla@@h").foo(String.format("blah %s", "foo"));
      |    }
      |}
      |""".stripMargin,
    """|=> self(java.lang.String message)
       |        ^^^^^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin,
  )
}
