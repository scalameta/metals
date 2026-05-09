package signatureHelp

import tests.pc.BaseJavaSignatureHelpSuite

class SignatureHelpDocumentationSuite extends BaseJavaSignatureHelpSuite {
  override val documentationHoverEnabled = true

  check(
    "valid-arg",
    """
      |class A {
      |    /**
      |     * Logs a message to the console.
      |     * @param message the message to log
      |     */
      |    public static void log(String message) {}
      |    public void run() {
      |        A.log("@@");
      |    }
      |}
      |""".stripMargin,
    """|=> log(java.lang.String message)
       |       ^^^^^^^^^^^^^^^^^^^^^^^^
       |   Logs a message to the console.
       |
       |**Parameters**
       |- `message`: the message to log
       |""".stripMargin,
  )

  check(
    "empty-arg",
    """
      |class A {
      |    /** Does blah */
      |    public static void qux() {}
      |    public void run() {
      |        A.qux(@@);
      |    }
      |}
      |""".stripMargin,
    """|=> qux()
       |   Does blah
       |""".stripMargin,
  )

  check(
    "empty-arg",
    """
      |class A {
      |    /** Does blah */
      |    public static void qux(String message) {}
      |    public void run() {
      |        A.qux(@@);
      |    }
      |}
      |""".stripMargin,
    """|=> qux(java.lang.String message)
       |       ^^^^^^^^^^^^^^^^^^^^^^^^
       |   Does blah
       |""".stripMargin,
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
    """|=> myMethod(int x, java.lang.String y)
       |                   ^^^^^^^^^^^^^^^^^^
       |   This method does something important.
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
    """| valueOf(java.lang.Object obj)
       |   Returns the string representation of the `Object` argument.
       |   valueOf(char[] data)
       |   Returns the string representation of the `char` array
       |argument. The contents of the character array are copied; subsequent
       |modification of the character array does not affect the returned
       |string.
       |   valueOf(char[] data, int offset, int count)
       |   Returns the string representation of a specific subarray of the
       |`char` array argument.
       |
       |The `offset` argument is the index of the first
       |character of the subarray. The `count` argument
       |specifies the length of the subarray. The contents of the subarray
       |are copied; subsequent modification of the character array does not
       |affect the returned string.
       |   valueOf(boolean b)
       |   Returns the string representation of the `boolean` argument.
       |   valueOf(char c)
       |   Returns the string representation of the `char`
       |argument.
       |=> valueOf(int i)
       |           ^^^^^
       |   Returns the string representation of the `int` argument.
       |
       |The representation is exactly the one returned by the
       |`Integer.toString` method of one argument.
       |   valueOf(long l)
       |   Returns the string representation of the `long` argument.
       |
       |The representation is exactly the one returned by the
       |`Long.toString` method of one argument.
       |   valueOf(float f)
       |   Returns the string representation of the `float` argument.
       |
       |The representation is exactly the one returned by the
       |`Float.toString` method of one argument.
       |   valueOf(double d)
       |   Returns the string representation of the `double` argument.
       |
       |The representation is exactly the one returned by the
       |`Double.toString` method of one argument.
       |""".stripMargin,
  )
}
