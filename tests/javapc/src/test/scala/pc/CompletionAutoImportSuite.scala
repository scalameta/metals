package pc
import tests.pc.BaseJavaCompletionSuite
class CompletionAutoImportSuite extends BaseJavaCompletionSuite {
  check(
    "list",
    """
      |
      |interface A {}
      |
      |class B implements A {
      |
      |    public static int foo() {
      |         Li@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |LinkageError
      |List
      |List
      |Line
      |List12
      |ListN
      |ListItr
      |UnsatisfiedLinkError
      |AccessibleAWTList
      |AccessibleAWTListChild
      |SubList
      |AbstractImmutableList
      |""".stripMargin,
  )

  checkEdit(
    "list-edit",
    """
      |
      |interface A {}
      |
      |class B implements A {
      |
      |    public static int foo() {
      |         Lis@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |import java.util.List;
      |
      |
      |interface A {}
      |
      |class B implements A {
      |
      |    public static int foo() {
      |         List
      |    }
      |
      |}
      |""".stripMargin,
    itemIndex = 0,
    assertSingleItem = false,
  )
  checkEdit(
    "inner-class",
    """
      |package a;
      |
      |public class A {
      |  public static void main(String[] args) {
      |    Entr@@
      |  }
      |}
      |""".stripMargin,
    """
      |package a;
      |
      |import java.util.Map.Entry;
      |public class A {
      |  public static void main(String[] args) {
      |    Entry
      |  }
      |}
      |""".stripMargin,
    filterItem = item => item.getDetail.startsWith("java.util.Map.Entry"),
  )

  check(
    "conflict",
    """
      |package a;
      |
      |public class A {
      |  public static void main(String[] args) {
      |    Lis@@
      |  }
      |}
      |""".stripMargin,
    """|List
       |List
       |ListSelectionHandler
       |List12
       |ListN
       |ListItr
       |AccessibleAWTList
       |AccessibleAWTListChild
       |JList
       |AccessibleJList
       |AccessibleJListChild
       |SubList
       |AbstractImmutableList
       |""".stripMargin,
  )

  checkEdit(
    "already-imported",
    """
      |package a;
      |
      |import java.util.List;
      |
      |public class A {
      |  public static void main(String[] args) {
      |    Lis@@
      |  }
      |}
      |""".stripMargin,
    """
      |package a;
      |
      |import java.util.List;
      |
      |public class A {
      |  public static void main(String[] args) {
      |    List
      |  }
      |}
      |""".stripMargin,
    filter = _.equals("List"),
  )
}
