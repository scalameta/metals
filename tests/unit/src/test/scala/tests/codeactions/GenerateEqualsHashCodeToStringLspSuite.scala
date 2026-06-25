package tests.codeactions

import scala.meta.internal.metals.codeactions.GenerateEqualsHashCodeToString

class GenerateEqualsHashCodeToStringLspSuite
    extends BaseCodeActionLspSuite("generate-equals-hashcode-tostring") {

  override protected def toPath(
      fileName: String,
      isSource: Boolean = true,
  ): String =
    if (isSource) s"a/src/main/java/a/$fileName"
    else s"a/$fileName"

  private def title(className: String): String =
    s"""|${GenerateEqualsHashCodeToString.title(className)}
        |""".stripMargin

  private val onlyGenerate: org.eclipse.lsp4j.CodeAction => Boolean =
    _.getTitle().startsWith("Generate equals()")

  check(
    "basic",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |  private int age;
       |}
       |""".stripMargin,
    title("Example"),
    """|package a;
       |
       |public class Example {
       |  private String name;
       |  private int age;
       |
       |  @Override
       |  public boolean equals(Object obj) {
       |    if (this == obj) {
       |      return true;
       |    }
       |    if (obj == null || getClass() != obj.getClass()) {
       |      return false;
       |    }
       |    Example that = (Example) obj;
       |    return java.util.Objects.equals(this.name, that.name) && java.util.Objects.equals(this.age, that.age);
       |  }
       |
       |  @Override
       |  public int hashCode() {
       |    return java.util.Objects.hash(name, age);
       |  }
       |
       |  @Override
       |  public String toString() {
       |    return "Example{" +
       |      "name=" + name +
       |      ", age=" + age +
       |      "}";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyGenerate,
  )

  check(
    "skips-static",
    """|package a;
       |
       |public class <<Example>> {
       |  private static String prefix;
       |  private String name;
       |}
       |""".stripMargin,
    title("Example"),
    """|package a;
       |
       |public class Example {
       |  private static String prefix;
       |  private String name;
       |
       |  @Override
       |  public boolean equals(Object obj) {
       |    if (this == obj) {
       |      return true;
       |    }
       |    if (obj == null || getClass() != obj.getClass()) {
       |      return false;
       |    }
       |    Example that = (Example) obj;
       |    return java.util.Objects.equals(this.name, that.name);
       |  }
       |
       |  @Override
       |  public int hashCode() {
       |    return java.util.Objects.hash(name);
       |  }
       |
       |  @Override
       |  public String toString() {
       |    return "Example{" +
       |      "name=" + name +
       |      "}";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyGenerate,
  )

  check(
    "generic",
    """|package a;
       |
       |public class <<Example>><T> {
       |  private T value;
       |}
       |""".stripMargin,
    title("Example"),
    """|package a;
       |
       |public class Example<T> {
       |  private T value;
       |
       |  @Override
       |  public boolean equals(Object obj) {
       |    if (this == obj) {
       |      return true;
       |    }
       |    if (obj == null || getClass() != obj.getClass()) {
       |      return false;
       |    }
       |    Example<?> that = (Example<?>) obj;
       |    return java.util.Objects.equals(this.value, that.value);
       |  }
       |
       |  @Override
       |  public int hashCode() {
       |    return java.util.Objects.hash(value);
       |  }
       |
       |  @Override
       |  public String toString() {
       |    return "Example{" +
       |      "value=" + value +
       |      "}";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyGenerate,
  )

  check(
    "arrays",
    """|package a;
       |
       |public class <<Example>> {
       |  private int[] numbers;
       |  private String[] tags;
       |}
       |""".stripMargin,
    title("Example"),
    """|package a;
       |
       |public class Example {
       |  private int[] numbers;
       |  private String[] tags;
       |
       |  @Override
       |  public boolean equals(Object obj) {
       |    if (this == obj) {
       |      return true;
       |    }
       |    if (obj == null || getClass() != obj.getClass()) {
       |      return false;
       |    }
       |    Example that = (Example) obj;
       |    return java.util.Arrays.equals(this.numbers, that.numbers) && java.util.Arrays.equals(this.tags, that.tags);
       |  }
       |
       |  @Override
       |  public int hashCode() {
       |    return java.util.Objects.hash(java.util.Arrays.hashCode(numbers), java.util.Arrays.hashCode(tags));
       |  }
       |
       |  @Override
       |  public String toString() {
       |    return "Example{" +
       |      "numbers=" + java.util.Arrays.toString(numbers) +
       |      ", tags=" + java.util.Arrays.toString(tags) +
       |      "}";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyGenerate,
  )

  check(
    "inner-class",
    """|package a;
       |
       |public class Outer {
       |  public class <<Inner>> {
       |    private String name;
       |  }
       |}
       |""".stripMargin,
    title("Inner"),
    """|package a;
       |
       |public class Outer {
       |  public class Inner {
       |    private String name;
       |
       |    @Override
       |    public boolean equals(Object obj) {
       |      if (this == obj) {
       |        return true;
       |      }
       |      if (obj == null || getClass() != obj.getClass()) {
       |        return false;
       |      }
       |      Inner that = (Inner) obj;
       |      return java.util.Objects.equals(this.name, that.name);
       |    }
       |
       |    @Override
       |    public int hashCode() {
       |      return java.util.Objects.hash(name);
       |    }
       |
       |    @Override
       |    public String toString() {
       |      return "Inner{" +
       |        "name=" + name +
       |        "}";
       |    }
       |  }
       |}
       |""".stripMargin,
    fileName = "Outer.java",
    filterAction = onlyGenerate,
  )

  check(
    "empty",
    """|package a;
       |
       |public class <<Example>> {
       |}
       |""".stripMargin,
    title("Example"),
    """|package a;
       |
       |public class Example {
       |  @Override
       |  public boolean equals(Object obj) {
       |    if (this == obj) {
       |      return true;
       |    }
       |    if (obj == null || getClass() != obj.getClass()) {
       |      return false;
       |    }
       |    return true;
       |  }
       |
       |  @Override
       |  public int hashCode() {
       |    return java.util.Objects.hash();
       |  }
       |
       |  @Override
       |  public String toString() {
       |    return "Example{}";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyGenerate,
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
      _.getTitle() == GenerateEqualsHashCodeToString.title("MyInterface"),
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
    filterAction =
      _.getTitle() == GenerateEqualsHashCodeToString.title("MyEnum"),
  )

  check(
    "existing-equals",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |
       |  @Override
       |  public boolean equals(Object obj) {
       |    return obj instanceof Example;
       |  }
       |}
       |""".stripMargin,
    title("Example"),
    """|package a;
       |
       |public class Example {
       |  private String name;
       |
       |  @Override
       |  public int hashCode() {
       |    return java.util.Objects.hash(name);
       |  }
       |
       |  @Override
       |  public String toString() {
       |    return "Example{" +
       |      "name=" + name +
       |      "}";
       |  }
       |
       |  @Override
       |  public boolean equals(Object obj) {
       |    return obj instanceof Example;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyGenerate,
  )

  checkNoAction(
    "existing-all",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |
       |  @Override
       |  public boolean equals(Object obj) {
       |    return obj instanceof Example;
       |  }
       |
       |  @Override
       |  public int hashCode() {
       |    return 1;
       |  }
       |
       |  @Override
       |  public String toString() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction =
      _.getTitle() == GenerateEqualsHashCodeToString.title("Example"),
  )
}
