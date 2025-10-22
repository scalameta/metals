package tests.j

import tests.TestingServer

class JavaPCDefinitionSuite extends BaseJavaPCSuite("java-pc-definition") {

  testLSP("jdk-virtualdoc".tag(TestingServer.virtualDocTag)) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/a/Example.java
           |package a;
           |
           |public class Example {
           |  public static String name = "Alice";
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Example.java")
      locs <- server.assertDefinition(
        "a/src/main/java/a/Example.java",
        "Str@@ing name",
        """|src.zip!/java.base/java/lang/String.java:LINE:COLUMN: definition
           |public final class String
           |                   ^^^^^^
           |""".stripMargin,
        redactLineNumbers = true,
      )
      loc = locs.head
      _ <- server.didOpen(loc.getUri())
      _ <- server.assertDefinition(
        loc.getUri(),
        "return new String(da@@ta);",
        """|src.zip!/java.base/java/lang/String.java:LINE:COLUMN: definition
           |    public static String valueOf(char data[]) {
           |                                      ^^^^
           |""".stripMargin,
        redactLineNumbers = true,
      )
      _ <- server.assertDefinition(
        loc.getUri(),
        "int l@@ength = asb.length();",
        """|src.zip!/java.base/java/lang/String.java:LINE:COLUMN: definition
           |        int length = asb.length();
           |            ^^^^^^
           |""".stripMargin,
        redactLineNumbers = true,
      )
      _ <- server.assertDefinition(
        loc.getUri(),
        "(AbstractS@@tringBuilder sb)",
        """|src.zip!/java.base/java/lang/AbstractStringBuilder.java:LINE:COLUMN: definition
           |abstract class AbstractStringBuilder implements Appendable, CharSequence {
           |               ^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin,
        redactLineNumbers = true,
      )
    } yield ()
  }

  testLSP("cross-file-sourcepath") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/whatever/Foo.java
           |package whatever;
           |public class Foo {
           |  public static String alice = "Alice";
           |}
           |/a/src/main/java/a/Example.java
           |package a;
           |
           |public class Example {
           |  public static String name = whatever.Foo.alice;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Example.java")
      _ <- server.assertDefinition(
        "a/src/main/java/a/Example.java",
        "whatever.Foo.ali@@ce",
        """|whatever/Foo.java:3:24: definition
           |  public static String alice = "Alice";
           |                       ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

}
