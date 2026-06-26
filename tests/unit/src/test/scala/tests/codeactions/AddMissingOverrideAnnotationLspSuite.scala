package tests.codeactions

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.codeactions.AddMissingOverrideAnnotation

import tests.BazelMbtTestInitializer

class AddMissingOverrideAnnotationLspSuite
    extends BaseCodeActionLspSuite(
      "add-missing-override-annotation",
      BazelMbtTestInitializer,
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

  private def title: String =
    s"""|${AddMissingOverrideAnnotation.title}
        |""".stripMargin

  private val onlyAddOverride: org.eclipse.lsp4j.CodeAction => Boolean =
    _.getTitle() == AddMissingOverrideAnnotation.title

  check(
    "object-method",
    """|package a;
       |
       |public class Example {
       |  public String <<toString>>() {
       |    return "";
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  @Override
       |  public String toString() {
       |    return "";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddOverride,
  )

  check(
    "existing-annotation",
    """|package a;
       |
       |public class Example {
       |  @Deprecated
       |  public String <<toString>>() {
       |    return "";
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  @Override
       |  @Deprecated
       |  public String toString() {
       |    return "";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddOverride,
  )

  check(
    "interface-method",
    """|package a;
       |
       |interface Named {
       |  String name();
       |}
       |
       |public class Example implements Named {
       |  public String <<name>>() {
       |    return "";
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |interface Named {
       |  String name();
       |}
       |
       |public class Example implements Named {
       |  @Override
       |  public String name() {
       |    return "";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddOverride,
  )

  checkNoAction(
    "already-has-override",
    """|package a;
       |
       |public class Example {
       |  @Override
       |  public String <<toString>>() {
       |    return "";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddOverride,
  )

  checkNoAction(
    "custom-method",
    """|package a;
       |
       |public class Example {
       |  public String <<myCustomMethod>>() {
       |    return "";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddOverride,
  )

  checkNoAction(
    "static-method",
    """|package a;
       |
       |public class Example {
       |  public static void <<main>>(String[] args) {}
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddOverride,
  )

  checkNoAction(
    "constructor",
    """|package a;
       |
       |public class Example {
       |  public <<Example>>() {}
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddOverride,
  )

  check(
    "generic-type",
    """|package a;
       |
       |interface Box<T> { void put(T item); }
       |
       |public class Example implements Box<String> {
       |  public void <<put>>(String item) {}
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |interface Box<T> { void put(T item); }
       |
       |public class Example implements Box<String> {
       |  @Override
       |  public void put(String item) {}
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddOverride,
  )

  check(
    "javadoc",
    """|package a;
       |
       |public class Example {
       |  /**
       |   * My custom toString.
       |   */
       |  public String <<toString>>() {
       |    return "";
       |  }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  /**
       |   * My custom toString.
       |   */
       |  @Override
       |  public String toString() {
       |    return "";
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddOverride,
  )
}
