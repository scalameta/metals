package tests.pc

import tests.BaseCompletionSuite

object CompletionDocSuite extends BaseCompletionSuite {

  check(
    "java",
    """
      |object A {
      |  "".substrin@@
      |}
    """.stripMargin,
    """|substring(beginIndex: Int): String
       |substring(beginIndex: Int, endIndex: Int): String
       |""".stripMargin
  )

  check(
    "java2",
    """
      |object A {
      |  String.join@@
      |}
    """.stripMargin,
    """|join(delimiter: CharSequence, elements: CharSequence*): String
       |join(delimiter: CharSequence, elements: Iterable[_ <: CharSequence]): String
       |""".stripMargin
  )

  check(
    "java3",
    """
      |import scala.collection.JavaConverters._
      |object A {
      |  new java.util.HashMap[String, Int]().entrySet.asScala.foreach { entry =>
      |    entry.setV@@
      |  }
      |}
    """.stripMargin,
    """|setValue(value: Int): Int
       |""".stripMargin
  )
  check(
    "java4",
    """
      |object A {
      |  java.util.Collections.singletonLis@@
      |}
    """.stripMargin,
    """|singletonList[T](o: T): List[T]
       |""".stripMargin
  )
  check(
    "java5",
    """
      |object A {
      |  java.util.OptionalInt@@
      |}
    """.stripMargin,
    """|> A container object which may or may not contain a {@code int} value.
       |If a value is present, {@code isPresent()} will return {@code true} and
       |{@code getAsInt()} will return the value.
       |
       |<p>Additional methods that depend on the presence or absence of a contained
       |value are provided, such as {@link #orElse(int) orElse()}
       |(return a default value if value not present) and
       |{@link #ifPresent(java.util.function.IntConsumer) ifPresent()} (execute a block
       |of code if the value is present).
       |
       |<p>This is a <a href="../lang/doc-files/ValueBased.html">value-based</a>
       |class; use of identity-sensitive operations (including reference equality
       |({@code ==}), identity hash code, or synchronization) on instances of
       |{@code OptionalInt} may have unpredictable results and should be avoided.
       |OptionalInt java.util
       |""".stripMargin,
    includeDocs = true
  )
  check(
    "scala",
    """
      |object A {
      |  val source: io.Source = ???
      |  source.reportWarn@@
      |}
    """.stripMargin,
    """|reportWarning(pos: Int, msg: String, out: PrintStream = Console.out): Unit
       |""".stripMargin
  )

  check(
    "scala1",
    """
      |object A {
      |  List(1).iterator.sliding@@
      |}
    """.stripMargin,
    """|sliding[B >: Int](size: Int, step: Int = 1): Iterator[Int]#GroupedIterator[B]
       |""".stripMargin
  )

  check(
    "scala2",
    """
      |object A {
      |  println@@
      |}
    """.stripMargin,
    """|> Prints a newline character on the default output.
       |println(): Unit
       |> Prints out an object to the default output, followed by a newline character.
       |println(x: Any): Unit
       |""".stripMargin,
    includeDocs = true
  )
  check(
    "scala3",
    """
      |object A {
      |  Predef@@
      |}
    """.stripMargin,
    """|DeprecatedPredef scala
       |> The `Predef` object provides definitions that are accessible in all Scala
       | compilation units without explicit qualification.
       |Predef scala
       |""".stripMargin,
    includeDocs = true
  )
  check(
    "scala4",
    """
      |object A {
      |  scala.collection.Iterator@@
      |}
    """.stripMargin,
    """|> Explicit instantiation of the `Iterator` trait to reduce class file size in subclasses.
       |AbstractIterator scala.collection
       |> Buffered iterators are iterators which provide a method `head`
       | that inspects the next element without discarding it.
       |BufferedIterator scala.collection
       |> ### class Iterator
       |Iterators are data structures that allow to iterate over a sequence
       | of elements.
       |
       |### object Iterator
       |The `Iterator` object provides various functions for creating specialized iterators.
       |Iterator scala.collection
       |""".stripMargin,
    includeDocs = true
  )
  check(
    "scala5",
    """
      |object A {
      |  scala.concurrent.ExecutionContext.Implicits.global@@
      |}
    """.stripMargin,
    """|> The implicit global `ExecutionContext`.
       |global: ExecutionContext
       |""".stripMargin,
    includeDocs = true,
    compat = Map(
      "2.11" -> """|> The implicit global `ExecutionContext`.
                   |global: ExecutionContextExecutor
                   |""".stripMargin
    )
  )
  check(
    "scala6",
    """
      |object A {
      |  scala.util.Try@@
      |}
    """.stripMargin,
    """|> The `Try` type represents a computation that may either result in an exception, or return a
       |successfully computed value.
       |Try scala.util
       |""".stripMargin,
    includeDocs = true
  )
  check(
    "scala7",
    """
      |object A {
      |  scala.collection.mutable.StringBuilder@@
      |}
    """.stripMargin,
    """|> A builder for mutable sequence of characters.
       |StringBuilder scala.collection.mutable
       |""".stripMargin,
    includeDocs = true
  )
  check(
    "scala8",
    """
      |object A {
      |  scala.Vector@@
      |}
    """.stripMargin,
    """|> ### class Vector
       |Vector is a general-purpose, immutable data structure.
       |
       |### object Vector
       |Companion object to the Vector class
       |Vector scala.collection.immutable
       |""".stripMargin,
    includeDocs = true
  )
  check(
    "scala9",
    """
      |object A {
      |  new Catch@@
      |}
    """.stripMargin,
    """|> A container class for catch/finally logic.
       |scala.util.control.Exception.Catch scala.util.control.Exception
       |""".stripMargin,
    includeDocs = true
  )

  check(
    "scala10",
    """
      |object A {
      |  scala.util.Failure@@
      |}
    """.stripMargin,
    """|Failure scala.util
       |""".stripMargin,
    includeDocs = true
  )
  check(
    "scala11",
    """
      |object A {
      |  new scala.util.DynamicVariable@@
      |}
    """.stripMargin,
    """|> `DynamicVariables` provide a binding mechanism where the current
       | value is found through dynamic scope, but where access to the
       | variable itself is resolved through static scope.
       |DynamicVariable scala.util
       |""".stripMargin,
    includeDocs = true
  )
  check(
    "scala12",
    """
      |object A {
      |  Option(1).isDefined@@
      |}
    """.stripMargin,
    """|> Returns true if the option is an instance of scala.Some, false otherwise.
       |isDefined: Boolean
       |""".stripMargin,
    includeDocs = true
  )
  check(
    "scala13",
    """
      |object A {
      |  scala.collection.immutable.TreeMap.empty[Int, Int].updated@@
      |}
    """.stripMargin,
    // tests both @define and HTML expansion
    """|> A new TreeMap with the entry added is returned,
       | if key is *not* in the TreeMap, otherwise
       | the key is updated with the new entry.
       |
       |
       |**Type Parameters**
       |- `B1`: type of the value of the new binding which is a supertype of `B`
       |
       |**Parameters**
       |- `key`: the key that should be updated
       |- `value`: the value to be associated with `key`
       |
       |**Returns:** a new immutable tree map with the updated binding
       |updated[B1 >: Int](key: Int, value: B1): TreeMap[Int,B1]
       |""".stripMargin,
    includeDocs = true
  )

  check(
    "local",
    """
      |object A {
      |  locally {
      |    val myNumbers = Vector(1)
      |    myNumbers@@
      |  }
      |}
    """.stripMargin,
    """|myNumbers: Vector[Int]
       |""".stripMargin
  )
}
