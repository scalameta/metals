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
       |**Type Parameters**
       |- `T`: type of elements, if there were any, in the list
       |
       |**Returns:** an empty immutable list
       |
       |**See**
       |- [#EMPTY_LIST](#EMPTY_LIST)
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
      s"""|```java
         |public void sort(java.util.Comparator<? super E> arg0)
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
         |
         |
         |**Parameters**
         |- `c`: the `Comparator` used to compare list elements.
         |A `null` value indicates that the elements'
         |[natural ordering](Comparable) should be used
         |
         |**Throws**
         |- `ClassCastException`: if the list contains elements that are not
         |*mutually comparable* using the specified comparator
         |- `UnsupportedOperationException`: if the `sort` operation
         |is not supported by this list
         |- `IllegalArgumentException`:${" "}
         |([optional]())
         |if the comparator is found to violate the [Comparator](Comparator)
         |contract
         |""".stripMargin
    else
      """|```java
         |public void sort(java.util.Comparator<? super E> arg0)
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
    s"""|```java
        |public static java.nio.file.Path get(java.lang.String arg0, java.lang.String[] arg1)
        |```
        |Converts a path string, or a sequence of strings that when joined form
        |a path string, to a `Path`.
        |
        |
        |**Parameters**
        |- `first`:${" "}
        |the path string or initial part of the path string
        |- `more`:${" "}
        |additional strings to be joined to form the path string
        |
        |**Returns:** the resulting `Path`
        |
        |**Throws**
        |- `InvalidPathException`:${" "}
        |if the path string cannot be converted to a `Path`
        |
        |**See**
        |- [FileSystem#getPath](FileSystem#getPath)
        |- [Path#of(String,String...)](Path#of(String,String...))""".stripMargin,
  )

  check(
    "list of",
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
       |public static java.util.List<E> of(E arg0, E arg1, E arg2)
       |```
       |Returns an unmodifiable list containing three elements.
       |
       |See [Unmodifiable Lists]() for details.
       |
       |
       |**Type Parameters**
       |- `E`: the `List`'s element type
       |
       |**Parameters**
       |- `e1`: the first element
       |- `e2`: the second element
       |- `e3`: the third element
       |
       |**Returns:** a `List` containing the specified elements
       |
       |**Throws**
       |- `NullPointerException`: if an element is `null`""".stripMargin,
  )
}
