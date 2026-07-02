package tests.codeactions

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.codeactions.RemoveUnusedJavaImport

import tests.MbtJsonBuilder
import tests.MbtTestInitializer

class RemoveUnusedJavaImportLspSuite
    extends BaseCodeActionLspSuite(
      "remove-unused-java-import",
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

  private val onlyRemoveUnusedImport: org.eclipse.lsp4j.CodeAction => Boolean =
    _.getTitle() == RemoveUnusedJavaImport.title

  private def title: String =
    s"""|${RemoveUnusedJavaImport.title}
        |""".stripMargin

  check(
    "basic",
    """|package a;
       |
       |import java.util.List;
       |<<import java.util.Map;>>
       |
       |public class Example {
       |  public List<String> names;
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  public List<String> names;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  checkNoAction(
    "used",
    """|package a;
       |
       |<<import java.util.List;>>
       |
       |public class Example {
       |  public List<String> names;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "static-import",
    """|package a;
       |
       |import static java.lang.Math.PI;
       |<<import static java.lang.Math.max;>>
       |
       |public class Example {
       |  public double getPi() { return PI; }
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |import static java.lang.Math.PI;
       |
       |public class Example {
       |  public double getPi() { return PI; }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "multiple-unused",
    """|package a;
       |
       |<<import java.util.Map;>>
       |import java.util.Set;
       |
       |public class Example {
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |import java.util.Set;
       |
       |public class Example {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    expectNoDiagnostics = false,
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "inner-class",
    """|package a;
       |
       |<<import java.util.Map.Entry;>>
       |
       |public class Example {
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "with-inline-comment",
    """|package a;
       |
       |<<import java.util.List; // this is unused>>
       |
       |public class Example {
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "block-comment",
    """|package a;
       |
       |<<import java.util. /* unused */ Map;>>
       |
       |public class Example {
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "multiline-import",
    """|package a;
       |
       |<<import java.util.
       |  Map;>>
       |
       |public class Example {
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "wildcard-import",
    """|package a;
       |
       |<<import java.util.*;>>
       |
       |public class Example {
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  checkNoAction(
    "wildcard-import-used",
    """|package a;
       |
       |<<import java.util.*;>>
       |
       |public class Example {
       |  public List<String> names;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "static-wildcard-import",
    """|package a;
       |
       |<<import static java.lang.Math.*;>>
       |
       |public class Example {
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  checkNoAction(
    "static-wildcard-import-used",
    """|package a;
       |
       |<<import static java.lang.Math.*;>>
       |
       |public class Example {
       |  public int maxValue() { return max(1, 2); }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "no-package",
    """|<<import java.util.List;>>
       |
       |public class Example {
       |}
       |""".stripMargin,
    title,
    """|public class Example {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "java-lang-redundant",
    """|package a;
       |
       |<<import java.lang.String;>>
       |
       |public class Example {
       |  public String name;
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public String name;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "same-package-redundant",
    """|package a;
       |
       |<<import a.Helper;>>
       |
       |public class Example {
       |  public Helper helper;
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  public Helper helper;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
    overrideLayout = Some(
      s"""|/.metals/mbt.json
          |$mbtJson
          |/a/src/main/java/a/Helper.java
          |package a;
          |
          |public class Helper {
          |}
          |/a/src/main/java/a/Example.java
          |package a;
          |
          |<<import a.Helper;>>
          |
          |public class Example {
          |  public Helper helper;
          |}
          |""".stripMargin
    ),
  )

  check(
    "duplicate-import",
    """|package a;
       |
       |import java.util.List;
       |<<import java.util.List;>>
       |
       |public class Example {
       |  public List<String> names;
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  public List<String> names;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "duplicate-static-nested-type-import",
    """|package a;
       |
       |import java.util.Map.Entry;
       |<<import static java.util.Map.Entry;>>
       |
       |public class Example {
       |  public Entry entry;
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |import java.util.Map.Entry;
       |
       |public class Example {
       |  public Entry entry;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  check(
    "member-type-shadows-import",
    """|package a;
       |
       |<<import java.util.List;>>
       |
       |public class Example {
       |  static class List {
       |  }
       |
       |  public List names;
       |}
       |""".stripMargin,
    title,
    """|package a;
       |
       |public class Example {
       |  static class List {
       |  }
       |
       |  public List names;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  checkNoAction(
    "javadoc-only",
    """|package a;
       |
       |<<import java.util.Map;>>
       |
       |/**
       | * Returns data as a {@link Map}.
       | */
       |public class Example {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyRemoveUnusedImport,
  )

  private def mbtJson: String =
    new MbtJsonBuilder(scalaVersion)
      .addNamespace("a", List("a/src/main/java/**", "a/src/main/scala/**"))
      .build()
}
