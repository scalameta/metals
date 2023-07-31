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
    "outer class",
    """
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
    "import List",
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
  )

  check(
    "import util",
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
}
