package hover

import tests.pc.BaseJavaHoverSuite

class HoverDocSuite extends BaseJavaHoverSuite {

  override val documentationHoverEnabled = true

  check(
    "doc",
    """
      |class A {
      |    public static void main(String args[]){
      |        java.util.Collections.<Integer>empty@@List();
      |    }
      |}
      |""".stripMargin,
    """|```java
       |public static final java.util.List<T> emptyList()
       |```
       |Returns an empty list (immutable).  This list is serializable.
       |
       |This example illustrates the type-safe way to obtain an empty list:
       |
       |```
       |List<String> s = Collections.emptyList();
       |```
       |""".stripMargin,
  )

  check(
    "doc parent",
    """
      |import java.util.ArrayList;
      |
      |class A {
      |    public static void main(String args[]){
      |        ArrayList<Integer> a = new ArrayList<>();
      |        a.so@@rt((x, y) -> { return x - y; });
      |    }
      |}
      |""".stripMargin,
    if (isJava22)
      """|```java
         |public void sort(java.util.Comparator<? super E> c)
         |```
         |Sorts this list according to the order induced by the specified
         |[Comparator](Comparator) (optional operation).  The sort is *stable*:
         |this method must not reorder equal elements.
         |
         |All elements in this list must be *mutually comparable* using the
         |specified comparator (that is, `c.compare(e1, e2)` must not throw
         |a `ClassCastException` for any elements `e1` and `e2`
         |in the list).
         |
         |If the specified comparator is `null` then all elements in this
         |list must implement the [Comparable](Comparable) interface and the elements'
         |[natural ordering](Comparable) should be used.
         |
         |This list must be modifiable, but need not be resizable.
         |""".stripMargin
    else
      """|```java
         |public void sort(java.util.Comparator<? super E> c)
         |```
         |Sorts this list according to the order induced by the specified
         |[Comparator](Comparator).  The sort is *stable*: this method must not
         |reorder equal elements.
         |
         |All elements in this list must be *mutually comparable* using the
         |specified comparator (that is, `c.compare(e1, e2)` must not throw
         |a `ClassCastException` for any elements `e1` and `e2`
         |in the list).
         |
         |If the specified comparator is `null` then all elements in this
         |list must implement the [Comparable](Comparable) interface and the elements'
         |[natural ordering](Comparable) should be used.
         |
         |This list must be modifiable, but need not be resizable.""".stripMargin,
  )

  check(
    "doc path",
    """
      |import java.nio.file.Paths;
      |
      |class A {
      |    public static void main(String args[]){
      |        Paths.g@@et("");
      |    }
      |}
      |""".stripMargin,
    """|```java
       |public static java.nio.file.Path get(java.lang.String first, java.lang.String[] more)
       |```
       |Converts a path string, or a sequence of strings that when joined form
       |a path string, to a `Path`.
       |""".stripMargin,
  )

  check(
    "list-of",
    """
      |import java.util.List;
      |
      |class A {
      |    public static void main(String args[]){
      |        List.o@@f(1, 2, 3);
      |    }
      |}
      |""".stripMargin,
    """|```java
       |public static java.util.List<E> of(E e1, E e2, E e3)
       |```
       |Returns an unmodifiable list containing three elements.
       |
       |See [Unmodifiable Lists]() for details.
       |""".stripMargin,
  )
}
