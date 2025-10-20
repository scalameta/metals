package tests.j

class JavaPCSemanticdbSuite extends BaseJavaPCSuite("java-pc-semanticdb") {

  testLSP("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/com/app/Main.java
           |package com.app;
           |
           |import java.io.IOException;
           |import java.io.UncheckedIOException;
           |import java.nio.file.FileVisitResult;
           |import java.nio.file.Files;
           |import java.nio.file.SimpleFileVisitor;
           |import java.nio.file.attribute.BasicFileAttributes;
           |import java.nio.file.FileVisitResult;
           |import java.nio.file.Path;
           |import java.nio.file.Paths;
           |import java.nio.file.Paths;
           |
           |public class Main {
           |
           |
           |	public static void blah(String[] args) throws IOException {
           |		Files.walkFileTree(java.nio.file.Paths.get(""), new SimpleFileVisitor<Path>() {
           |			@Override
           |			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
           |				System.out.println(file);
           |				return FileVisitResult.CONTINUE;
           |			}
           |		});
           |	}
           |}
           |
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/com/app/Main.java")
      decodeURI =
        s"metalsDecode:${server.toPath("a/src/main/java/com/app/Main.java").toURI}.semanticdb-detailed"
      result <- server.executeDecodeFileCommand(decodeURI)
      shortResult = result.value.linesIterator
        .dropWhile(line => !line.contains("visitFile"))
        .take(8)
        .mkString("\n")
      _ = assertNoDiff(
        shortResult,
        """|public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
           |//           ^^^^^^^^^^^^^^^ reference java/nio/file/FileVisitResult#
           |//                           ^^^^^^^^^ definition local2
           |//                                     ^^^^ reference java/nio/file/Path#
           |//                                          ^^^^ definition local3
           |//                                                ^^^^^^^^^^^^^^^^^^^ reference java/nio/file/attribute/BasicFileAttributes#
           |//                                                                    ^^^^^ definition local4
           |//                                                                                  ^^^^^^^^^^^ reference java/io/IOException#
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("errors") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/java/com/app/Main.java
           |
           |package com.app;
           |
           |public class Main {
           |	public int test(Foobar foobar) { // Foobar is not defined
           |	  String message = foobar.greeting();
           |		return message.length();
           |	}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/com/app/Main.java")
      decodeURI =
        s"metalsDecode:${server.toPath("a/src/main/java/com/app/Main.java").toURI}.semanticdb-detailed"
      result <- server.executeDecodeFileCommand(decodeURI)
      _ = assertNoDiff(
        result.value,
        """|   package com.app;
           |//         ^^^ reference com/
           |//             ^^^ reference com/app/
           |
           |   public class Main {
           |//              ^^^^ definition com/app/Main#
           |//              ^^^^ definition com/app/Main#`<init>`().
           |    public int test(Foobar foobar) { // Foobar is not defined
           |//             ^^^^ definition com/app/Main#test().
           |//                  ^^^^^^ reference Foobar#
           |//                         ^^^^^^ definition local0
           |//                         ^ diagnostic - error cannot find symbol
           |//                             symbol:   class Foobar
           |//                             location: class com.app.Main
           |      String message = foobar.greeting();
           |//    ^^^^^^ reference java/lang/String#
           |//           ^^^^^^^ definition local1
           |//                     ^^^^^^ reference local0
           |//                            ^^^^^^^^ reference Foobar#greeting#
           |     return message.length();
           |//          ^^^^^^^ reference local1
           |//                  ^^^^^^ reference java/lang/String#length().
           |    }
           |   }
           |""".stripMargin,
      )
    } yield ()
  }

}
