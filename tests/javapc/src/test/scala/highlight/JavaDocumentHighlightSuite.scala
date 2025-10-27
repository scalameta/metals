package tests.highlight

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.DocumentHighlight
import tests.RangeReplace
import tests.pc.BaseJavaPCSuite

class JavaDocumentHighlightSuite extends BaseJavaPCSuite with RangeReplace {

  check(
    "basic",
    """|class A {
       |    public static int <<NUMBER>> = 42;
       |
       |    public static void main(String args[]){
       |        int x = <<NU@@MBER>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "basic-def",
    """|class A {
       |    public static int <<NUM@@BER>> = 42;
       |
       |    public static void main(String args[]){
       |        int x = <<NUMBER>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "local-variable",
    """|class A {
       |    public static void main(String args[]){
       |        int <<x>> = 42;
       |        int y = <<@@x>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "local-variable-definition",
    """|class A {
       |    public static void main(String args[]){
       |        int <<@@x>> = 42;
       |        int y = <<x>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "method",
    """|class A {
       |    private static int <<foo>>() {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      <<fo@@o>>();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "no-name",
    """|class A {
       |    priv@@ate static int foo() {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      foo();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "method-definition",
    """|class A {
       |    private static int <<f@@oo>>() {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      <<foo>>();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "method-with-args",
    """|import java.util.List;
       |
       |class A {
       |    static int <<foo>>(int x, String s) {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      <<fo@@o>>(1, "str");
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "class-reference",
    """|class <<Foo>> {
       |    public int value = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      <<Fo@@o>> f = new <<Foo>>();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "class-definition",
    """|class <<F@@oo>> {
       |    public int value = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      <<Foo>> f = new <<Foo>>();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "new-instance",
    """|class <<Foo>> {
       |    public int value = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      <<Foo>> f = new <<Fo@@o>>();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "field-access",
    """|class Foo {
       |    public int <<value>> = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Foo f = new Foo();
       |      int x = f.<<val@@ue>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "field-definition",
    """|class Foo {
       |    public int <<val@@ue>> = 42;
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Foo f = new Foo();
       |      int x = f.<<value>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "parameter",
    """|class A {
       |    static void foo(int <<x>>) {
       |       System.out.println(<<@@x>>);
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "parameter-definition",
    """|class A {
       |    static void foo(int <<@@x>>) {
       |       System.out.println(<<x>>);
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "constructor-parameter",
    """|class Foo {
       |    private int value;
       |    public Foo(int <<value>>) {
       |        this.value = <<va@@lue>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "constructor-parameter-def",
    """|class Foo {
       |    private int value;
       |    public Foo(int <<va@@lue>>) {
       |        this.value = <<value>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "this-reference",
    """|class Foo {
       |    private int <<value>>;
       |    public void setValue(int value) {
       |        this.<<val@@ue>> = value;
       |    }
       |}
       |""".stripMargin,
  )
  check(
    "this-reference-def",
    """|class Foo {
       |    private int <<va@@lue>>;
       |    public void setValue(int value) {
       |        this.<<value>> = value;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "enum-constant",
    """|enum Color {
       |    <<RED>>, GREEN, BLUE
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Color c = Color.<<R@@ED>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "enum-constant-definition",
    """|enum Color {
       |    <<R@@ED>>, GREEN, BLUE
       |}
       |
       |class A {
       |    public static void main(String args[]){
       |      Color c = Color.<<RED>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "method-overload",
    """|class A {
       |    static void <<foo>>(int x) {}
       |    static void foo(String s) {}
       |
       |    public static void main(String args[]){
       |      <<fo@@o>>(42);
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "for-loop-variable",
    """|class A {
       |    public static void main(String args[]){
       |      for (int <<i>> = 0; <<@@i>> < 10; <<i>>++) {
       |        System.out.println(<<i>>);
       |      }
       |    }
       |}
       |""".stripMargin,
  )

  def check(
      name: TestOptions,
      original: String,
      automaticPackage: Boolean = true,
  )(implicit location: Location): Unit =
    test(name) {
      val pkg = packageName(name.name)
      val edit = original.replaceAll("(<<|>>)", "")
      val expected = original.replaceAll("@@", "")
      val base = original.replaceAll("(<<|>>|@@)", "")

      val packagePrefix =
        if (automaticPackage) s"package $pkg;\n"
        else ""

      val codeOriginal = packagePrefix + edit
      val expectedCode = packagePrefix + expected
      val baseCode = packagePrefix + base

      val (code, offset) = params(codeOriginal, "Highlight.java")
      val highlights = presentationCompiler
        .documentHighlight(
          CompilerOffsetParams(
            URI.create("file:/Highlight.java"),
            code,
            offset,
            EmptyCancelToken,
          )
        )
        .get()
        .asScala
        .toList
        .sortWith(compareHighlights)
        .reverse

      assertEquals(
        renderHighlightsAsString(baseCode, highlights),
        expectedCode,
      )
    }

  private def packageName(name: String): String = {
    name.toLowerCase.split(" ").mkString("_").replaceAll("-", "_")
  }

  private def compareHighlights(
      h1: DocumentHighlight,
      h2: DocumentHighlight,
  ) = {
    val r1 = h1.getRange().getStart()
    val r2 = h2.getRange().getStart()
    r1.getLine() < r2.getLine() || (r1.getLine() == r2.getLine() && r1
      .getCharacter() < r2.getCharacter())
  }
}
