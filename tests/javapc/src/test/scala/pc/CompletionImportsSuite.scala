package pc

import tests.pc.BaseJavaCompletionSuite

class CompletionImportsSuite extends BaseJavaCompletionSuite {
  check(
    "package-only",
    """package a;
      |class A {
      |  public void foo() {
      |    return StandardChar@@
      |  }
      |}
      |""".stripMargin,
    """
      |StandardCharsets - java.nio.charset
      |""".stripMargin,
    editedFile = """package a;
                   |
                   |import java.nio.charset.StandardCharsets;
                   |
                   |class A {
                   |  public void foo() {
                   |    return StandardCharsets
                   |  }
                   |}
                   |""".stripMargin,
  )

  // Assert the auto-import is placed before the closest match
  check(
    "import-less",
    """package a;
      |import java.io.File;
      |
      |import java.nio.file.Path;
      |import java.text.Annotation;

      |class A {
      |  public void foo() {
      |    return StandardChar@@
      |  }
      |}
      |""".stripMargin,
    """
      |StandardCharsets - java.nio.charset
      |""".stripMargin,
    editedFile = """|
                    |package a;
                    |import java.io.File;
                    |
                    |import java.nio.charset.StandardCharsets;
                    |import java.nio.file.Path;
                    |import java.text.Annotation;
                    |
                    |class A {
                    |  public void foo() {
                    |    return StandardCharsets
                    |  }
                    |}
                    |""".stripMargin,
  )

  // Assert the auto-import is placed after the closest match
  check(
    "import-greater",
    """package a
      |import java.io.File;
      |import java.nio.channels.AcceptPendingException;
      |
      |import java.text.Annotation;

      |class A {
      |  public void foo() {
      |    return StandardChar@@
      |  }
      |}
      |""".stripMargin,
    """
      |StandardCharsets - java.nio.charset
      |""".stripMargin,
    editedFile = """|
                    |package a
                    |import java.io.File;
                    |import java.nio.channels.AcceptPendingException;
                    |import java.nio.charset.StandardCharsets;
                    |
                    |import java.text.Annotation;
                    |
                    |class A {
                    |  public void foo() {
                    |    return StandardCharsets
                    |  }
                    |}
                    |""".stripMargin,
  )

  check(
    "import-insert",
    """|package com;

       |import java.nio.file.Files;
       |import java.nio.file.SimpleFileVisitor;
       |import java.nio.file.Paths;

       |class A {
       |  public void foo() {
       |    return Path@@
       |  }
       |}
       |""".stripMargin,
    """|Path - java.nio.file
       |""".stripMargin,
    filterItem = _.getLabel() == "Path - java.nio.file",
    editedFile = """|package com;

                    |import java.nio.file.Files;
                    |import java.nio.file.SimpleFileVisitor;
                    |import java.nio.file.Path;
                    |import java.nio.file.Paths;

                    |class A {
                    |  public void foo() {
                    |    return Path
                    |  }
                    |}
                    |""".stripMargin,
  )

  check(
    "no-package",
    """|class A {
       |  public void foo() {
       |    return StandardChar@@
       |  }
       |}
       |""".stripMargin,
    """
      |StandardCharsets - java.nio.charset
      |""".stripMargin,
    editedFile = """|import java.nio.charset.StandardCharsets;
                    |
                    |class A {
                    |  public void foo() {
                    |    return StandardCharsets
                    |  }
                    |}
                    |""".stripMargin,
  )

}
