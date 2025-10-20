package pc

import tests.pc.BaseJavaCompletionSuite

class CompletionIdentifierSuite extends BaseJavaCompletionSuite {
  check(
    "static value",
    """
      |
      |class A {
      |
      |    public static int BAR = 42;
      |
      |    public static void foo() {
      |         int x = BA@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |BAR
      |""".stripMargin,
  )

  check(
    "method",
    """
      |class A {
      |
      |    public void bar() {}
      |
      |    public static void foo(int bar) {
      |         ba@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |bar
      |bar()
      |""".stripMargin,
  )

  check(
    "argument",
    """
      |class A {
      |
      |    public static void foo(int bar) {
      |         int x = ba@@;
      |    }
      |
      |}
      |""".stripMargin,
    """
      |bar
      |""".stripMargin,
  )

  check(
    "outer-class",
    """package outer;
      |
      |class OneMore {}
      |
      |class Main {
      |
      |    public static void foo(int bar) {
      |         One@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |OneMore
      |""".stripMargin,
  )

  check(
    "import-list",
    """
      |import java.util.List;
      |
      |class A {
      |
      |    public static void foo(int bar) {
      |         Lis@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |List
      |""".stripMargin,
    filterItem = item => !item.getLabel().contains(" - "),
  )

  check(
    "import-util",
    """
      |import java.util.*;
      |
      |class A {
      |
      |    public static void foo(int bar) {
      |         Lis@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |ListResourceBundle
      |ListIterator
      |List
      |TooManyListenersException
      |LinkedList
      |EventListenerProxy
      |EventListener
      |ArrayList
      |AbstractSequentialList
      |AbstractList
      |""".stripMargin,
    filterItem = item => !item.getLabel().contains(" - "),
  )

  check(
    "duplicate names",
    """
      |class A {
      |
      |   public static int duplicate = 42;
      |
      |    public static void duplicate(int bar) {
      |         dup@@
      |    }
      |
      |}
      |""".stripMargin,
    """
      |duplicate
      |duplicate(int bar)
      |""".stripMargin,
  )

  check(
    "inner class",
    """
      |class A {
      |   class B { public int SUPER_FIELD = 42 }
      |   class C extends B {
      |      public void foo() {
      |         SUPER@@
      |      }
      |   }
      |}
      |""".stripMargin,
    """
      |SUPER_FIELD
      |""".stripMargin,
  )

  check(
    "constructor",
    """
      |package constructor;
      |class UserFactoryBuilderCrew {
      |  public static void create() {
      |    new UserFactoryBuilder@@();
      |  }
      |}
      |""".stripMargin,
    """
      |UserFactoryBuilderCrew()
      |""".stripMargin,
  )

  check(
    "import".fail.pending(
      "special case to not insert import if you're aready at an import"
    ),
    """
      |import SimpleFileVisitor@@
      |""".stripMargin,
    """|SimpleFileVisitor - java.nio.file
       |""".stripMargin,
    editedFile = """
                   |import java.nio.file.SimpleFileVisitor;
                   |""".stripMargin,
  )
}
