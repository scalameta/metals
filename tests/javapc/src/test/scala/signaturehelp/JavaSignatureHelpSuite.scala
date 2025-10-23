package tests.signaturehelp

import tests.pc.BaseJavaSignatureHelpSuite

class JavaSignatureHelpSuite extends BaseJavaSignatureHelpSuite {

  check(
    "custom-method",
    """
      |class Test {
      |  void myMethod(int x, String y) {}
      |  void test() {
      |    myMethod(1, @@);
      |  }
      |}
    """.stripMargin,
    """|void myMethod(int x, String y)
       |                     ^^^^^^^^
       |""".stripMargin,
  )

  check(
    "custom-constructor",
    """
      |class Test {
      |  Test(int x, String y) {}
      |  void create() {
      |    new Test(1, @@);
      |  }
      |}
    """.stripMargin,
    """|Test(int x, String y)
       |            ^^^^^^^^
       |""".stripMargin,
  )

  check(
    "custom-constructor-empty",
    """
      |class Test {
      |  Test(int x, String y) {}
      |  void create() {
      |    new Test(@@);
      |  }
      |}
    """.stripMargin,
    """|<init>(int x, String y)
       |       ^^^^^
       |""".stripMargin,
  )

  check(
    "static-method",
    """
      |class Test {
      |  void test() {
      |    Math.max(1, @@);
      |  }
      |}
    """.stripMargin,
    """|int max(int a, int b)
       |               ^^^^^
       |""".stripMargin,
  )

  check(
    "varargs",
    """
      |class Test {
      |  void test() {
      |    String.format("", 1, @@);
      |  }
      |}
    """.stripMargin,
    """|String format(String format, Object[] args)
       |                             ^^^^^^^^^^^^^
       |""".stripMargin,
  )

  check(
    "method-println",
    """
      |class Test {
      |  void test() {
      |    String name = "John";
      |    System.out.println(name@@);
      |  }
      |}
    """.stripMargin,
    """|void println(String x)
       |             ^^^^^^^^
       |""".stripMargin,
  )

  check(
    "method-println-empty",
    """
      |class Test {
      |  void test() {
      |    System.out.println(@@);
      |  }
      |}
    """.stripMargin,
    """|void println()
       |
       |""".stripMargin,
  )

  check(
    "method-second-param",
    """
      |class Test {
      |  void test() {
      |    String.format("", @@);
      |  }
      |}
    """.stripMargin,
    """|String format(String format, Object[] args)
       |                             ^^^^^^^^^^^^^
       |""".stripMargin,
  )

  check(
    "method-first-param",
    """
      |class Test {
      |   public void setVariable(final String name, final Object value) {
      |       // this.variables.put(name, value);
      |  }
      |  void test() {
      |    setVariable(@@);
      |  }
      |}
    """.stripMargin,
    """|void setVariable(String name, Object value)
       |                 ^^^^^^^^^^^
       |""".stripMargin,
  )

  check(
    "constructor",
    """
      |class Test {
      |  void test() {
      |    new String(@@);
      |  }
      |}
    """.stripMargin,
    """|String()
       |""".stripMargin,
  )

  check(
    "constructor-second-param",
    """
      |import java.io.File;
      |class Test {
      |  void test() {
      |    new File("test", @@);
      |  }
      |}
    """.stripMargin,
    """|File(String arg0, String arg1)
       |                  ^^^^^^^^^^^
       |""".stripMargin,
  )

  check(
    "empty-args",
    """
      |class Test {
      |  void myMethod(String x) {}
      |  void myMethod(Integer x) {}
      |  void test() {
      |    myMethod(@@);
      |  }
      |}
    """.stripMargin,
    """|void myMethod(String x)
       |              ^^^^^^^^
       |void myMethod(Integer x)
       |
       |""".stripMargin,
  )

  check(
    "nested",
    """
      |class Test {
      |  void test() {
      |    System.out.println(String.valueOf(@@));
      |  }
      |}
    """.stripMargin,
    """|String valueOf(Object obj)
       |               ^^^^^^^^^^
       |String valueOf(char[] data)
       |String valueOf(char[] data, int offset, int count)
       |String valueOf(boolean b)
       |String valueOf(char c)
       |String valueOf(int i)
       |String valueOf(long l)
       |String valueOf(float f)
       |String valueOf(double d)
       |""".stripMargin,
  )

  check(
    "nested-outer",
    """
      |class Test {
      |  void test() {
      |    System.out.println(Str@@ing.valueOf(12));
      |  }
      |}
    """.stripMargin,
    """|void println(String x)
       |             ^^^^^^^^
       |""".stripMargin,
  )

  check(
    "nested-docs",
    """
      |class Test {
      |  void test() {
      |    System.out.println(String.valueOf(1@@2));
      |  }
      |}
    """.stripMargin,
    """|Returns the string representation of the `int` argument.
       |
       |The representation is exactly the one returned by the
       |`Integer.toString` method of one argument.
       |String valueOf(int i)
       |               ^^^^^
       |""".stripMargin,
    includeDocs = true,
  )

  check(
    "inherited-docs",
    """
      |class BaseClass {
      |  /**
      |   * This method does something important.
      |   * @param x the first parameter
      |   * @param y the second parameter
      |   */
      |  void myMethod(int x, String y) {}
      |}
      |
      |class ChildClass extends BaseClass {
      |  @Override
      |  void myMethod(int x, String y) {}
      |}
      |
      |class Test {
      |  void test() {
      |    ChildClass child = new ChildClass();
      |    child.myMethod(1, @@);
      |  }
      |}
    """.stripMargin,
    """|This method does something important.
       |void myMethod(int x, String y)
       |                     ^^^^^^^^
       |""".stripMargin,
    includeDocs = true,
  )

}
