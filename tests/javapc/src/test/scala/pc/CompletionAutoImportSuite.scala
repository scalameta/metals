package pc
import tests.pc.BaseJavaCompletionSuite
class CompletionAutoImportSuite extends BaseJavaCompletionSuite {

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
    "inner-class".ignore, // We don't currently index inner classes
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
    filterItem =
      item => Option(item.getDetail).exists(_.startsWith("java.util.Map.Entry")),
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
    filterItem =
      item => Option(item.getDetail).exists(_.startsWith("java.util.List")),
  )

  checkEdit(
    "sorted-import-before",
    """|package a;
       |
       |import java.util.Map;
       |
       |public class A {
       |  public static void main(String[] args) {
       |    Lis@@
       |  }
       |}
       |""".stripMargin,
    """|package a;
       |
       |import java.util.List;
       |import java.util.Map;
       |
       |public class A {
       |  public static void main(String[] args) {
       |    List
       |  }
       |}
       |""".stripMargin,
    filterItem = item => item.getLabel().contains("java.util"),
  )

  checkEdit(
    "sorted-import-after",
    """|package a;
       |
       |import java.io.File;
       |
       |public class A {
       |  public static void main(String[] args) {
       |    Lis@@
       |  }
       |}
       |""".stripMargin,
    """|package a;
       |
       |import java.io.File;
       |import java.util.List;
       |
       |public class A {
       |  public static void main(String[] args) {
       |    List
       |  }
       |}
       |""".stripMargin,
    filterItem = item => item.getLabel().contains("java.util"),
  )

  checkEdit(
    "sorted-import-between",
    """|package a;
       |
       |import java.io.File;
       |import java.util.Map;
       |
       |public class A {
       |  public static void main(String[] args) {
       |    Lis@@
       |  }
       |}
       |""".stripMargin,
    """|package a;
       |
       |import java.io.File;
       |import java.util.List;
       |import java.util.Map;
       |
       |public class A {
       |  public static void main(String[] args) {
       |    List
       |  }
       |}
       |""".stripMargin,
    filterItem = item => item.getLabel().contains("java.util"),
  )

  checkEdit(
    "conflicting-import-simple-name",
    """|package a;
       |
       |import java.awt.List;
       |
       |public class A {
       |  public static void main(String[] args) {
       |    Lis@@.of(1);
       |  }
       |}
       |""".stripMargin,
    """|package a;
       |
       |import java.awt.List;
       |
       |public class A {
       |  public static void main(String[] args) {
       |    java.util.List.of(1);
       |  }
       |}
       |""".stripMargin,
    filterItem = item => item.getLabel().contains("java.util"),
    assertSingleItem = false,
  )

  checkEdit(
    "wildcard-already-imported",
    """|package a;
       |
       |import java.util.*;
       |
       |public class A {
       |  public static void main(String[] args) {
       |    ArrayLis@@
       |  }
       |}
       |""".stripMargin,
    """|package a;
       |
       |import java.util.*;
       |
       |public class A {
       |  public static void main(String[] args) {
       |    ArrayList
       |  }
       |}
       |""".stripMargin,
    itemIndex = 0,
    assertSingleItem = false,
  )
}
