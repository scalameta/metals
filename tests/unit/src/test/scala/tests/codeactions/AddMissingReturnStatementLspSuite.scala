package tests.codeactions

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.codeactions.AddMissingReturnStatement

import tests.MbtTestInitializer

class AddMissingReturnStatementLspSuite
    extends BaseCodeActionLspSuite(
      "add-missing-return-statement",
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

  private val onlyAddReturn: org.eclipse.lsp4j.CodeAction => Boolean =
    _.getTitle() == AddMissingReturnStatement.title

  // Wait for the async "missing return" diagnostic
  override protected def defaultAwaitDiagnostics
      : Option[Seq[org.eclipse.lsp4j.Diagnostic] => Boolean] =
    Some(
      _.exists(AddMissingReturnStatement.hasMissingReturnMessage)
    )

  check(
    "int",
    """|package a;
       |
       |public class Example {
       |  public int <<answer>>() {
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public int answer() {
       |    return 0;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "boolean",
    """|package a;
       |
       |public class Example {
       |  public boolean <<isReady>>() {
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public boolean isReady() {
       |    return false;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "annotated-primitive",
    """|package a;
       |
       |import java.lang.annotation.ElementType;
       |import java.lang.annotation.Target;
       |
       |@Target(ElementType.TYPE_USE)
       |@interface A {}
       |
       |public class Example {
       |  public @A int <<answer>>() {
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |import java.lang.annotation.ElementType;
       |import java.lang.annotation.Target;
       |
       |@Target(ElementType.TYPE_USE)
       |@interface A {}
       |
       |public class Example {
       |  public @A int answer() {
       |    return 0;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "double",
    """|package a;
       |
       |public class Example {
       |  public double <<getScore>>() {
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public double getScore() {
       |    return 0.0;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "long",
    """|package a;
       |
       |public class Example {
       |  public long <<getId>>() {
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public long getId() {
       |    return 0L;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "float",
    """|package a;
       |
       |public class Example {
       |  public float <<getRatio>>() {
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public float getRatio() {
       |    return 0.0f;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "reference",
    """|package a;
       |
       |public class Example {
       |  public String <<name>>() {
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public String name() {
       |    return null;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "primitive-array",
    """|package a;
       |
       |public class Example {
       |  public int[] <<getNumbers>>() {
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public int[] getNumbers() {
       |    return null;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "generic",
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  public List<String> <<names>>() {
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  public List<String> names() {
       |    return null;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "cursor-inside-empty-body",
    """|package a;
       |
       |public class Example {
       |  public int answer() {
       |    <<>>
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public int answer() {
       |    return 0;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "cursor-inside-non-empty-body",
    """|package a;
       |
       |public class Example {
       |  public int answer() {
       |    int x = 1;<<>>
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public int answer() {
       |    int x = 1;
       |    return 0;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "comment-only-body",
    """|package a;
       |
       |public class Example {
       |  public int <<answer>>() {
       |    // TODO
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public int answer() {
       |    // TODO
       |    return 0;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "same-line-comment-only-body",
    """|package a;
       |
       |public class Example {
       |  public int <<answer>>() { /* todo */ }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public int answer() { /* todo */
       |    return 0;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "same-line",
    """|package a;
       |
       |public class Example {
       |  public int <<answer>>() {}
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public int answer() {
       |    return 0;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  checkNoAction(
    "if-else-returns",
    """|package a;
       |
       |public class Example {
       |  public int <<answer>>(boolean flag) {
       |    if (flag) {
       |      return 1;
       |    } else {
       |      return 2;
       |    }
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "existing-return-in-lambda",
    """|package a;
       |
       |public class Example {
       |  public int <<answer>>() {
       |    Runnable runnable = () -> {
       |      return;
       |    };
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public class Example {
       |  public int answer() {
       |    Runnable runnable = () -> {
       |      return;
       |    };
       |    return 0;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  checkNoAction(
    "abstract",
    """|package a;
       |
       |public abstract class Example {
       |  public abstract int <<calculate>>();
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  checkNoAction(
    "interface",
    """|package a;
       |
       |public interface Example {
       |  int <<calculate>>();
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  check(
    "interface-default-method",
    """|package a;
       |
       |public interface Example {
       |  default int <<calculate>>() {
       |  }
       |}
       |""".stripMargin,
    s"""|${AddMissingReturnStatement.title}
        |""".stripMargin,
    """|package a;
       |
       |public interface Example {
       |  default int calculate() {
       |    return 0;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  checkNoAction(
    "void",
    """|package a;
       |
       |public class Example {
       |  public void <<run>>() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  checkNoAction(
    "constructor",
    """|package a;
       |
       |public class Example {
       |  public <<Example>>() {
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )

  checkNoAction(
    "existing-return",
    """|package a;
       |
       |public class Example {
       |  public int <<answer>>() {
       |    return 42;
       |  }
       |}
       |""".stripMargin,
    fileName = "Example.java",
    filterAction = onlyAddReturn,
  )
}
