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

  check(
    "workspace-local-class",
    """
      |package a;
      |
      |class LocalType {}
      |
      |class A {
      |  void foo() {
      |    <<LocalType>> value = null;
      |  }
      |}
      |""".stripMargin,
    """
      |a
      |""".stripMargin,
    filename = "A.java",
  )

  checkEdit(
    "conflicting-import-simple-name",
    """
      |package a;
      |
      |import java.awt.List;
      |
      |class A {
      |  void foo() {
      |    <<List>>.of(1);
      |  }
      |}
      |""".stripMargin,
    """
      |package a;
      |
      |import java.awt.List;
      |
      |class A {
      |  void foo() {
      |    java.util.List.of(1);
      |  }
      |}
      |""".stripMargin,
    filename = "A.java",
  )

  checkEdit(
    "wildcard-already-imported",
    """|package a;
       |
       |import java.util.*;
       |
       |class A {
       |  void foo() {
       |    <<List>>.of(1);
       |  }
       |}
       |""".stripMargin,
    """|package a;
       |
       |import java.util.*;
       |
       |class A {
       |  void foo() {
       |    List.of(1);
       |  }
       |}
       |""".stripMargin,
    filename = "A.java",
  )

  checkEdit(
    "wildcard-multiple-packages",
    """|package a;
       |
       |import java.io.*;
       |import java.util.*;
       |
       |class A {
       |  void foo() {
       |    <<Map>> map;
       |  }
       |}
       |""".stripMargin,
    """|package a;
       |
       |import java.io.*;
       |import java.util.*;
       |
       |class A {
       |  void foo() {
       |    Map map;
       |  }
       |}
       |""".stripMargin,
    filename = "A.java",
  )

  checkEdit(
    "wildcard-with-explicit-import",
    """|package a;
       |
       |import java.util.*;
       |import java.util.concurrent.ConcurrentHashMap;
       |
       |class A {
       |  void foo() {
       |    <<List>> list;
       |  }
       |}
       |""".stripMargin,
    """|package a;
       |
       |import java.util.*;
       |import java.util.concurrent.ConcurrentHashMap;
       |
       |class A {
       |  void foo() {
       |    List list;
       |  }
       |}
       |""".stripMargin,
    filename = "A.java",
  )
}
