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
}
