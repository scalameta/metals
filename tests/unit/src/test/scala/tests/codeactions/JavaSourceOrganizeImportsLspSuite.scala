package tests.codeactions

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.codeactions.JavaSourceOrganizeImports

import tests.MbtTestInitializer

class JavaSourceOrganizeImportsLspSuite
    extends BaseCodeActionLspSuite(
      "java-source-organize-imports",
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

  private val kind = List(JavaSourceOrganizeImports.kind)

  check(
    "remove-unused-and-sort",
    """|package a;
       |
       |import java.util.Map;
       |import java.util.List;
       |import java.util.Set;
       |
       |public class <<Example>> {
       |  public List<String> names;
       |  public Map<String, String> map;
       |}
       |""".stripMargin,
    s"""|${JavaSourceOrganizeImports.title}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.List;
       |import java.util.Map;
       |
       |public class Example {
       |  public List<String> names;
       |  public Map<String, String> map;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    kind = kind,
  )

  check(
    "static-imports-first",
    """|package a;
       |
       |import java.util.List;
       |import static java.lang.Math.max;
       |
       |public class <<Example>> {
       |  public List<Integer> values;
       |  public int m = max(1, 2);
       |}
       |""".stripMargin,
    s"""|${JavaSourceOrganizeImports.title}
        |""".stripMargin,
    """|package a;
       |
       |import static java.lang.Math.max;
       |
       |import java.util.List;
       |
       |public class Example {
       |  public List<Integer> values;
       |  public int m = max(1, 2);
       |}
       |""".stripMargin,
    fileName = "Example.java",
    kind = kind,
  )

  check(
    "does-not-reformat-body",
    """|package a;
       |
       |import java.util.List;
       |import java.util.Map;
       |
       |public class <<Example>> {
       |      public   List<String>   names;
       |}
       |""".stripMargin,
    s"""|${JavaSourceOrganizeImports.title}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |      public   List<String>   names;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    kind = kind,
  )

  check(
    "already-organized",
    """|package a;
       |
       |import java.util.List;
       |
       |public class <<Example>> {
       |  public List<String> names;
       |}
       |""".stripMargin,
    "",
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  public List<String> names;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    kind = kind,
  )
}
