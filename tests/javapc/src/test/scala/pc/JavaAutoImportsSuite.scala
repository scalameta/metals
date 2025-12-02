package pc

import tests.pc.BaseJavaAutoImportsSuite

class JavaAutoImportsSuite extends BaseJavaAutoImportsSuite {

  check(
    "basic",
    """
      |class A {
      |  void foo() {
      |    <<UUID>>.randomUUID();
      |  }
      |}
      |""".stripMargin,
    """
      |java.util
      |""".stripMargin,
  )

  checkEdit(
    "basic-edit",
    """
      |class A {
      |  void foo() {
      |    <<UUID>>.randomUUID();
      |  }
      |}
      |""".stripMargin,
    """
      |import java.util.UUID;
      |
      |class A {
      |  void foo() {
      |    UUID.randomUUID();
      |  }
      |}
      |""".stripMargin,
  )

  checkEdit(
    "already-imported",
    """
      |import java.util.UUID;
      |
      |class A {
      |  void foo() {
      |    <<UUID>>.randomUUID();
      |  }
      |}
      |""".stripMargin,
    """
      |import java.util.UUID;
      |
      |class A {
      |  void foo() {
      |    UUID.randomUUID();
      |  }
      |}
      |""".stripMargin,
  )
}
