package tests.j

import scala.meta.internal.metals.codeactions.InlineValueCodeAction

import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}

/**
 * End-to-end tests for the "inline value" refactoring code action on Java
 * sources, which is backed by the Java presentation compiler.
 */
class JavaInlineValueCodeActionSuite
    extends BaseJavaPCSuite("java-inline-value-code-action") {

  check(
    "local-variable",
    """|public class Example {
       |  int run() {
       |    int x = 1 + 2;
       |    return <<x>> + 3;
       |  }
       |}
       |""".stripMargin,
    InlineValueCodeAction.genericTitle,
    """|public class Example {
       |  int run() {
       |    return 1 + 2 + 3;
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "local-variable-needs-brackets",
    """|public class Example {
       |  int run() {
       |    int x = 1 + 2;
       |    return 3 - <<x>>;
       |  }
       |}
       |""".stripMargin,
    InlineValueCodeAction.genericTitle,
    """|public class Example {
       |  int run() {
       |    return 3 - (1 + 2);
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "private-field-inline-all",
    """|public class Example {
       |  private int <<value>> = 42;
       |  int a() { return value; }
       |  int b() { return value + 1; }
       |}
       |""".stripMargin,
    InlineValueCodeAction.genericTitle,
    """|public class Example {
       |  int a() { return 42; }
       |  int b() { return 42 + 1; }
       |}
       |""".stripMargin,
  )

  check(
    "local-in-static-initializer",
    """|public class Example {
       |  static int VALUE;
       |  static {
       |    int <<size>> = 1 + 2;
       |    VALUE = size;
       |  }
       |}
       |""".stripMargin,
    InlineValueCodeAction.genericTitle,
    """|public class Example {
       |  static int VALUE;
       |  static {
       |    VALUE = 1 + 2;
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "private-final-field",
    """|public class Example {
       |  private final int <<MAX>> = 10;
       |  int a() { return MAX; }
       |  int b() { return MAX + 1; }
       |}
       |""".stripMargin,
    InlineValueCodeAction.genericTitle,
    """|public class Example {
       |  int a() { return 10; }
       |  int b() { return 10 + 1; }
       |}
       |""".stripMargin,
  )

  check(
    "multiple-uses-keeps-definition",
    """|public class Example {
       |  int a() {
       |    int x = 5;
       |    int y = <<x>> + 1;
       |    int z = x + 2;
       |    return y + z;
       |  }
       |}
       |""".stripMargin,
    InlineValueCodeAction.genericTitle,
    """|public class Example {
       |  int a() {
       |    int x = 5;
       |    int y = 5 + 1;
       |    int z = x + 2;
       |    return y + z;
       |  }
       |}
       |""".stripMargin,
  )

  checkNotInlined(
    "reassigned-field",
    """|public class Example {
       |  private static long <<preAllocSize>> = 65536 * 1024;
       |  void grow() { preAllocSize = preAllocSize * 2; }
       |  long get() { return preAllocSize; }
       |}
       |""".stripMargin,
  )

  checkNotInlined(
    "reassigned-local-variable",
    """|public class Example {
       |  int run() {
       |    int x = 1;
       |    x = 2;
       |    return <<x>>;
       |  }
       |}
       |""".stripMargin,
  )

  def check(
      name: TestOptions,
      input: String,
      expectedAction: String,
      expectedCode: String,
  )(implicit loc: Location): Unit =
    testLSP(name) {
      cleanWorkspace()
      val path = "a/src/main/java/a/Example.java"
      val fileContent = input.replace("<<", "").replace(">>", "")
      val layout =
        s"""|/metals.json
            |{"a": {}}
            |/$path
            |$fileContent""".stripMargin
      for {
        _ <- initialize(layout)
        _ <- server.didOpen(path)
        codeActions <- server.assertCodeAction(
          path,
          input,
          expectedAction,
          List(l.CodeActionKind.RefactorInline),
        )
        _ <- client.applyCodeAction(0, codeActions, server)
        _ <- server.didChange(path)(_ => server.bufferContents(path))
        _ <- server.didSave(path)
        _ = assertNoDiff(server.bufferContents(path), expectedCode)
      } yield ()
    }

  /**
   * The cheap check offers the action, but invoking it inlines nothing because
   * the presentation compiler rejects it (e.g. a reassigned value), leaving the
   * source unchanged.
   */
  def checkNotInlined(
      name: TestOptions,
      input: String,
  )(implicit loc: Location): Unit =
    check(
      name,
      input,
      InlineValueCodeAction.genericTitle,
      input.replace("<<", "").replace(">>", ""),
    )
}
