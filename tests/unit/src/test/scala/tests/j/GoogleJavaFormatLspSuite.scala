package tests.j

import scala.meta.internal.metals.config.GoogleJavaFormatStyle

class GoogleJavaFormatLspSuite
    extends BaseJavaPCSuite("google-java-format-lsp") {

  testLSP("range-preserves-other-method-and-imports") {
    cleanWorkspace()
    val example = "a/src/main/java/a/Example.java"
    val query =
      """|package a;
         |
         |import java.util.Map;
         |import java.util.ArrayList;
         |
         |public class Example {
         |  void untouched( ){System.out.println("old");}
         |<<  void changed( ){System.out.println("new");}>>
         |}
         |""".stripMargin
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/$example
            |""".stripMargin + query.replace("<<", "").replace(">>", "")
      )
      _ <- server.didOpen(example)
      _ <- server.rangeFormatting(
        example,
        query,
        """|package a;
           |
           |import java.util.Map;
           |import java.util.ArrayList;
           |
           |public class Example {
           |  void untouched( ){System.out.println("old");}
           |
           |  void changed() {
           |    System.out.println("new");
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("whole-document-range-preserves-imports") {
    cleanWorkspace()
    val example = "a/src/main/java/a/Example.java"
    val code =
      """|package a;
         |
         |import java.util.Map;
         |
         |public class Example {}
         |""".stripMargin
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/$example
            |""".stripMargin + code
      )
      _ <- server.didOpen(example)
      _ <- server.rangeFormatting(example, s"<<$code>>", code)
    } yield ()
  }

  testLSP("aosp-range-preserves-other-method-and-imports") {
    cleanWorkspace()
    val example = "a/src/main/java/a/Example.java"
    val query =
      """|package a;
         |
         |import java.util.Map;
         |import java.util.ArrayList;
         |
         |public class Example {
         |  void untouched( ){System.out.println("old");}
         |<<  void changed( ){System.out.println("new");}>>
         |}
         |""".stripMargin
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/$example
            |""".stripMargin + query.replace("<<", "").replace(">>", "")
      )
      _ <- server.didChangeConfiguration(
        userConfig
          .copy(googleJavaFormatStyle = GoogleJavaFormatStyle.Aosp)
          .toString
      )
      _ <- server.didOpen(example)
      _ <- server.rangeFormatting(
        example,
        query,
        """|package a;
           |
           |import java.util.Map;
           |import java.util.ArrayList;
           |
           |public class Example {
           |  void untouched( ){System.out.println("old");}
           |
           |    void changed() {
           |        System.out.println("new");
           |    }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("style-change-without-restart") {
    cleanWorkspace()
    val example = "a/src/main/java/a/Example.java"
    val google =
      """|package a;
         |
         |import java.util.List;
         |import java.util.Map;
         |import javax.swing.JFrame;
         |
         |public class Example {
         |  Map<String, List<String>> values;
         |  JFrame frame;
         |}
         |""".stripMargin
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/$example
            |""".stripMargin + google
      )
      _ <- server.didOpen(example)
      _ <- server.formatting(example)
      _ = assertNoDiff(server.bufferContents(example), google)
      _ <- server.didChangeConfiguration(
        userConfig
          .copy(googleJavaFormatStyle = GoogleJavaFormatStyle.Aosp)
          .toString
      )
      _ <- server.formatting(example)
      _ = assertNoDiff(
        server.bufferContents(example),
        """|package a;
           |
           |import java.util.List;
           |import java.util.Map;
           |
           |import javax.swing.JFrame;
           |
           |public class Example {
           |    Map<String, List<String>> values;
           |    JFrame frame;
           |}
           |""".stripMargin,
      )
      _ <- server.didChangeConfiguration(userConfig.toString)
      _ <- server.formatting(example)
      _ = assertNoDiff(server.bufferContents(example), google)
    } yield ()
  }

  testLSP("basic") {
    cleanWorkspace()
    val example = "a/src/main/java/a/Example.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/$example
            |package a;
            |
            |import java.util.ArrayList;
            |import java.util.Map;
            |import java.util.HashMap;
            |
            |public class Example {
            |
            |
            |    public static Map<String, String> map = new HashMap<>();
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(example)
      _ <- server.formatting(example)
      _ = assertNoDiff(
        server.bufferContents(example),
        // Assert:
        // 1. Formatting was applied (indentation, spacing, etc.)
        // 2. Unused imports were removed
        // 3. Imports were reordered
        """|package a;
           |
           |import java.util.HashMap;
           |import java.util.Map;
           |
           |public class Example {
           |
           |  public static Map<String, String> map = new HashMap<>();
           |}
           |""".stripMargin,
      )
    } yield ()
  }
}
