package tests.codeactions

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.codeactions.ChangeVariableType

import tests.MbtTestInitializer

class ChangeVariableTypeLspSuite
    extends BaseCodeActionLspSuite(
      "change-variable-type",
      MbtTestInitializer,
      useMbtLayout = true,
    ) {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(presentationCompilerDiagnostics = true)

  override protected def toPath(
      fileName: String,
      isSource: Boolean = true,
  ): String =
    if (isSource) s"a/src/main/java/a/$fileName"
    else s"a/$fileName"

  private val onlyChangeType: org.eclipse.lsp4j.CodeAction => Boolean =
    _.getTitle() == ChangeVariableType.title

  check(
    "string-to-int",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<x>> = "hello";
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    String x = "hello";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "int-to-string",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    String <<s>> = 42;
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int s = 42;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "lossy-conversion",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<ratio>> = 3.5;
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    double ratio = 3.5;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "field",
    """|package a;
       |
       |public class Example {
       |  private int <<count>> = "many";
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  private String count = "many";
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "final-modifier",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    final int <<x>> = "hello";
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    final String x = "hello";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "annotation",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    @Deprecated int <<x>> = "hello";
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    @Deprecated String x = "hello";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "array",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<x>> = new String[]{"a", "b"};
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    String[] x = new String[]{"a", "b"};
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "downcast",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    String <<x>> = new Object();
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    Object x = new Object();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "multi-variable-declaration",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int a = 1, <<b>> = "test", c = 3;
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "diamond-operator",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<x>> = new java.util.ArrayList<>();
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "method-call",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<x>> = name();
       |  }
       |
       |  private String name() {
       |    return "n";
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    String x = name();
       |  }
       |
       |  private String name() {
       |    return "n";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "method-return-not-imported",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<today>> = currentDate();
       |  }
       |
       |  private java.time.LocalDate currentDate() {
       |    return java.time.LocalDate.now();
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    java.time.LocalDate today = currentDate();
       |  }
       |
       |  private java.time.LocalDate currentDate() {
       |    return java.time.LocalDate.now();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "method-return-generic",
    """|package a;
       |
       |import java.util.Map;
       |
       |public class Example {
       |  public void run() {
       |    int <<dates>> = dates();
       |  }
       |
       |  private Map<String, java.time.LocalDate> dates() {
       |    return null;
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.Map;
       |
       |public class Example {
       |  public void run() {
       |    Map<String,java.time.LocalDate> dates = dates();
       |  }
       |
       |  private Map<String, java.time.LocalDate> dates() {
       |    return null;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "imported-generic",
    """|package a;
       |
       |import java.util.ArrayList;
       |
       |public class Example {
       |  public void run() {
       |    int <<names>> = new ArrayList<String>();
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.ArrayList;
       |
       |public class Example {
       |  public void run() {
       |    ArrayList<String> names = new ArrayList<String>();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "not-imported",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<today>> = java.time.LocalDate.now();
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    java.time.LocalDate today = java.time.LocalDate.now();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "commented-import-not-visible",
    """|package a;
       |
       |// import java.time.LocalDate;
       |public class Example {
       |  public void run() {
       |    String ignored = "import java.time.LocalDate;";
       |    int <<today>> = java.time.LocalDate.now();
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |// import java.time.LocalDate;
       |public class Example {
       |  public void run() {
       |    String ignored = "import java.time.LocalDate;";
       |    java.time.LocalDate today = java.time.LocalDate.now();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "same-package",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<other>> = new Other();
       |  }
       |}
       |
       |class Other {}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    Other other = new Other();
       |  }
       |}
       |
       |class Other {}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "legacy-array-declarator",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<values>>[] = new String[]{"a", "b"};
       |  }
       |}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    String values[] = new String[]{"a", "b"};
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "legacy-array-declarator-scalar-found",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<values>>[] = "hello";
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "dollar-qualified-name",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    a.Foo$Bar <<value>> = 42;
       |  }
       |}
       |
       |class Foo$Bar {}
       |""".stripMargin,
    s"""|${ChangeVariableType.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int value = 42;
       |  }
       |}
       |
       |class Foo$Bar {}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "argument-mismatch",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<x>> = twice("a");
       |  }
       |
       |  private int twice(int i) {
       |    return i * 2;
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "return-mismatch",
    """|package a;
       |
       |public class Example {
       |  public String name() {
       |    return <<42>>;
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "cursor-outside",
    """|package a;
       |
       |public class Example {
       |  public void <<run>>() {
       |  }
       |
       |  public void other() {
       |    int x = "hello";
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkNoAction(
    "no-mismatch",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<x>> = 42;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )
}
