package tests.codeactions

import scala.meta.internal.metals.codeactions.GenerateGettersSetters

class GenerateGettersSettersLspSuite
    extends BaseCodeActionLspSuite("generate-getters-setters") {

  override protected def toPath(
      fileName: String,
      isSource: Boolean = true,
  ): String =
    if (isSource) s"a/src/main/java/a/$fileName"
    else s"a/$fileName"

  private def getterAndSetter(fieldName: String): String =
    s"""|${GenerateGettersSetters.titleGetter(fieldName)}
        |${GenerateGettersSetters.titleSetter(fieldName)}
        |""".stripMargin

  check(
    "getter-basic",
    """|package a;
       |
       |public class Example {
       |  private String <<name>>;
       |}
       |""".stripMargin,
    getterAndSetter("name"),
    """|package a;
       |
       |public class Example {
       |  private String name;
       |
       |  public String getName() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 0,
    fileName = "Example.java",
  )

  check(
    "setter-basic",
    """|package a;
       |
       |public class Example {
       |  private String <<name>>;
       |}
       |""".stripMargin,
    getterAndSetter("name"),
    """|package a;
       |
       |public class Example {
       |  private String name;
       |
       |  public void setName(String name) {
       |    this.name = name;
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
    fileName = "Example.java",
  )

  check(
    "boolean-getter",
    """|package a;
       |
       |public class Example {
       |  private boolean <<active>>;
       |}
       |""".stripMargin,
    getterAndSetter("active"),
    """|package a;
       |
       |public class Example {
       |  private boolean active;
       |
       |  public boolean isActive() {
       |    return active;
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 0,
    fileName = "Example.java",
  )

  // Object types (incl. the `Boolean` wrapper) use `get`, not `is`.
  check(
    "boolean-wrapper-getter",
    """|package a;
       |
       |public class Example {
       |  private Boolean <<active>>;
       |}
       |""".stripMargin,
    getterAndSetter("active"),
    """|package a;
       |
       |public class Example {
       |  private Boolean active;
       |
       |  public Boolean getActive() {
       |    return active;
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 0,
    fileName = "Example.java",
  )

  check(
    "after-multiple-fields",
    """|package a;
       |
       |public class Example {
       |  private String <<name>>;
       |  private int age;
       |}
       |""".stripMargin,
    getterAndSetter("name"),
    """|package a;
       |
       |public class Example {
       |  private String name;
       |  private int age;
       |
       |  public String getName() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 0,
    fileName = "Example.java",
  )

  check(
    "before-methods",
    """|package a;
       |
       |public class Example {
       |  private String <<name>>;
       |
       |  public String describe() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    getterAndSetter("name"),
    """|package a;
       |
       |public class Example {
       |  private String name;
       |
       |  public String getName() {
       |    return name;
       |  }
       |
       |  public String describe() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 0,
    fileName = "Example.java",
  )

  // A method sharing the field's name must not confuse the insertion point.
  check(
    "method-named-like-field",
    """|package a;
       |
       |public class Example {
       |  private String <<name>>;
       |
       |  public String name() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    getterAndSetter("name"),
    """|package a;
       |
       |public class Example {
       |  private String name;
       |
       |  public String getName() {
       |    return name;
       |  }
       |
       |  public String name() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 0,
    fileName = "Example.java",
  )

  check(
    "inner-class",
    """|package a;
       |
       |public class Outer {
       |  public class Example {
       |    private String <<name>>;
       |  }
       |}
       |""".stripMargin,
    getterAndSetter("name"),
    """|package a;
       |
       |public class Outer {
       |  public class Example {
       |    private String name;
       |
       |    public String getName() {
       |      return name;
       |    }
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 0,
    fileName = "Outer.java",
  )

  check(
    "tab-indented",
    s"""|package a;
        |
        |public class Example {
        |\tprivate String <<name>>;
        |}
        |""".stripMargin,
    getterAndSetter("name"),
    s"""|package a;
        |
        |public class Example {
        |\tprivate String name;
        |
        |\tpublic String getName() {
        |\t\treturn name;
        |\t}
        |}
        |""".stripMargin,
    selectedActionIndex = 0,
    fileName = "Example.java",
  )

  // A `final` field has no setter, since the generated assignment would not
  // compile. Only the getter is offered.
  check(
    "final-field-only-getter",
    """|package a;
       |
       |public class Example {
       |  private final String <<name>> = "";
       |}
       |""".stripMargin,
    s"""|${GenerateGettersSetters.titleGetter("name")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private final String name = "";
       |
       |  public String getName() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 0,
    fileName = "Example.java",
  )

  // A static field gets a static getter/setter.
  check(
    "static-field-getter",
    """|package a;
       |
       |public class Example {
       |  private static String <<name>>;
       |}
       |""".stripMargin,
    getterAndSetter("name"),
    """|package a;
       |
       |public class Example {
       |  private static String name;
       |
       |  public static String getName() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 0,
    fileName = "Example.java",
  )

  check(
    "static-field-setter",
    """|package a;
       |
       |public class Example {
       |  private static String <<name>>;
       |}
       |""".stripMargin,
    getterAndSetter("name"),
    """|package a;
       |
       |public class Example {
       |  private static String name;
       |
       |  public static void setName(String name) {
       |    Example.name = name;
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
    fileName = "Example.java",
  )

  // A getter already exists, so only the setter is offered.
  check(
    "existing-getter-only-setter",
    """|package a;
       |
       |public class Example {
       |  private String <<name>>;
       |
       |  public String getName() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    s"""|${GenerateGettersSetters.titleSetter("name")}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private String name;
       |
       |  public void setName(String name) {
       |    this.name = name;
       |  }
       |
       |  public String getName() {
       |    return name;
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 0,
    fileName = "Example.java",
  )

  // Both getter and setter already exist, so neither action is offered.
  checkNoAction(
    "existing-getter-and-setter",
    """|package a;
       |
       |public class Example {
       |  private String <<name>>;
       |
       |  public String getName() {
       |    return name;
       |  }
       |
       |  public void setName(String name) {
       |    this.name = name;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = action =>
      action.getTitle() == GenerateGettersSetters.titleGetter("name") ||
        action.getTitle() == GenerateGettersSetters.titleSetter("name"),
  )

  checkNoAction(
    "no-action-on-class-name",
    """|package a;
       |
       |public class <<Example>> {
       |  private String name;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = action =>
      action.getTitle() == GenerateGettersSetters.titleGetter("name") ||
        action.getTitle() == GenerateGettersSetters.titleSetter("name"),
  )

}
