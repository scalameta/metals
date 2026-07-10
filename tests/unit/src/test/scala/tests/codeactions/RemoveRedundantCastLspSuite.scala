package tests.codeactions

import scala.meta.internal.metals.JavaLintOptions
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.codeactions.RemoveRedundantCast

import tests.MbtTestInitializer

class RemoveRedundantCastLspSuite
    extends BaseCodeActionLspSuite(
      "remove-redundant-cast",
      MbtTestInitializer,
      useMbtLayout = true,
    ) {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      presentationCompilerDiagnostics = true,
      javaLintOptions = JavaLintOptions(List("cast")),
    )

  override protected def toPath(
      fileName: String,
      isSource: Boolean = true,
  ): String =
    if (isSource) s"a/src/main/java/a/$fileName"
    else s"a/$fileName"

  private val onlyRemoveRedundantCast: org.eclipse.lsp4j.CodeAction => Boolean =
    _.getTitle() == RemoveRedundantCast.title

  private def title: String =
    s"""|${RemoveRedundantCast.title}
        |""".stripMargin

  check(
    "basic",
    """|package a;
       |
       |public class Example {
       |  public String run() {
       |    return <<(String) "value">>;
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public String run() {
       |    return "value";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "without-space",
    """|package a;
       |
       |public class Example {
       |  public String run(String value) {
       |    return <<(String)value>>;
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public String run(String value) {
       |    return value;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "nested-parentheses",
    """|package a;
       |
       |public class Example {
       |  public String run(String value) {
       |    return (<<(String) value>>);
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public String run(String value) {
       |    return (value);
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "generic-type",
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  public String run(List<String> list) {
       |    return (<<(List<String>) list>>).get(0);
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  public String run(List<String> list) {
       |    return (list).get(0);
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "array-type",
    """|package a;
       |
       |public class Example {
       |  public String run(String[] array) {
       |    return (<<(String[]) array>>)[0];
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public String run(String[] array) {
       |    return (array)[0];
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "primitive-type",
    """|package a;
       |
       |public class Example {
       |  public int run() {
       |    return <<(int) 5>>;
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public int run() {
       |    return 5;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "method-invocation",
    """|package a;
       |
       |public class Example {
       |  public String run(Object obj) {
       |    return <<(String) obj.toString()>>;
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public String run(Object obj) {
       |    return obj.toString();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "extra-spaces",
    """|package a;
       |
       |public class Example {
       |  public String run(String value) {
       |    return <<(  String  )   value>>;
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public String run(String value) {
       |    return value;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "newline-after-cast",
    """|package a;
       |
       |public class Example {
       |  public String run(String value) {
       |    return <<(String)
       |      value>>;
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public String run(String value) {
       |    return
       |      value;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "variable-declaration",
    """|package a;
       |
       |public class Example {
       |  public String run() {
       |    String s = <<(String) "value">>;
       |    return s;
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public String run() {
       |    String s = "value";
       |    return s;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "intersection-type",
    """|package a;
       |
       |import java.io.Serializable;
       |
       |public class Example {
       |  public <T extends String & Serializable> String run(T t) {
       |    return <<(String & Serializable) t>>;
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |import java.io.Serializable;
       |
       |public class Example {
       |  public <T extends String & Serializable> String run(T t) {
       |    return t;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "this-cast",
    """|package a;
       |
       |public class Example {
       |  public Example run() {
       |    return <<(Example) this>>;
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public Example run() {
       |    return this;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "lambda-cast",
    """|package a;
       |
       |import java.util.List;
       |import java.util.stream.Collectors;
       |
       |public class Example {
       |  public List<String> run(List<String> list) {
       |    return list.stream().map(x -> <<(String) x>>).collect(Collectors.toList());
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |import java.util.List;
       |import java.util.stream.Collectors;
       |
       |public class Example {
       |  public List<String> run(List<String> list) {
       |    return list.stream().map(x -> x).collect(Collectors.toList());
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )

  check(
    "lambda-return-cast",
    """|package a;
       |
       |import java.util.List;
       |import java.util.stream.Collectors;
       |
       |public class Example {
       |  public List<String> run(List<String> list) {
       |    return list.stream().map(x -> {
       |      return <<(String) x>>;
       |    }).collect(Collectors.toList());
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |import java.util.List;
       |import java.util.stream.Collectors;
       |
       |public class Example {
       |  public List<String> run(List<String> list) {
       |    return list.stream().map(x -> {
       |      return x;
       |    }).collect(Collectors.toList());
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveRedundantCast,
  )
}
