package tests.codeactions

import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.codeactions.ImportMissingSymbol

import tests.MbtTestInitializer

class ImportMissingSymbolJavaLspSuite
    extends BaseCodeActionLspSuite(
      "import-missing-symbol-java",
      MbtTestInitializer,
      useMbtLayout = true,
    ) {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      presentationCompilerDiagnostics = true,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
    )

  override protected def toPath(
      fileName: String,
      isSource: Boolean = true,
  ): String =
    if (isSource) s"a/src/main/java/a/$fileName"
    else s"a/$fileName"

  private val onlyImport: org.eclipse.lsp4j.CodeAction => Boolean =
    _.getTitle().startsWith("Import ")

  check(
    "basic",
    """|package a;
       |
       |public class Example {
       |  public static Object now = <<Instant>>.now();
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Instant", "java.time")}
        |""".stripMargin,
    """|package a;
       |
       |import java.time.Instant;
       |
       |
       |public class Example {
       |  public static Object now = Instant.now();
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyImport,
  )

  check(
    "generic-field",
    """|package a;
       |
       |public class Example {
       |  private <<ArrayList>><String> items;
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("ArrayList", "java.util")}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.ArrayList;
       |
       |
       |public class Example {
       |  private ArrayList<String> items;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyImport,
  )

  check(
    "with-existing-import",
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  private List<String> names;
       |  private <<ArrayList>><String> items;
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("ArrayList", "java.util")}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |  private List<String> names;
       |  private ArrayList<String> items;
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyImport,
  )

  check(
    "in-method-body",
    """|package a;
       |
       |public class Example {
       |  public Object create() {
       |    return new <<ArrayList>><String>();
       |  }
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("ArrayList", "java.util")}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.ArrayList;
       |
       |
       |public class Example {
       |  public Object create() {
       |    return new ArrayList<String>();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyImport,
  )

  checkNoAction(
    "already-imported",
    """|package a;
       |
       |import java.time.Instant;
       |
       |public class Example {
       |  public static Object now = <<Instant>>.now();
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyImport,
  )

  checkActionsOnly(
    "non-exact",
    """|package a;
       |
       |public class Example {
       |  public static <<SimpleFileVisit>> visitor = null;
       |}
       |""".stripMargin,
    "",
    fileName = "Example.java",
    filterAction = onlyImport,
  )

  check(
    "import-all",
    """|package a;
       |
       |public class Example {
       |  <<public static Object now = Instant.now();
       |  public static Object list = new ArrayList();>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.allSymbolsTitle}
        |${ImportMissingSymbol.title("Instant", "java.time")}
        |${ImportMissingSymbol.title("ArrayList", "java.util")}
        |""".stripMargin,
    """|package a;
       |import java.time.Instant;
       |
       |import java.util.ArrayList;
       |
       |
       |public class Example {
       |  public static Object now = Instant.now();
       |  public static Object list = new ArrayList();
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyImport,
  )

  check(
    "throws-clause",
    """|package a;
       |
       |public class Example {
       |  public void readFile() throws <<IOException>> {
       |  }
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("IOException", "java.io")}
        |""".stripMargin,
    """|package a;
       |
       |import java.io.IOException;
       |
       |
       |public class Example {
       |  public void readFile() throws IOException {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyImport,
  )

  check(
    "implements-clause",
    """|package a;
       |
       |public class Example implements <<Serializable>> {
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Serializable", "java.io")}
        |""".stripMargin,
    """|package a;
       |
       |import java.io.Serializable;
       |
       |
       |public class Example implements Serializable {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyImport,
  )

  check(
    "annotation",
    """|package a;
       |
       |@<<Documented>>
       |public class Example {
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Documented", "java.lang.annotation")}
        |""".stripMargin,
    """|package a;
       |
       |import java.lang.annotation.Documented;
       |
       |
       |@Documented
       |public class Example {
       |}
       |""".stripMargin,
    fileName = "Example.java",
    expectNoDiagnostics = false,
    filterAction = onlyImport,
  )

  check(
    "default-package",
    """|public class Example {
       |  public static Object now = <<Instant>>.now();
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Instant", "java.time")}
        |""".stripMargin,
    """|import java.time.Instant;
       |
       |public class Example {
       |  public static Object now = Instant.now();
       |}
       |""".stripMargin,
    fileName = "Example.java",
    expectNoDiagnostics = false,
    filterAction = onlyImport,
  )

  check(
    "var-local",
    """|package a;
       |
       |public class Example {
       |  public void run() {
       |    var list = new <<ArrayList>>();
       |  }
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("ArrayList", "java.util")}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.ArrayList;
       |
       |
       |public class Example {
       |  public void run() {
       |    var list = new ArrayList();
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    expectNoDiagnostics = false,
    filterAction = onlyImport,
  )

}
