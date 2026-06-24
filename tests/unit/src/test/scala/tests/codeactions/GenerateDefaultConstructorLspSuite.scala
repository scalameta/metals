package tests.codeactions

import scala.meta.internal.metals.codeactions.GenerateDefaultConstructor

class GenerateDefaultConstructorLspSuite
    extends BaseCodeActionLspSuite("generate-default-constructor") {

  override protected def toPath(
      fileName: String,
      isSource: Boolean = true,
  ): String =
    if (isSource) s"a/src/main/java/a/$fileName"
    else s"a/$fileName"

  check(
    "basic",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |}
       |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private String name;
       |
       |  public Example() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )

  checkNoAction(
    "existing",
    """|package a;
       |
       |public class <<Example>> {
       |  public Example() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = _.getTitle() == GenerateDefaultConstructor.title("Example"),
  )

  check(
    "with-parameterized-constructor",
    """|package a;
       |
       |public class <<Example>> {
       |  public Example(String name) {
       |  }
       |}
       |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public Example() {
       |  }
       |
       |  public Example(String name) {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )

  checkNoAction(
    "interface",
    """|package a;
       |
       |public interface <<MyInterface>> {
       |}
       |""".stripMargin,
    fileName = "MyInterface.java",
    filterAction =
      _.getTitle() == GenerateDefaultConstructor.title("MyInterface"),
  )

  checkNoAction(
    "enum",
    """|package a;
       |
       |public enum <<MyEnum>> {
       |  A
       |}
       |""".stripMargin,
    fileName = "MyEnum.java",
    filterAction = _.getTitle() == GenerateDefaultConstructor.title("MyEnum"),
  )

  check(
    "abstract-class",
    """|package a;
       |
       |public abstract class <<Base>> {
       |}
       |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Base")}
        |""".stripMargin,
    """|package a;
       |
       |public abstract class Base {
       |  protected Base() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Base.java",
  )

  check(
    "before-methods",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |
       |  public String name() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private String name;
       |
       |  public Example() {
       |  }
       |
       |  public String name() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )

  check(
    "cursor-on-name",
    """|package a;
       |
       |public class Exa<<>>mple {
       |  private String name;
       |}
       |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private String name;
       |
       |  public Example() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )

  check(
    "nested-class",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |
       |  public static class Inner {
       |    int x;
       |  }
       |
       |  public String name() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private String name;
       |
       |  public Example() {
       |  }
       |
       |  public static class Inner {
       |    int x;
       |  }
       |
       |  public String name() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )

  check(
    "generic",
    """|package a;
       |
       |public class <<Example>><T> {
       |  private T value;
       |}
       |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example<T> {
       |  private T value;
       |
       |  public Example() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )

  check(
    "empty-body",
    """|package a;
       |
       |public class <<Example>> {}
       |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public Example() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )

  check(
    "empty-body-spaces",
    """|package a;
       |
       |class Helper {
       |  private int x;
       |}
       |
       |public class <<Example>> {}
       |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Example")}
        |""".stripMargin,
    """|package a;
       |
       |class Helper {
       |  private int x;
       |}
       |
       |public class Example {
       |  public Example() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )

  check(
    "empty-body-tabs",
    s"""|package a;
        |
        |class Helper {
        |\tprivate int x;
        |}
        |
        |public class <<Example>> {}
        |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Example")}
        |""".stripMargin,
    s"""|package a;
        |
        |class Helper {
        |\tprivate int x;
        |}
        |
        |public class Example {
        |\tpublic Example() {
        |\t}
        |}
        |""".stripMargin,
    fileName = "Example.java",
  )

  check(
    "inner-class-cursor",
    """|package a;
       |
       |public class Outer {
       |  public class <<Example>> {
       |    private String name;
       |  }
       |}
       |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Outer {
       |  public class Example {
       |    private String name;
       |
       |    public Example() {
       |    }
       |  }
       |}
       |""".stripMargin,
    fileName = "Outer.java",
  )

  check(
    "with-annotation",
    """|package a;
       |
       |@SuppressWarnings({"unused"})
       |public class <<Example>> {
       |  private String name;
       |}
       |""".stripMargin,
    s"""|${GenerateDefaultConstructor.title("Example")}
        |""".stripMargin,
    """|package a;
       |
       |@SuppressWarnings({"unused"})
       |public class Example {
       |  private String name;
       |
       |  public Example() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
  )

}
