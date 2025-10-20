package tests.j

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.TextEdits

class JavaPCCompletionSuite extends BaseJavaPCSuite("java-pc-completion") {

  testLSP("basic") {
    cleanWorkspace()
    val foo = "whatever/FooQux.java"
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
            |public class ExampleGreetingBean {
            |  public static String name = "Alice";
            |  public static void run() {
            |    FooQu
            |  }
            |}
            |/$foo
            |package whatever;
            |
            |public class FooQux {
            |  public void foo() {
            |    System.out.println(ExampleGreetingB);
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(foo)
      _ <- server.didFocus(foo)
      // Assert we can auto-import from a build target with the fallback compiler
      completions <- server.completionList(
        foo,
        "System.out.println(ExampleGreetingB@@",
      )
      _ = assertNoDiff(
        server.formatCompletion(completions, includeDetail = true),
        """|ExampleGreetingBean - a
           |""".stripMargin,
      )
      item = completions.getItems().get(0)
      _ = assertNoDiff(
        TextEdits.applyEdits(
          server.textContents(foo),
          item.getTextEdit().getLeft() ::
            item.getAdditionalTextEdits().asScala.toList,
        ),
        """|package whatever;
           |
           |import a.ExampleGreetingBean;
           |
           |
           |public class FooQux {
           |  public void foo() {
           |    System.out.println(ExampleGreetingBean);
           |  }
           |}
           |""".stripMargin,
      )
      // Assert that we can import symbols from outside a build target
      completionsFromBuildTarget <- server.completion(example, "FooQu@@")
      _ = assertNoDiff(
        completionsFromBuildTarget,
        """|FooQux - whatever
           |""".stripMargin,
      )
    } yield ()
  }

}
