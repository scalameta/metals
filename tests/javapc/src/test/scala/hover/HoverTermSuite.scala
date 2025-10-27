package hover

import tests.pc.BaseJavaHoverSuite

class HoverTermSuite extends BaseJavaHoverSuite {
  check(
    "new-class",
    """
      |class A {
      |
      |    private A(String str, int num) {};
      |
      |    public static void main(String args[]){
      |        new @@A("str", 42);
      |    }
      |}
      |""".stripMargin,
    """
      |private A(java.lang.String str, int num)
      |""".stripMargin.javaHover,
  )

  check(
    "new-t-class",
    """
      |class A<T> {
      |
      |    private A(String str, T t) {};
      |
      |    public static void main(String args[]){
      |        new <Integer>@@A("str", 42);
      |    }
      |}
      |""".stripMargin,
    """
      |private A(java.lang.String str, T t)
      |""".stripMargin.javaHover,
  )

  check(
    "import1",
    """
      |import java.n@@io.file.*;
      |""".stripMargin,
    """|```java
       |package java.nio
       |```
       |""".stripMargin,
  )

  check(
    "import2",
    """
      |import jav@@a.nio.file.*;
      |""".stripMargin,
    """|```java
       |package java
       |```
       |""".stripMargin,
  )

  check(
    "import3",
    """
      |import java.nio.fil@@e.*;
      |""".stripMargin,
    """|```java
       |package java.nio.file
       |```
       |""".stripMargin,
  )

  check(
    "widen",
    """
      |class A {
      |    public static void main(String args[]){
      |        System.out.println(java.nio.file.FileVisitResult.CONTIN@@UE);
      |    }
      |}
      |""".stripMargin,
    """public static final java.nio.file.FileVisitResult CONTINUE""".javaHover,
  )

  check(
    "list",
    """
      |import java.util.List;
      |
      |class A {
      |    public static void main(String args[]){
      |        List<Integer> list = Li@@st.of(1, 2, 3);
      |    }
      |}
      |""".stripMargin, {
      if (isJava21)
        "public interface java.util.List<E> extends java.util.SequencedCollection<E>".javaHover
      else
        """public interface java.util.List<E> extends java.util.Collection<E>""".javaHover
    },
  )
}
