package tests.rename

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.TextEdits

import munit.Location
import munit.TestOptions
import tests.RangeReplace
import tests.pc.BaseJavaPCSuite

class JavaRenameSuite extends BaseJavaPCSuite with RangeReplace {

  check(
    "basic",
    """|class A {
       |    private static int <<NUMBER>> = 42;
       |
       |    public static void main(String args[]){
       |        int x = <<NU@@MBER>>;
       |    }
       |}
       |""".stripMargin,
  )

  checkPrepare(
    "basic-prepare",
    """|class A {
       |    private static int <<NUM@@BER>> = 42;
       |
       |    public static void main(String args[]){
       |        int x = NUMBER;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "basic-public",
    """|class A {
       |    public static int NUMBER = 42;
       |
       |    public static void main(String args[]){
       |        int x = NU@@MBER;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "basic-public-prepare",
    """|class A {
       |    public static int NUMBER = 42;
       |
       |    public static void main(String args[]){
       |        int x = NU@@MBER;
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
  checkPrepare(
    "local-variable-prepare",
    """|class A {
       |    public static void main(String args[]){
       |        int x = 42;
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

  checkPrepare(
    "local-variable-definition-prepare",
    """|class A {
       |    public static void main(String args[]){
       |        int <<@@x>> = 42;
       |        int y = x;
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

  checkPrepare(
    "method-prepare",
    """|class A {
       |    private static int foo() {
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
    "method-public",
    """|class A {
       |    public static int foo() {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      fo@@o();
       |    }
       |}
       |""".stripMargin,
  )

  checkPrepare(
    "method-public-prepare",
    """|class A {
       |    public static int foo() {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      fo@@o();
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

  checkPrepare(
    "no-name-prepare",
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

  checkPrepare(
    "method-definition-prepare",
    """|class A {
       |    private static int <<f@@oo>>() {
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
    "method-with-args",
    """|import java.util.List;
       |
       |class A {
       |    private static int <<foo>>(int x, String s) {
       |       return 42;
       |    }
       |
       |    public static void main(String args[]){
       |      <<fo@@o>>(1, "str");
       |    }
       |}
       |""".stripMargin,
  )

  checkPrepare(
    "method-with-args-prepare",
    """|import java.util.List;
       |
       |class A {
       |    private static int foo(int x, String s) {
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
    """|
       |
       |class A {
       |    private class <<Foo>> {
       |      public int value = 42;
       |    }
       |    public static void main(String args[]){
       |      <<Fo@@o>> f = new <<Foo>>();
       |    }
       |}
       |""".stripMargin,
  )

  checkPrepare(
    "class-reference-prepare",
    """|
       |
       |class A {
       |    private class Foo {
       |      public int value = 42;
       |    }
       |    public static void main(String args[]){
       |      <<Fo@@o>> f = new Foo();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "class-definition",
    """|
       |
       |class A {
       |    private class <<F@@oo>> {
       |      public int value = 42;
       |    }
       |    public static void main(String args[]){
       |      <<Foo>> f = new <<Foo>>();
       |    }
       |}
       |""".stripMargin,
  )

  checkPrepare(
    "class-definition-prepare",
    """|
       |
       |class A {
       |    private class <<F@@oo>> {
       |      public int value = 42;
       |    }
       |    public static void main(String args[]){
       |      Foo f = new Foo();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "new-instance",
    """|class A {
       |    private class <<Foo>> {
       |      public int value = 42;
       |    }
       |    public static void main(String args[]){
       |      <<Foo>> f = new <<Fo@@o>>();
       |    }
       |}
       |""".stripMargin,
  )

  checkPrepare(
    "new-instance-prepare",
    """|class A {
       |    private class Foo {
       |      public int value = 42;
       |    }
       |    public static void main(String args[]){
       |      Foo f = new <<Fo@@o>>();
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "field-access",
    """|class A {
       |    private class Foo {
       |      public int <<value>> = 42;
       |    }
       |    public static void main(String args[]){
       |      Foo f = new Foo();
       |      int x = f.<<val@@ue>>;
       |    }
       |}
       |""".stripMargin,
  )
  checkPrepare(
    "field-access-prepare",
    """|class A {
       |    private class Foo {
       |      public int value = 42;
       |    }
       |    public static void main(String args[]){
       |      Foo f = new Foo();
       |      int x = f.<<val@@ue>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "field-definition",
    """|class A {
       |    private class Foo {
       |      public int <<val@@ue>> = 42;
       |    }
       |    public static void main(String args[]){
       |      Foo f = new Foo();
       |      int x = f.<<value>>;
       |    }
       |}
       |""".stripMargin,
  )

  checkPrepare(
    "field-definition-prepare",
    """|class A {
       |    private class Foo {
       |      public int <<val@@ue>> = 42;
       |    }
       |    public static void main(String args[]){
       |      Foo f = new Foo();
       |      int x = f.value;
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

  checkPrepare(
    "parameter-prepare",
    """|class A {
       |    static void foo(int x) {
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

  checkPrepare(
    "parameter-definition-prepare",
    """|class A {
       |    static void foo(int <<@@x>>) {
       |       System.out.println(x);
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
  checkPrepare(
    "constructor-parameter-prepare",
    """|class Foo {
       |    private int value;
       |    public Foo(int value) {
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

  checkPrepare(
    "constructor-parameter-def-prepare",
    """|class Foo {
       |    private int value;
       |    public Foo(int <<va@@lue>>) {
       |        this.value = value;
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

  checkPrepare(
    "this-reference-prepare",
    """|class Foo {
       |    private int value;
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

  checkPrepare(
    "this-reference-def-prepare",
    """|class Foo {
       |    private int <<va@@lue>>;
       |    public void setValue(int value) {
       |        this.value = value;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "enum-constant",
    """|class A {
       |    private enum Color {
       |      <<RED>>, GREEN, BLUE
       |    }
       |    public static void main(String args[]){
       |      Color c = Color.<<R@@ED>>;
       |    }
       |}
       |""".stripMargin,
  )

  checkPrepare(
    "enum-constant-prepare",
    """|class A {
       |    private enum Color {
       |      RED, GREEN, BLUE
       |    }
       |    public static void main(String args[]){
       |      Color c = Color.<<R@@ED>>;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "enum-constant-definition",
    """|class A {
       |    private enum Color {
       |      <<RE@@D>>, GREEN, BLUE
       |    }
       |    public static void main(String args[]){
       |      Color c = Color.<<RED>>;
       |    }
       |}
       |""".stripMargin,
  )
  checkPrepare(
    "enum-constant-definition-prepare",
    """|class A {
       |    private enum Color {
       |      <<RE@@D>>, GREEN, BLUE
       |    }
       |    public static void main(String args[]){
       |      Color c = Color.RED;
       |    }
       |}
       |""".stripMargin,
  )

  check(
    "method-overload",
    """|class A {
       |    private static void <<foo>>(int x) {}
       |    static void foo(String s) {}
       |
       |    public static void main(String args[]){
       |      <<fo@@o>>(42);
       |    }
       |}
       |""".stripMargin,
  )

  checkPrepare(
    "method-overload-prepare",
    """|class A {
       |    private static void foo(int x) {}
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

  checkPrepare(
    "for-loop-variable-prepare",
    """|class A {
       |    public static void main(String args[]){
       |      for (int i = 0; <<@@i>> < 10; i++) {
       |        System.out.println(i);
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
      val newName = "newNameForRename"
      val base =
        original.replaceAll("@@", "").replaceAll("\\<\\<\\S*\\>\\>", newName)

      val packagePrefix =
        if (automaticPackage) s"package $pkg;\n"
        else ""

      val codeOriginal = packagePrefix + edit
      val expectedCode = packagePrefix + base

      val (code, offset) = params(codeOriginal, "Rename.java")
      val renames = presentationCompiler
        .rename(
          CompilerOffsetParams(
            URI.create("file:/Rename.java"),
            code,
            offset,
            EmptyCancelToken,
          ),
          newName,
        )
        .get()
        .asScala
        .toList

      assertEquals(
        TextEdits.applyEdits(code, renames),
        expectedCode,
      )
    }

  def checkPrepare(
      name: TestOptions,
      input: String,
      filename: String = "Rename.java",
  ): Unit = {
    test(name) {
      val edit = input.replaceAll("(<<|>>)", "")
      val expected =
        input.replaceAll("@@", "")
      val base = input.replaceAll("(<<|>>|@@)", "")
      val (code, offset) = params(edit, filename)
      val range = presentationCompiler
        .prepareRename(
          CompilerOffsetParams(
            URI.create(s"file:/$filename"),
            code,
            offset,
            EmptyCancelToken,
          )
        )
        .get()

      val withRange =
        if (!range.isPresent()) base else replaceInRange(base, range.get())
      assertNoDiff(
        withRange,
        expected,
      )
    }
  }

  private def packageName(name: String): String = {
    name.toLowerCase.split(" ").mkString("_").replaceAll("-", "_")
  }
}
