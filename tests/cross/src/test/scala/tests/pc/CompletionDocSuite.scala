package tests.pc

import tests.BaseCompletionSuite

class CompletionDocSuite extends BaseCompletionSuite {
  override def requiresJdkSources: Boolean = true
  override def requiresScalaLibrarySources: Boolean = true

  // the docs can change and fail the tests easily, Scala 3 changes much less often
  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala2Nightlies
  )

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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|join(delimiter: CharSequence, elements: CharSequence*): String
           |join(delimiter: CharSequence, elements: Iterable[? <: CharSequence]): String
           |""".stripMargin
    )
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|singletonList[T](o: T): java.util.List[T]
           |""".stripMargin
    )
  )
  check(
    "java5",
    """
      |object A {
      |  java.util.OptionalInt@@
      |}
    """.stripMargin,
    if (isJava17)
      """|> A container object which may or may not contain an `int` value.
         |If a value is present, `isPresent()` returns `true`. If no
         |value is present, the object is considered *empty* and
         |`isPresent()` returns `false`.
         |
         |Additional methods that depend on the presence or absence of a contained
         |value are provided, such as [orElse()](#orElse(int))
         |(returns a default value if no value is present) and
         |[ifPresent()](#ifPresent(IntConsumer)) (performs an
         |action if a value is present).
         |
         |This is a [value-based]()
         |class; programmers should treat instances that are
         |[equal](#equals(Object)) as interchangeable and should not
         |use instances for synchronization, or unpredictable behavior may
         |occur. For example, in a future release, synchronization may fail.
         |OptionalInt java.util
         |""".stripMargin
    else
      """|> A container object which may or may not contain an `int` value.
         |If a value is present, `isPresent()` returns `true`. If no
         |value is present, the object is considered *empty* and
         |`isPresent()` returns `false`.
         |
         |Additional methods that depend on the presence or absence of a contained
         |value are provided, such as [orElse()](#orElse(int))
         |(returns a default value if no value is present) and
         |[ifPresent()](#ifPresent(IntConsumer)) (performs an
         |action if a value is present).
         |
         |This is a [value-based]()
         |class; use of identity-sensitive operations (including reference equality
         |(`==`), identity hash code, or synchronization) on instances of
         |`OptionalInt` may have unpredictable results and should be avoided.
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|sliding[B >: Int](size: Int, step: Int = 1): List[Int]#iterator.GroupedIterator[B]
           |""".stripMargin
    )
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
       |
       |
       |**Parameters**
       |- `x`: the object to print.
       |println(x: Any): Unit
       |""".stripMargin,
    includeDocs = true
  )

  val commonlyUsedTypesPre2134: String =
    """|###  Commonly Used Types
       | Predef provides type aliases for types which are commonly used, such as
       | the immutable collection types [scala.collection.immutable.Map](scala.collection.immutable.Map),
       | [scala.collection.immutable.Set](scala.collection.immutable.Set), and the [scala.collection.immutable.List](scala.collection.immutable.List)
       | constructors ([scala.collection.immutable.::](scala.collection.immutable.::) and
       | [scala.collection.immutable.Nil](scala.collection.immutable.Nil)).""".stripMargin

  val commonlyUsedTypesPost2134: String =
    """|###  Commonly Used Types
       | Predef provides type aliases for types which are commonly used, such as
       | the immutable collection types [scala.collection.immutable.Map](scala.collection.immutable.Map) and
       | [scala.collection.immutable.Set](scala.collection.immutable.Set).""".stripMargin

  def predefDocString(commonlyUsedTypes: String): String =
    s"""|
        |> The `Predef` object provides definitions that are accessible in all Scala
        | compilation units without explicit qualification.
        |
        |$commonlyUsedTypes
        |
        |###  Console Output
        | For basic console output, `Predef` provides convenience methods [print(x:Any* print](print(x:Any* print) and [println(x:Any* println](println(x:Any* println),
        | which are aliases of the methods in the object [scala.Console](scala.Console).
        |
        |###  Assertions
        | A set of `assert` functions are provided for use as a way to document
        | and dynamically check invariants in code. Invocations of `assert` can be elided
        | at compile time by providing the command line option `-Xdisable-assertions`,
        | which raises `-Xelide-below` above `elidable.ASSERTION`, to the `scalac` command.
        |
        | Variants of `assert` intended for use with static analysis tools are also
        | provided: `assume`, `require` and `ensuring`. `require` and `ensuring` are
        | intended for use as a means of design-by-contract style specification
        | of pre- and post-conditions on functions, with the intention that these
        | specifications could be consumed by a static analysis tool. For instance,
        |
        |```
        |def addNaturals(nats: List[Int]): Int = {
        |  require(nats forall (_ >= 0), "List contains negative numbers")
        |  nats.foldLeft(0)(_ + _)
        |} ensuring(_ >= 0)
        |```
        | The declaration of `addNaturals` states that the list of integers passed should
        | only contain natural numbers (i.e. non-negative), and that the result returned
        | will also be natural. `require` is distinct from `assert` in that if the
        | condition fails, then the caller of the function is to blame rather than a
        | logical error having been made within `addNaturals` itself. `ensuring` is a
        | form of `assert` that declares the guarantee the function is providing with
        | regards to its return value.
        |
        |###  Implicit Conversions
        | A number of commonly applied implicit conversions are also defined here, and
        | in the parent type [scala.LowPriorityImplicits](scala.LowPriorityImplicits). Implicit conversions
        | are provided for the "widening" of numeric values, for instance, converting a
        | Short value to a Long value as required, and to add additional higher-order
        | functions to Array values. These are described in more detail in the documentation of [scala.Array](scala.Array).
        |""".stripMargin.trim

  check(
    "scala3",
    """
      |object A {
      |  Predef@@
      |}
    """.stripMargin,
    s"""
       |${predefDocString(commonlyUsedTypesPre2134)}
       |Predef scala
       |DeprecatedPredef scala
       |""".stripMargin,
    includeDocs = true,
    compat = Map(
      "2.13" ->
        s"""
           |${predefDocString(commonlyUsedTypesPost2134)}
           |Predef scala
           |""".stripMargin,
      "3" ->
        s"""
           |${predefDocString(commonlyUsedTypesPost2134)}
           |Predef scala
           |Predef - scala.runtime.stdLibPatches
           |""".stripMargin
    )
  )

  val iteratorDocs213: String =
    s"""|> Iterators are data structures that allow to iterate over a sequence
        |of elements. They have a `hasNext` method for checking
        |if there is a next element available, and a `next` method
        |which returns the next element and advances the iterator.
        |
        |An iterator is mutable: most operations on it change its state. While it is often used
        |to iterate through the elements of a collection, it can also be used without
        |being backed by any collection (see constructors on the companion object).
        |
        |It is of particular importance to note that, unless stated otherwise, *one should never
        |use an iterator after calling a method on it*. The two most important exceptions
        |are also the sole abstract methods: `next` and `hasNext`.
        |
        |Both these methods can be called any number of times without having to discard the
        |iterator. Note that even `hasNext` may cause mutation -- such as when iterating
        |from an input stream, where it will block until the stream is closed or some
        |input becomes available.
        |
        |Consider this example for safe and unsafe use:
        |
        |```
        |def f[A](it: Iterator[A]) = {
        |  if (it.hasNext) {            // Safe to reuse "it" after "hasNext"
        |    it.next()                  // Safe to reuse "it" after "next"
        |    val remainder = it.drop(2) // it is *not* safe to use "it" again after this line!
        |    remainder.take(2)          // it is *not* safe to use "remainder" after this line!
        |  } else it
        |}
        |```
        |Iterator scala.collection
        |""".stripMargin

  def iteratorAndSpecificIterableFactoryDocs213(
      withLinearSeqIterator: Boolean = true
  ): String = {
    val linearSeqIteratorDocs =
      if (withLinearSeqIterator) {
        "\n" +
          """|> A specialized Iterator for LinearSeqs that is lazy enough for Stream and LazyList. This is accomplished by not
             |evaluating the tail after returning the current head.
             |LinearSeqIterator scala.collection""".stripMargin
      } else {
        ""
      }
    s"""|$iteratorDocs213> Explicit instantiation of the `Iterator` trait to reduce class file size in subclasses.
        |AbstractIterator scala.collection
        |> Buffered iterators are iterators which provide a method `head`
        | that inspects the next element without discarding it.
        |BufferedIterator scala.collection$linearSeqIteratorDocs
        |> Base trait for companion objects of collections that require an implicit `ClassTag`.
        |
        |**Type Parameters**
        |- `CC`: Collection type constructor (e.g. `ArraySeq`)
        |ClassTagIterableFactory scala.collection
        |> Base trait for companion objects of collections that require an implicit evidence.
        |
        |**Type Parameters**
        |- `CC`: Collection type constructor (e.g. `ArraySeq`)
        |- `Ev`: Unary type constructor for the implicit evidence required for an element type
        |           (typically `Ordering` or `ClassTag`)
        |EvidenceIterableFactory scala.collection
        |> This trait provides default implementations for the factory methods `fromSpecific` and
        |`newSpecificBuilder` that need to be refined when implementing a collection type that refines
        |the `CC` and `C` type parameters. It is used for collections that have an additional constraint,
        |expressed by the `evidenceIterableFactory` method.
        |
        |The default implementations in this trait can be used in the common case when `CC[A]` is the
        |same as `C`.
        |EvidenceIterableFactoryDefaults scala.collection
        |> Base trait for companion objects of unconstrained collection types that may require
        |multiple traversals of a source collection to build a target collection `CC`.
        |
        |
        |**Type Parameters**
        |- `CC`: Collection type constructor (e.g. `List`)
        |IterableFactory scala.collection
        |> This trait provides default implementations for the factory methods `fromSpecific` and
        |`newSpecificBuilder` that need to be refined when implementing a collection type that refines
        |the `CC` and `C` type parameters.
        |
        |The default implementations in this trait can be used in the common case when `CC[A]` is the
        |same as `C`.
        |IterableFactoryDefaults scala.collection
        |> Base trait for companion objects of collections that require an implicit `Ordering`.
        |
        |**Type Parameters**
        |- `CC`: Collection type constructor (e.g. `SortedSet`)
        |SortedIterableFactory scala.collection
        |> **Type Parameters**
        |- `A`: Type of elements (e.g. `Int`, `Boolean`, etc.)
        |- `C`: Type of collection (e.g. `List[Int]`, `TreeMap[Int, String]`, etc.)
        |SpecificIterableFactory scala.collection
        |""".stripMargin
  }

  check(
    "scala4",
    """
      |object A {
      |  import scala.collection.Iterator@@
      |}
    """.stripMargin,
    """|
       |> ### class Iterator
       |Iterators are data structures that allow to iterate over a sequence
       | of elements. They have a `hasNext` method for checking
       | if there is a next element available, and a `next` method
       | which returns the next element and advances the iterator.
       |
       | An iterator is mutable: most operations on it change its state. While it is often used
       | to iterate through the elements of a collection, it can also be used without
       | being backed by any collection (see constructors on the companion object).
       |
       | It is of particular importance to note that, unless stated otherwise, *one should never
       | use an iterator after calling a method on it*. The two most important exceptions
       | are also the sole abstract methods: `next` and `hasNext`.
       |
       | Both these methods can be called any number of times without having to discard the
       | iterator. Note that even `hasNext` may cause mutation -- such as when iterating
       | from an input stream, where it will block until the stream is closed or some
       | input becomes available.
       |
       | Consider this example for safe and unsafe use:
       |
       |```
       |def f[A](it: Iterator[A]) = {
       |  if (it.hasNext) {            // Safe to reuse "it" after "hasNext"
       |    it.next                    // Safe to reuse "it" after "next"
       |    val remainder = it.drop(2) // it is *not* safe to use "it" again after this line!
       |    remainder.take(2)          // it is *not* safe to use "remainder" after this line!
       |  } else it
       |}
       |```
       |
       |### object Iterator
       |The `Iterator` object provides various functions for creating specialized iterators.
       |Iterator scala.collection
       |> Explicit instantiation of the `Iterator` trait to reduce class file size in subclasses.
       |AbstractIterator scala.collection
       |> Buffered iterators are iterators which provide a method `head`
       | that inspects the next element without discarding it.
       |BufferedIterator scala.collection
       |""".stripMargin,
    includeDocs = true,
    compat = Map(
      // LinearSeqIterator should actually not be added since it's private and it's fixed in 2.13.5
      "2.13" -> iteratorAndSpecificIterableFactoryDocs213(
        withLinearSeqIterator = false
      ),
      "3" -> iteratorDocs213
    )
  )

  def executionDocstringPre2134: String =
    """|> The implicit global `ExecutionContext`. Import `global` when you want to provide the global
       |`ExecutionContext` implicitly.
       |
       |The default `ExecutionContext` implementation is backed by a work-stealing thread pool. By default,
       |the thread pool uses a target number of worker threads equal to the number of
       |[available processors](https://docs.oracle.com/javase/8/docs/api/java/lang/Runtime.html#availableProcessors--).
       |""".stripMargin.trim

  def executionDocstringPost2134: String =
    """|> An accessor that can be used to import the global `ExecutionContext` into the implicit scope,
       |see [ExecutionContext.global](ExecutionContext.global).
       |""".stripMargin.trim

  check(
    "scala5",
    """
      |object A {
      |  scala.concurrent.ExecutionContext.Implicits.global@@
      |}
    """.stripMargin,
    s"""|$executionDocstringPost2134
        |global: ExecutionContext
        |""".stripMargin,
    includeDocs = true,
    compat = Map(
      "2.12" -> s"""|$executionDocstringPre2134
                    |global: ExecutionContext
                    |""".stripMargin,
      "2.11" -> s"""|$executionDocstringPre2134
                    |global: ExecutionContextExecutor
                    |""".stripMargin
    )
  )

  val baseTryDocs: String =
    """|> The `Try` type represents a computation that may either result in an exception, or return a
       |successfully computed value. It's similar to, but semantically different from the [scala.util.Either](scala.util.Either) type.
       |
       |Instances of `Try[T]`, are either an instance of [scala.util.Success](scala.util.Success)[T] or [scala.util.Failure](scala.util.Failure)[T].
       |
       |For example, `Try` can be used to perform division on a user-defined input, without the need to do explicit
       |exception-handling in all of the places that an exception might occur.
       |
       |Example:
       |
       |```
       |import scala.io.StdIn
       |import scala.util.{Try, Success, Failure}
       |
       |def divide: Try[Int] = {
       |  val dividend = Try(StdIn.readLine("Enter an Int that you'd like to divide:\n").toInt)
       |  val divisor = Try(StdIn.readLine("Enter an Int that you'd like to divide by:\n").toInt)
       |  val problem = dividend.flatMap(x => divisor.map(y => x/y))
       |  problem match {
       |    case Success(v) =>
       |      println("Result of " + dividend.get + "/"+ divisor.get +" is: " + v)
       |      Success(v)
       |    case Failure(e) =>
       |      println("You must've divided by zero or entered something that's not an Int. Try again!")
       |      println("Info from the exception: " + e.getMessage)
       |      divide
       |  }
       |}
       |```
       |An important property of `Try` shown in the above example is its ability to *pipeline*, or chain, operations,
       |catching exceptions along the way. The `flatMap` and `map` combinators in the above example each essentially
       |pass off either their successfully completed value, wrapped in the `Success` type for it to be further operated
       |upon by the next combinator in the chain, or the exception wrapped in the `Failure` type usually to be simply
       |passed on down the chain. Combinators such as `recover` and `recoverWith` are designed to provide some type of
       |default behavior in the case of failure.
       |
       |*Note*: only non-fatal exceptions are caught by the combinators on `Try` (see [scala.util.control.NonFatal](scala.util.control.NonFatal)).
       |Serious system errors, on the other hand, will be thrown.
       |
       |*Note:*: all Try combinators will catch exceptions and return failure unless otherwise specified in the documentation.
       |
       |`Try` comes to the Scala standard library after years of use as an integral part of Twitter's stack.
       |Try scala.util
       |""".stripMargin

  val baseScala21313Docs: String =
    """|> The `Try` type represents a computation that may fail during evaluation by raising an exception.
       |It holds either a successfully computed value or the exception that was thrown.
       |This is similar to the [scala.util.Either](scala.util.Either) type, but with different semantics.
       |
       |Instances of `Try[T]` are an instance of either [scala.util.Success](scala.util.Success)[T] or [scala.util.Failure](scala.util.Failure)[T].
       |
       |For example, consider a computation that performs division on user-defined input.
       |`Try` can reduce or eliminate the need for explicit exception handling in all of the places
       |where an exception might be thrown.
       |
       |Example:
       |
       |```
       |import scala.io.StdIn
       |import scala.util.{Try, Success, Failure}
       |
       |def divide: Try[Int] = {
       |  val dividend = Try(StdIn.readLine("Enter an Int that you'd like to divide:\n").toInt)
       |  val divisor = Try(StdIn.readLine("Enter an Int that you'd like to divide by:\n").toInt)
       |  val problem = dividend.flatMap(x => divisor.map(y => x/y))
       |  problem match {
       |    case Success(v) =>
       |      println("Result of " + dividend.get + "/"+ divisor.get +" is: " + v)
       |      Success(v)
       |    case Failure(e) =>
       |      println("You must've divided by zero or entered something that's not an Int. Try again!")
       |      println("Info from the exception: " + e.getMessage)
       |      divide
       |  }
       |}
       |```
       |An important property of `Try` shown in the above example is its ability to *pipeline*, or chain, operations,
       |catching exceptions along the way. The `flatMap` and `map` combinators in the above example each essentially
       |pass off either their successfully completed value, wrapped in the `Success` type for it to be further operated
       |upon by the next combinator in the chain, or the exception wrapped in the `Failure` type usually to be simply
       |passed on down the chain. Combinators such as `recover` and `recoverWith` are designed to provide some type of
       |default behavior in the case of failure.
       |
       |*Note*: only non-fatal exceptions are caught by the combinators on `Try` (see [scala.util.control.NonFatal](scala.util.control.NonFatal)).
       |Serious system errors, on the other hand, will be thrown.
       |
       |*Note:*: all Try combinators will catch exceptions and return failure unless otherwise specified in the documentation.
       |Try scala.util""".stripMargin

  check(
    "scala6",
    """
      |object A {
      |  scala.util.Try@@
      |}
    """.stripMargin,
    baseTryDocs,
    includeDocs = true,
    compat = Map(
      "3" ->
        s"""|$baseScala21313Docs
            |> Constructs a `Try` using the by-name parameter as a result value.
            |
            | The evaluation of `r` is attempted once.
            |
            | Any non-fatal exception is caught and results in a `Failure`
            | that holds the exception.
            |
            |
            |**Parameters**
            |- `r`: the result value to compute
            |
            |**Returns:** the result of evaluating the value, as a `Success` or `Failure`
            |Try[T](r: => T): Try[T]""".stripMargin,
      "2.13.13" -> baseScala21313Docs,
      "2.13.14" -> baseScala21313Docs,
      "2.13.15" -> baseScala21313Docs
    )
  )

  val scala213Docs: String =
    """|> A builder of `String` which is also a mutable sequence of characters.
       |
       | This class provides an API mostly compatible with `java.lang.StringBuilder`,
       | except where there are conflicts with the Scala collections API, such as the `reverse` method:
       | [reverse](reverse) produces a new `StringBuilder`, and [reverseInPlace](reverseInPlace) mutates this builder.
       |
       | Mutating operations return either `this.type`, i.e., the current builder, or `Unit`.
       |
       | Other methods extract data or information from the builder without mutating it.
       |
       | The distinction is also reflected in naming conventions used by collections,
       | such as `append`, which mutates, and `appended`, which does not, or `reverse`,
       | which does not mutate, and `reverseInPlace`, which does.
       |
       | The `String` result may be obtained using either `result()` or `toString`.
       |
       | $multipleResults
       |
       |
       |**See**
       |- ["Scala's Collection Library overview"](https://docs.scala-lang.org/overviews/collections-2.13/concrete-mutable-collection-classes.html#stringbuilders)
       | section on `StringBuilders` for more information.""".stripMargin

  check(
    "scala7",
    """
      |object A {
      |  scala.collection.mutable.StringBuilder@@
      |}
    """.stripMargin,
    """|> A builder for mutable sequence of characters.  This class provides an API
       | mostly compatible with `java.lang.StringBuilder`, except where there are
       | conflicts with the Scala collections API (such as the `reverse` method.)
       |
       |
       |**See**
       |- ["Scala's Collection Library overview"](http://docs.scala-lang.org/overviews/collections/concrete-mutable-collection-classes.html#stringbuilders)
       | section on `StringBuilders` for more information.
       |StringBuilder scala.collection.mutable
       |""".stripMargin,
    includeDocs = true,
    compat = Map(
      ">=2.13.0" ->
        """|> A builder for mutable sequence of characters.  This class provides an API
           |mostly compatible with `java.lang.StringBuilder`, except where there are
           |conflicts with the Scala collections API (such as the `reverse` method.)
           |
           |$multipleResults
           |
           |
           |**See**
           |- ["Scala's Collection Library overview"](https://docs.scala-lang.org/overviews/collections/concrete-mutable-collection-classes.html#stringbuilders)
           |section on `StringBuilders` for more information.
           |StringBuilder scala.collection.mutable
           |""".stripMargin,
      ">=2.13.11" ->
        s"""|$scala213Docs
            |StringBuilder scala.collection.mutable
            |""".stripMargin,
      ">=3.0.0" ->
        List(
          "StringBuilder scala.collection.mutable",
          "StringBuilder(): StringBuilder",
          "StringBuilder(str: String): StringBuilder",
          "StringBuilder(underlying: StringBuilder): StringBuilder",
          "StringBuilder(capacity: Int): StringBuilder",
          "StringBuilder(initCapacity: Int, initValue: String): StringBuilder"
        ).map(s => scala213Docs + "\n" + s).mkString("\n")
    )
  )

  val vectorDocs213: String =
    """|> ### class Vector
       |Vector is a general-purpose, immutable data structure.  It provides random access and updates
       |in O(log n) time, as well as very fast append/prepend/tail/init (amortized O(1), worst case O(log n)).
       |Because vectors strike a good balance between fast random selections and fast random functional updates,
       |they are currently the default implementation of immutable indexed sequences.
       |
       |Vectors are implemented by radix-balanced finger trees of width 32. There is a separate subclass
       |for each level (0 to 6, with 0 being the empty vector and 6 a tree with a maximum width of 64 at the
       |top level).
       |
       |Tree balancing:
       |- Only the first dimension of an array may have a size < WIDTH
       |- In a `data` (central) array the first dimension may be up to WIDTH-2 long, in `prefix1` and `suffix1` up
       |  to WIDTH, and in other `prefix` and `suffix` arrays up to WIDTH-1
       |- `prefix1` and `suffix1` are never empty
       |- Balancing does not cross the main data array (i.e. prepending never touches the suffix and appending never touches
       |  the prefix). The level is increased/decreased when the affected side plus main data is already full/empty
       |- All arrays are left-aligned and truncated
       |
       |In addition to the data slices (`prefix1`, `prefix2`, ..., `dataN`, ..., `suffix2`, `suffix1`) we store a running
       |count of elements after each prefix for more efficient indexing without having to dereference all prefix arrays.
       |
       |### object Vector
       |$factoryInfo
       |Vector scala.collection.immutable
       |""".stripMargin

  check(
    "scala8",
    """
      |object A {
      |  scala.Vector@@
      |}
    """.stripMargin,
    """|> ### class Vector
       |Vector is a general-purpose, immutable data structure.  It provides random access and updates
       | in effectively constant time, as well as very fast append and prepend.  Because vectors strike
       | a good balance between fast random selections and fast random functional updates, they are
       | currently the default implementation of immutable indexed sequences.  It is backed by a little
       | endian bit-mapped vector trie with a branching factor of 32.  Locality is very good, but not
       | contiguous, which is good for very large sequences.
       |
       | $usesMutableState
       |
       |
       |**Type Parameters**
       |- `A`: the element type
       |
       |**See**
       |- ["Scala's Collection Library overview"](http://docs.scala-lang.org/overviews/collections/concrete-immutable-collection-classes.html#vectors)
       | section on `Vectors` for more information.
       |
       |### object Vector
       |Companion object to the Vector class
       |Vector scala.collection.immutable
       |""".stripMargin,
    includeDocs = true,
    compat = Map(
      "2.13" -> vectorDocs213,
      "3" -> vectorDocs213
    )
  )

  check(
    "scala9",
    """
      |object A {
      |  new Catch@@
      |}
    """.stripMargin,
    """|> A container class for catch/finally logic.
       |
       | Pass a different value for rethrow if you want to probably
       | unwisely allow catching control exceptions and other throwables
       | which the rest of the world may expect to get through.
       |
       |**Type Parameters**
       |- `T`: result type of bodies used in try and catch blocks
       |
       |**Parameters**
       |- `pf`: Partial function used when applying catch logic to determine result value
       |- `fin`: Finally logic which if defined will be invoked after catch logic
       |- `rethrow`: Predicate on throwables determining when to rethrow a caught [Throwable](Throwable)
       |Catch - scala.util.control.Exception
       |""".stripMargin,
    includeDocs = true,
    compat = Map(
      "3" ->
        """|> A container class for catch/finally logic.
           |
           | Pass a different value for rethrow if you want to probably
           | unwisely allow catching control exceptions and other throwables
           | which the rest of the world may expect to get through.
           |
           |**Type Parameters**
           |- `T`: result type of bodies used in try and catch blocks
           |
           |**Parameters**
           |- `pf`: Partial function used when applying catch logic to determine result value
           |- `fin`: Finally logic which if defined will be invoked after catch logic
           |- `rethrow`: Predicate on throwables determining when to rethrow a caught [Throwable](Throwable)
           |Catch[T] - scala.util.control.Exception
           |""".stripMargin
    ),
    topLines = Some(1) // for Scala3, result contains `Catch[$0]` and `Catch`
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
    includeDocs = true,
    compat = Map(
      "3" ->
        """|Failure scala.util
           |Failure[T](exception: Throwable): Failure[T]
           |""".stripMargin
    )
  )

  // New completions not yet implemented for Scala 3
  check(
    "scala11".tag(IgnoreScala3),
    """
      |object A {
      |  new scala.util.DynamicVariable@@
      |}
    """.stripMargin,
    """|> `DynamicVariables` provide a binding mechanism where the current
       | value is found through dynamic scope, but where access to the
       | variable itself is resolved through static scope.
       |
       | The current value can be retrieved with the value method. New values
       | should be pushed using the `withValue` method. Values pushed via
       | `withValue` only stay valid while the `withValue`'s second argument, a
       | parameterless closure, executes. When the second argument finishes,
       | the variable reverts to the previous value.
       |
       |```
       |someDynamicVariable.withValue(newValue) {
       |  // ... code called in here that calls value ...
       |  // ... will be given back the newValue ...
       |}
       |```
       | Each thread gets its own stack of bindings.  When a
       | new thread is created, the `DynamicVariable` gets a copy
       | of the stack of bindings from the parent thread, and
       | from then on the bindings for the new thread
       | are independent of those for the original thread.
       |DynamicVariable scala.util
       |""".stripMargin,
    includeDocs = true
  )

  val isDefinedLatestDocs: String =
    """|> Returns true if the option is an instance of [scala.Some](scala.Some), false otherwise.
       |
       |This is equivalent to:
       |
       |```
       |option match {
       |  case Some(_) => true
       |  case None    => false
       |}
       |```
       |isDefined: Boolean
       |""".stripMargin

  val isDefinedOlderDocs: String =
    """|> Returns true if the option is an instance of [scala.Some](scala.Some), false otherwise.
       |isDefined: Boolean
       |""".stripMargin

  check(
    "scala12",
    """
      |object A {
      |  Option(1).isDefined@@
      |}
    """.stripMargin,
    isDefinedLatestDocs,
    includeDocs = true,
    compat = Map(
      "2.13" -> isDefinedLatestDocs
    )
  )
  check(
    "scala13",
    """
      |object A {
      |  scala.collection.immutable.TreeMap.empty[Int, Int].insert@@
      |}
    """.stripMargin,
    // tests both @define and HTML expansion
    """|> A new TreeMap with the entry added is returned,
       | assuming that key is *not* in the TreeMap.
       |
       |
       |**Type Parameters**
       |- `B1`: type of the values of the new bindings, a supertype of `B`
       |
       |**Parameters**
       |- `key`: the key to be inserted
       |- `value`: the value to be associated with `key`
       |
       |**Returns:** a new immutable tree map with the inserted binding, if it wasn't present in the map
       |insert[B1 >: Int](key: Int, value: B1): TreeMap[Int,B1]
       |""".stripMargin,
    includeDocs = true,
    compat = Map(
      "2.13" ->
        """|> A new TreeMap with the entry added is returned,
           | assuming that key is *not* in the TreeMap.
           |
           |
           |**Type Parameters**
           |- `V1`: type of the values of the new bindings, a supertype of `V`
           |
           |**Parameters**
           |- `key`: the key to be inserted
           |- `value`: the value to be associated with `key`
           |
           |**Returns:** a new immutable tree map with the inserted binding, if it wasn't present in the map
           |insert[V1 >: Int](key: Int, value: V1): TreeMap[Int,V1]
           |""".stripMargin,
      "3" -> """|> A new TreeMap with the entry added is returned,
                | assuming that key is *not* in the TreeMap.
                |
                |
                |**Type Parameters**
                |- `V1`: type of the values of the new bindings, a supertype of `V`
                |
                |**Parameters**
                |- `key`: the key to be inserted
                |- `value`: the value to be associated with `key`
                |
                |**Returns:** a new immutable tree map with the inserted binding, if it wasn't present in the map
                |insert[V1 >: Int](key: Int, value: V1): TreeMap[Int, V1]
                |""".stripMargin
    )
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
