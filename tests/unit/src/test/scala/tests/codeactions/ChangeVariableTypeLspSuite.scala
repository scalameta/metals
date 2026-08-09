package tests.codeactions

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.codeactions.ChangeVariableType

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Diagnostic
import tests.MbtTestInitializer

class ChangeVariableTypeLspSuite
    extends BaseCodeActionLspSuite(
      "change-variable-type",
      MbtTestInitializer,
      useMbtLayout = true,
    ) {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(presentationCompilerDiagnostics = true)

  override protected def defaultAwaitDiagnostics
      : Option[Seq[Diagnostic] => Boolean] =
    Some(_.nonEmpty)

  override protected def toPath(
      fileName: String,
      isSource: Boolean = true,
  ): String =
    if (isSource) s"a/src/main/java/a/$fileName"
    else s"a/$fileName"

  private val onlyChangeType: CodeAction => Boolean =
    _.getTitle() == ChangeVariableType.title
  private val expectedAction = s"${ChangeVariableType.title}\n"

  check(
    "string-to-int",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x = <<"hello">>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
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
       |    String s = <<42>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
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
       |    int ratio = <<3.5>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
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
    "autoboxing-wrapper",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    Long x = <<42>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x = 42;
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
       |  private int count = <<"many">>;
       |}
       |""".stripMargin,
    expectedAction,
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
    "interface-field",
    """|package a;
       |
       |public interface Example {
       |  int CONST = <<"hello">>;
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public interface Example {
       |  String CONST = "hello";
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "try-with-resources",
    """|package a;
       |
       |import java.io.ByteArrayInputStream;
       |
       |public class Example {
       |  public void run() throws Exception {
       |    try (int stream = <<new ByteArrayInputStream(new byte[0])>>) {}
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.io.ByteArrayInputStream;
       |
       |public class Example {
       |  public void run() throws Exception {
       |    try (ByteArrayInputStream stream = new ByteArrayInputStream(new byte[0])) {}
       |  }
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
       |    final int x = <<"hello">>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
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
       |    @Deprecated int x = <<"hello">>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
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
    "multiple-modifiers-and-annotations",
    """|package a;
       |
       |public class Example {
       |  @Deprecated
       |  public static final int x = <<"hello">>;
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  @Deprecated
       |  public static final String x = "hello";
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "comments-in-declaration",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int /* comment */ x = <<"hello">>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    String /* comment */ x = "hello";
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
       |    int x = <<new String[]{"a", "b"}>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
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

  checkActionsOnly(
    "implicit-array-initializer",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x = <<{"a", "b"}>>;
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "multi-dimensional-array",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x = <<new String[2][2]>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    String[][] x = new String[2][2];
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
       |    String x = <<new Object()>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
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
    "null-to-primitive",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x = <<null>>;
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "anonymous-class",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x = <<new Runnable() {
       |      public void run() {}
       |    }>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    Runnable x = new Runnable() {
       |      public void run() {}
       |    };
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    expectNoDiagnostics = false,
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "multi-variable-declaration",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int a = 1, b = <<"test">>, c = 3;
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "multi-variable-declaration-comment-before-comma",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int a = <<"test">> /* c */, b = 1;
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "mixed-array-declarator",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int a, b[] = <<new String[]{"test"}>>;
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
       |    int x = <<new java.util.ArrayList<>()>>;
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
       |    int x = <<name()>>;
       |  }
       |
       |  private String name() {
       |    return "n";
       |  }
       |}
       |""".stripMargin,
    expectedAction,
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

  checkActionsOnly(
    "void-return",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x = <<doSomething()>>;
       |  }
       |
       |  private void doSomething() {}
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "unresolved-symbol",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x = <<doesNotExist()>>;
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "unresolved-generic-target",
    """|package a;
       |
       |import java.util.Collections;
       |
       |public class Example {
       |  public void run() {
       |    int x = <<Collections.emptyList()>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.util.Collections;
       |import java.util.List;
       |
       |public class Example {
       |  public void run() {
       |    List<Object> x = Collections.emptyList();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "ternary-operator",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x = <<true ? "hello" : "world">>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    String x = true ? "hello" : "world";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "switch-expression",
    """|package a;
       |
       |public class Example {
       |  public void run(int y) {
       |    int x = <<switch (y) {
       |      case 1 -> "A";
       |      default -> "B";
       |    }>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  public void run(int y) {
       |    String x = switch (y) {
       |      case 1 -> "A";
       |      default -> "B";
       |    };
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "method-reference",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x = <<String::length>>;
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "lambda-expression",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x = <<() -> {}>>;
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  checkActionsOnly(
    "intersection-type",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int value = <<(Runnable & Marker) () -> {}>>;
       |  }
       |}
       |
       |interface Marker {}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "method-return-not-imported",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int today = <<currentDate()>>;
       |  }
       |
       |  private java.time.LocalDate currentDate() {
       |    return java.time.LocalDate.now();
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.time.LocalDate;
       |
       |public class Example {
       |  public void run() {
       |    LocalDate today = currentDate();
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
       |    int dates = <<dates()>>;
       |  }
       |
       |  private Map<String, java.time.LocalDate> dates() {
       |    return null;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.util.Map;
       |import java.time.LocalDate;
       |
       |public class Example {
       |  public void run() {
       |    Map<String, LocalDate> dates = dates();
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
    "bounded-wildcard-return",
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  public void run() {
       |    int values = <<getList()>>;
       |  }
       |
       |  private List<? extends CharSequence> getList() {
       |    return null;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  public void run() {
       |    List<? extends CharSequence> values = getList();
       |  }
       |
       |  private List<? extends CharSequence> getList() {
       |    return null;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "method-return-generic-not-imported",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int dates = <<dates()>>;
       |  }
       |
       |  private java.util.Map<String, java.time.LocalDate> dates() {
       |    return null;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.time.LocalDate;
       |import java.util.Map;
       |
       |public class Example {
       |  public void run() {
       |    Map<String, LocalDate> dates = dates();
       |  }
       |
       |  private java.util.Map<String, java.time.LocalDate> dates() {
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
       |    int names = <<new ArrayList<String>()>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
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
       |    int today = <<java.time.LocalDate.now()>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.time.LocalDate;
       |
       |public class Example {
       |  public void run() {
       |    LocalDate today = java.time.LocalDate.now();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "wildcard-import",
    """|package a;
       |
       |import java.time.*;
       |
       |public class Example {
       |  public void run() {
       |    int today = <<LocalDate.now()>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.time.*;
       |
       |public class Example {
       |  public void run() {
       |    LocalDate today = LocalDate.now();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "conflicting-wildcard-imports",
    """|package a;
       |
       |import java.sql.*;
       |import java.util.*;
       |
       |public class Example {
       |  public void run() {
       |    int date = <<new java.util.Date()>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.sql.*;
       |import java.util.*;
       |import java.util.Date;
       |
       |public class Example {
       |  public void run() {
       |    Date date = new java.util.Date();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    expectNoDiagnostics = false,
    filterAction = onlyChangeType,
  )

  check(
    "multiple-unambiguous-wildcard-imports",
    """|package a;
       |
       |import java.time.*;
       |import java.util.*;
       |
       |public class Example {
       |  public void run() {
       |    int today = <<LocalDate.now()>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.time.*;
       |import java.util.*;
       |
       |public class Example {
       |  public void run() {
       |    LocalDate today = LocalDate.now();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    expectNoDiagnostics = false,
    filterAction = onlyChangeType,
  )

  check(
    "static-wildcard-import",
    """|package a;
       |
       |import static java.time.DayOfWeek.*;
       |
       |public class Example {
       |  public void run() {
       |    int today = <<java.time.LocalDate.now()>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import static java.time.DayOfWeek.*;
       |import java.time.LocalDate;
       |
       |public class Example {
       |  public void run() {
       |    LocalDate today = java.time.LocalDate.now();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    expectNoDiagnostics = false,
    filterAction = onlyChangeType,
  )

  check(
    "not-imported-name-clash",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int today = <<java.time.LocalDate.now()>>;
       |  }
       |
       |  static class LocalDate {}
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    java.time.LocalDate today = java.time.LocalDate.now();
       |  }
       |
       |  static class LocalDate {}
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "outer-member-name-clash",
    """|package a;
       |
       |import java.time.*;
       |
       |public class Example {
       |  static class LocalDate {}
       |
       |  static class Nested {
       |    public void run() {
       |      int today = <<java.time.LocalDate.now()>>;
       |    }
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.time.*;
       |
       |public class Example {
       |  static class LocalDate {}
       |
       |  static class Nested {
       |    public void run() {
       |      java.time.LocalDate today = java.time.LocalDate.now();
       |    }
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    expectNoDiagnostics = false,
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
       |    int today = <<java.time.LocalDate.now()>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |import java.time.LocalDate;
       |
       |// import java.time.LocalDate;
       |public class Example {
       |  public void run() {
       |    String ignored = "import java.time.LocalDate;";
       |    LocalDate today = java.time.LocalDate.now();
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
       |    int other = <<new Other()>>;
       |  }
       |}
       |
       |class Other {}
       |""".stripMargin,
    expectedAction,
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
    "same-package-nested-class",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int inner = <<new a.Outer.Inner()>>;
       |  }
       |}
       |
       |class Outer {
       |  static class Inner {}
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    Outer.Inner inner = new a.Outer.Inner();
       |  }
       |}
       |
       |class Outer {
       |  static class Inner {}
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "generic-inner-class",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int inner = <<new Outer<String>().new Inner<Integer>()>>;
       |  }
       |}
       |
       |class Outer<T> {
       |  class Inner<U> {}
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    Outer<String>.Inner<Integer> inner = new Outer<String>().new Inner<Integer>();
       |  }
       |}
       |
       |class Outer<T> {
       |  class Inner<U> {}
       |}
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
       |    int values[] = <<new String[]{"a", "b"}>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
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

  check(
    "legacy-array-comment-before-name",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int /* values */ values[] = <<new String[]{"a", "b"}>>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    String /* values */ values[] = new String[]{"a", "b"};
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "legacy-array-comment-and-annotation",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int values /* comment */ @Dimension [] = <<new String[]{"a", "b"}>>;
       |  }
       |}
       |
       |@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
       |@interface Dimension {}
       |""".stripMargin,
    expectedAction,
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    String values /* comment */ @Dimension [] = new String[]{"a", "b"};
       |  }
       |}
       |
       |@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
       |@interface Dimension {}
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
       |    int values[] = <<"hello">>;
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
       |    a.Foo$Bar value = <<42>>;
       |  }
       |}
       |
       |class Foo$Bar {}
       |""".stripMargin,
    expectedAction,
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
       |    int x = twice(<<"a">>);
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
    "reassignment",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int x;
       |    x = <<"hello">>;
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

  checkActionsOnly(
    "cursor-on-variable-name",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<x>> = "hello";
       |  }
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyChangeType,
  )

  check(
    "selection-overlaps-initializer",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    int <<x = "hello">>;
       |  }
       |}
       |""".stripMargin,
    expectedAction,
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
