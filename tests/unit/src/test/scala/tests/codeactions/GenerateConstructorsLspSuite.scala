package tests.codeactions

import scala.meta.internal.metals.codeactions.GenerateConstructors

class GenerateConstructorsLspSuite
    extends BaseCodeActionLspSuite("generate-constructors") {

  override protected def toPath(
      fileName: String,
      isSource: Boolean = true,
  ): String =
    if (isSource) s"a/src/main/java/a/$fileName"
    else s"a/$fileName"

  private val onlyConstructor: org.eclipse.lsp4j.CodeAction => Boolean =
    _.getTitle().startsWith("Generate default constructor")

  check(
    "basic",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleDefault("Example")}
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
    filterAction = onlyConstructor,
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
    filterAction = _.getTitle() == GenerateConstructors.titleDefault("Example"),
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
    s"""|${GenerateConstructors.titleDefault("Example")}
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
    filterAction = onlyConstructor,
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
      _.getTitle() == GenerateConstructors.titleDefault("MyInterface"),
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
    filterAction = _.getTitle() == GenerateConstructors.titleDefault("MyEnum"),
  )

  check(
    "abstract-class",
    """|package a;
       |
       |public abstract class <<Base>> {
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleDefault("Base")}
        |""".stripMargin,
    """|package a;
       |
       |public abstract class Base {
       |  protected Base() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Base.java",
    filterAction = onlyConstructor,
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
    s"""|${GenerateConstructors.titleDefault("Example")}
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
    filterAction = onlyConstructor,
  )

  check(
    "cursor-on-name",
    """|package a;
       |
       |public class Exa<<>>mple {
       |  private String name;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleDefault("Example")}
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
    filterAction = onlyConstructor,
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
    s"""|${GenerateConstructors.titleDefault("Example")}
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
    filterAction = onlyConstructor,
  )

  check(
    "generic",
    """|package a;
       |
       |public class <<Example>><T> {
       |  private T value;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleDefault("Example")}
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
    filterAction = onlyConstructor,
  )

  check(
    "empty-body",
    """|package a;
       |
       |public class <<Example>> {}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleDefault("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public Example() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyConstructor,
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
    s"""|${GenerateConstructors.titleDefault("Example")}
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
    filterAction = onlyConstructor,
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
    s"""|${GenerateConstructors.titleDefault("Example")}
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
    filterAction = onlyConstructor,
  )

  check(
    "tab-indented-inner-class",
    s"""|package a;
        |
        |public class Outer {
        |\tpublic class <<Example>> {
        |\t\tprivate String name;
        |\t}
        |}
        |""".stripMargin,
    s"""|${GenerateConstructors.titleDefault("Example")}
        |""".stripMargin,
    s"""|package a;
        |
        |public class Outer {
        |\tpublic class Example {
        |\t\tprivate String name;
        |
        |\t\tpublic Example() {
        |\t\t}
        |\t}
        |}
        |""".stripMargin,
    fileName = "Outer.java",
    filterAction = onlyConstructor,
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
    s"""|${GenerateConstructors.titleDefault("Example")}
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
    filterAction = onlyConstructor,
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
    s"""|${GenerateConstructors.titleDefault("Example")}
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
    filterAction = onlyConstructor,
  )

  check(
    "from-fields",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |  private int age;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleFromFields("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private String name;
       |  private int age;
       |
       |  public Example(String name, int age) {
       |    this.name = name;
       |    this.age = age;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction =
      _.getTitle() == GenerateConstructors.titleFromFields("Example"),
  )

  check(
    "copy",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |  private int age;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleCopy("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private String name;
       |  private int age;
       |
       |  public Example(Example other) {
       |    this.name = other.name;
       |    this.age = other.age;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = _.getTitle() == GenerateConstructors.titleCopy("Example"),
  )

  check(
    "copy-generic",
    """|package a;
       |
       |public class <<Example>><T> {
       |  private T value;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleCopy("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example<T> {
       |  private T value;
       |
       |  public Example(Example<T> other) {
       |    this.value = other.value;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = _.getTitle() == GenerateConstructors.titleCopy("Example"),
  )

  check(
    "abstract-from-fields",
    """|package a;
       |
       |public abstract class <<Base>> {
       |  private String name;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleFromFields("Base")}
        |""".stripMargin,
    """|package a;
       |
       |public abstract class Base {
       |  private String name;
       |
       |  protected Base(String name) {
       |    this.name = name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Base.java",
    filterAction = _.getTitle() == GenerateConstructors.titleFromFields("Base"),
  )

  check(
    "abstract-copy",
    """|package a;
       |
       |public abstract class <<Base>> {
       |  private String name;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleCopy("Base")}
        |""".stripMargin,
    """|package a;
       |
       |public abstract class Base {
       |  private String name;
       |
       |  protected Base(Base other) {
       |    this.name = other.name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Base.java",
    filterAction = _.getTitle() == GenerateConstructors.titleCopy("Base"),
  )

  check(
    "from-fields-skips-static",
    """|package a;
       |
       |public class <<Example>> {
       |  private static String prefix;
       |  private String name;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleFromFields("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private static String prefix;
       |  private String name;
       |
       |  public Example(String name) {
       |    this.name = name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction =
      _.getTitle() == GenerateConstructors.titleFromFields("Example"),
  )

  check(
    "from-fields-skips-final-with-initializer",
    """|package a;
       |
       |public class <<Example>> {
       |  private final int age = 10;
       |  private String name;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleFromFields("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private final int age = 10;
       |  private String name;
       |
       |  public Example(String name) {
       |    this.name = name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction =
      _.getTitle() == GenerateConstructors.titleFromFields("Example"),
  )

  check(
    "copy-skips-final-with-initializer",
    """|package a;
       |
       |public class <<Example>> {
       |  private final int age = 10;
       |  private String name;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleCopy("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private final int age = 10;
       |  private String name;
       |
       |  public Example(Example other) {
       |    this.name = other.name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = _.getTitle() == GenerateConstructors.titleCopy("Example"),
  )

  check(
    "from-fields-includes-final-without-initializer",
    """|package a;
       |
       |public class <<Example>> {
       |  private final String name;
       |}
       |""".stripMargin,
    s"""|${GenerateConstructors.titleFromFields("Example")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private final String name;
       |
       |  public Example(String name) {
       |    this.name = name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction =
      _.getTitle() == GenerateConstructors.titleFromFields("Example"),
  )

  checkNoAction(
    "from-fields-empty-class",
    """|package a;
       |
       |public class <<Example>> {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction =
      _.getTitle() == GenerateConstructors.titleFromFields("Example"),
  )

  checkNoAction(
    "from-fields-only-static",
    """|package a;
       |
       |public class <<Example>> {
       |  private static String name;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction =
      _.getTitle() == GenerateConstructors.titleFromFields("Example"),
  )

  checkNoAction(
    "existing-from-fields",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |
       |  public Example(String name) {
       |    this.name = name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction =
      _.getTitle() == GenerateConstructors.titleFromFields("Example"),
  )

  checkNoAction(
    "existing-copy",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |
       |  public Example(Example other) {
       |    this.name = other.name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = _.getTitle() == GenerateConstructors.titleCopy("Example"),
  )

}
