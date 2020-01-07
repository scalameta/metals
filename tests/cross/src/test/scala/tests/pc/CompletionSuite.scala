package tests.pc

import tests.BaseCompletionSuite
import funsuite.BeforeAll

object CompletionSuite extends BaseCompletionSuite {

  override def beforeAll(context: BeforeAll): Unit = {
    indexJDK()
  }

  check(
    "scope",
    """
      |object A {
      |  Lis@@
      |}""".stripMargin,
    """|List scala.collection.immutable
       |List - java.awt
       |List - java.util
       |ListMap - scala.collection.immutable
       |ListMap - scala.collection.mutable
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|List scala.collection.immutable
           |LazyList scala.collection.immutable
           |List - java.awt
           |List - java.util
           |ListMap - scala.collection.immutable
           |""".stripMargin
    ),
    topLines = Some(5)
  )

  check(
    "member",
    """
      |object A {
      |  List.emp@@
      |}""".stripMargin,
    """
      |empty[A]: List[A]
      |""".stripMargin
  )

  check(
    "extension",
    """
      |object A {
      |  "".stripSu@@
      |}""".stripMargin,
    """|stripSuffix(suffix: String): String
       |""".stripMargin
  )

  check(
    "tparam",
    """
      |class Foo[A] {
      |  def identity[B >: A](a: B): B = a
      |}
      |object Foo {
      |  new Foo[Int].ident@@
      |}""".stripMargin,
    """|identity[B >: Int](a: B): B
       |""".stripMargin
  )

  check(
    "tparam1",
    """
      |class Foo[A] {
      |  def identity(a: A): A = a
      |}
      |object Foo {
      |  new Foo[Int].ident@@
      |}""".stripMargin,
    """|identity(a: Int): Int
       |""".stripMargin
  )
  List(1)

  check(
    "tparam2",
    """
      |object A {
      |  Map.empty[Int, String].getOrEl@@
      |}
      |""".stripMargin,
    """|getOrElse[V1 >: String](key: Int, default: => V1): V1
       |""".stripMargin,
    compat = Map(
      "2.11" -> "getOrElse[B1 >: String](key: Int, default: => B1): B1"
    )
  )

  check(
    "cursor",
    """
      |object A {
      |  val default = 1
      |  def@@
      |}""".stripMargin,
    """|default: Int
       |def
       |override def equals(obj: Any): Boolean
       |override def hashCode(): Int
       |override def toString(): String
       |override def clone(): Object
       |override def finalize(): Unit
       |""".stripMargin
  )

  check(
    "dot",
    """
      |object A {
      |  List.@@
      |}""".stripMargin,
    """|apply[A](xs: A*): List[A]
       |canBuildFrom[A]: CanBuildFrom[immutable.List.Coll,A,List[A]]
       |empty[A]: List[A]
       |newBuilder[A]: Builder[A,List[A]]
       |GenericCanBuildFrom scala.collection.generic.GenTraversableFactory
       |ReusableCBF: immutable.List.GenericCanBuildFrom[Nothing]
       |concat[A](xss: Traversable[A]*): List[A]
       |fill[A](n: Int)(elem: => A): List[A]
       |fill[A](n1: Int, n2: Int)(elem: => A): List[List[A]]
       |fill[A](n1: Int, n2: Int, n3: Int)(elem: => A): List[List[List[A]]]
       |fill[A](n1: Int, n2: Int, n3: Int, n4: Int)(elem: => A): List[List[List[List[A]]]]
       |fill[A](n1: Int, n2: Int, n3: Int, n4: Int, n5: Int)(elem: => A): List[List[List[List[List[A]]]]]
       |iterate[A](start: A, len: Int)(f: A => A): List[A]
       |range[T: Integral](start: T, end: T): List[T]
       |range[T: Integral](start: T, end: T, step: T): List[T]
       |tabulate[A](n: Int)(f: Int => A): List[A]
       |tabulate[A](n1: Int, n2: Int)(f: (Int, Int) => A): List[List[A]]
       |tabulate[A](n1: Int, n2: Int, n3: Int)(f: (Int, Int, Int) => A): List[List[List[A]]]
       |tabulate[A](n1: Int, n2: Int, n3: Int, n4: Int)(f: (Int, Int, Int, Int) => A): List[List[List[List[A]]]]
       |tabulate[A](n1: Int, n2: Int, n3: Int, n4: Int, n5: Int)(f: (Int, Int, Int, Int, Int) => A): List[List[List[List[List[A]]]]]
       |unapplySeq[A](x: List[A]): Some[List[A]]
       |+(other: String): String
       |formatted(fmtstr: String): String
       |asInstanceOf[T0]: T0
       |equals(obj: Any): Boolean
       |getClass(): Class[_]
       |hashCode(): Int
       |isInstanceOf[T0]: Boolean
       |synchronized[T0](x$1: T0): T0
       |toString(): String
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|empty[A]: List[A]
           |from[B](coll: IterableOnce[B]): List[B]
           |newBuilder[A]: Builder[A,List[A]]
           |apply[A](elems: A*): List[A]
           |concat[A](xss: Iterable[A]*): List[A]
           |fill[A](n1: Int, n2: Int)(elem: => A): List[List[A]]
           |fill[A](n1: Int, n2: Int, n3: Int)(elem: => A): List[List[List[A]]]
           |fill[A](n1: Int, n2: Int, n3: Int, n4: Int)(elem: => A): List[List[List[List[A]]]]
           |fill[A](n1: Int, n2: Int, n3: Int, n4: Int, n5: Int)(elem: => A): List[List[List[List[List[A]]]]]
           |fill[A](n: Int)(elem: => A): List[A]
           |iterableFactory[A]: Factory[A,List[A]]
           |iterate[A](start: A, len: Int)(f: A => A): List[A]
           |range[A: Integral](start: A, end: A): List[A]
           |range[A: Integral](start: A, end: A, step: A): List[A]
           |tabulate[A](n1: Int, n2: Int)(f: (Int, Int) => A): List[List[A]]
           |tabulate[A](n1: Int, n2: Int, n3: Int)(f: (Int, Int, Int) => A): List[List[List[A]]]
           |tabulate[A](n1: Int, n2: Int, n3: Int, n4: Int)(f: (Int, Int, Int, Int) => A): List[List[List[List[A]]]]
           |tabulate[A](n1: Int, n2: Int, n3: Int, n4: Int, n5: Int)(f: (Int, Int, Int, Int, Int) => A): List[List[List[List[List[A]]]]]
           |tabulate[A](n: Int)(f: Int => A): List[A]
           |unapplySeq[A](x: List[A]): SeqFactory.UnapplySeqWrapper[A]
           |unfold[A, S](init: S)(f: S => Option[(A, S)]): List[A]
           |+(other: String): String
           |formatted(fmtstr: String): String
           |fromSpecific(from: Any)(it: IterableOnce[A]): List[A]
           |fromSpecific(it: IterableOnce[A]): List[A]
           |newBuilder(from: Any): Builder[A,List[A]]
           |newBuilder: Builder[A,List[A]]
           |toFactory(from: Any): Factory[A,List[A]]
           |apply(from: Any): Builder[A,List[A]]
           |asInstanceOf[T0]: T0
           |equals(obj: Object): Boolean
           |getClass(): Class[_]
           |hashCode(): Int
           |isInstanceOf[T0]: Boolean
           |synchronized[T0](x$1: T0): T0
           |toString(): String
           |""".stripMargin,
      "2.11" ->
        """|apply[A](xs: A*): List[A]
           |canBuildFrom[A]: CanBuildFrom[List.Coll,A,List[A]]
           |empty[A]: List[A]
           |newBuilder[A]: Builder[A,List[A]]
           |GenericCanBuildFrom scala.collection.generic.GenTraversableFactory
           |ReusableCBF: List.GenericCanBuildFrom[Nothing]
           |concat[A](xss: Traversable[A]*): List[A]
           |fill[A](n: Int)(elem: => A): List[A]
           |fill[A](n1: Int, n2: Int)(elem: => A): List[List[A]]
           |fill[A](n1: Int, n2: Int, n3: Int)(elem: => A): List[List[List[A]]]
           |fill[A](n1: Int, n2: Int, n3: Int, n4: Int)(elem: => A): List[List[List[List[A]]]]
           |fill[A](n1: Int, n2: Int, n3: Int, n4: Int, n5: Int)(elem: => A): List[List[List[List[List[A]]]]]
           |iterate[A](start: A, len: Int)(f: A => A): List[A]
           |range[T: Integral](start: T, end: T): List[T]
           |range[T: Integral](start: T, end: T, step: T): List[T]
           |tabulate[A](n: Int)(f: Int => A): List[A]
           |tabulate[A](n1: Int, n2: Int)(f: (Int, Int) => A): List[List[A]]
           |tabulate[A](n1: Int, n2: Int, n3: Int)(f: (Int, Int, Int) => A): List[List[List[A]]]
           |tabulate[A](n1: Int, n2: Int, n3: Int, n4: Int)(f: (Int, Int, Int, Int) => A): List[List[List[List[A]]]]
           |tabulate[A](n1: Int, n2: Int, n3: Int, n4: Int, n5: Int)(f: (Int, Int, Int, Int, Int) => A): List[List[List[List[List[A]]]]]
           |unapplySeq[A](x: List[A]): Some[List[A]]
           |+(other: String): String
           |formatted(fmtstr: String): String
           |asInstanceOf[T0]: T0
           |equals(obj: Any): Boolean
           |getClass(): Class[_]
           |hashCode(): Int
           |isInstanceOf[T0]: Boolean
           |synchronized[T0](x$1: T0): T0
           |toString(): String
           |""".stripMargin
    )
  )

  check(
    "implicit-class",
    """
      |object A {
      |  implicit class XtensionMethod(a: Int) {
      |    def increment = a + 1
      |  }
      |  Xtension@@
      |}""".stripMargin,
    """|XtensionMethod(a: Int): XtensionMethod
       |""".stripMargin
  )

  check(
    "fuzzy",
    """
      |object A {
      |  def userService = 1
      |  uService@@
      |}""".stripMargin,
    """|userService: Int
       |""".stripMargin
  )

  check(
    "fuzzy1",
    """
      |object A {
      |  new PBuil@@
      |}""".stripMargin,
    """|ProcessBuilder java.lang
       |ProcessBuilder - scala.sys.process
       |CertPathBuilder - java.security.cert
       |CertPathBuilderSpi - java.security.cert
       |ProcessBuilderImpl - scala.sys.process
       |CertPathBuilderResult - java.security.cert
       |PKIXBuilderParameters - java.security.cert
       |CertPathBuilderException - java.security.cert
       |PKIXCertPathBuilderResult - java.security.cert
       |""".stripMargin
  )

  check(
    "companion",
    """
      |import scala.collection.concurrent._
      |object A {
      |  TrieMap@@
      |}""".stripMargin,
    """|TrieMap scala.collection.concurrent
       |ParTrieMap - scala.collection.parallel.mutable
       |HashTrieMap - scala.collection.immutable.HashMap
       |ParTrieMapCombiner - scala.collection.parallel.mutable
       |ParTrieMapSplitter - scala.collection.parallel.mutable
       |TrieMapSerializationEnd - scala.collection.concurrent
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|TrieMap scala.collection.concurrent
           |TrieMapSerializationEnd - scala.collection.concurrent
           |""".stripMargin
    )
  )

  check(
    "pkg",
    """
      |import scala.collection.conc@@
      |""".stripMargin,
    """|concurrent scala.collection
       |""".stripMargin
  )

  check(
    "import",
    """
      |import JavaCon@@
      |""".stripMargin,
    """|JavaConverters - scala.collection
       |JavaConversions - scala.collection
       |JavaConversions - scala.concurrent
       |AsJavaConverters - scala.collection.convert
       |""".stripMargin,
    compat = Map(
      "2.13" -> """|JavaConverters - scala.collection
                   |AsJavaConsumer - scala.jdk.FunctionWrappers
                   |JavaConversions - scala.concurrent
                   |AsJavaConverters - scala.collection.convert
                   |FromJavaConsumer - scala.jdk.FunctionWrappers
                   |AsJavaBiConsumer - scala.jdk.FunctionWrappers
                   |AsJavaIntConsumer - scala.jdk.FunctionWrappers
                   |AsJavaLongConsumer - scala.jdk.FunctionWrappers
                   |FromJavaBiConsumer - scala.jdk.FunctionWrappers
                   |FromJavaIntConsumer - scala.jdk.FunctionWrappers
                   |""".stripMargin,
      "2.11" -> """|JavaConverters - scala.collection
                   |JavaConversions - scala.collection
                   |JavaConversions - scala.concurrent
                   |""".stripMargin
    )
  )

  check(
    "import1",
    """
      |import Paths@@
      |""".stripMargin,
    """|Paths - java.nio.file
       |""".stripMargin
  )

  check(
    "import2",
    """
      |import Catch@@
      |""".stripMargin,
    """|Catch - scala.util.control.Exception
       |""".stripMargin
  )

  check(
    "import3",
    """
      |import Path@@
      |""".stripMargin,
    """|Path - java.nio.file
       |Paths - java.nio.file
       |Path2D - java.awt.geom
       |CertPath - java.security.cert
       |LayoutPath - java.awt.font
       |PathMatcher - java.nio.file
       |GeneralPath - java.awt.geom
       |XPathResult - org.w3c.dom.xpath
       |PathIterator - java.awt.geom
       |XPathEvaluator - org.w3c.dom.xpath
       |XPathException - org.w3c.dom.xpath
       |""".stripMargin
  )

  check(
    "accessible",
    """
      |package a
      |import MetaData@@
      |""".stripMargin,
    """|DatabaseMetaData - java.sql
       |ParameterMetaData - java.sql
       |ResultSetMetaData - java.sql
       |""".stripMargin
  )

  check(
    "source",
    """
      |package a
      |object Main {
      |  import Inner@@
      |}
      |object Outer {
      |  class Inner
      |}
      |""".stripMargin,
    """|Inner - a.Outer
       |""".stripMargin
  )

  check(
    "duplicate",
    """
      |package a
      |object Main {
      |  import a.Outer.Inner
      |  import Inner@@
      |}
      |object Outer {
      |  class Inner
      |}
      |""".stripMargin,
    ""
  )

  check(
    "duplicate2",
    """
      |package a
      |import java.nio.file.Files
      |
      |final class AbsolutePath private (val underlying: String) extends AnyVal {
      |  def syntax: String = Files@@
      |}
      |
      |object Outer {
      |  object Files
      |}
      |""".stripMargin,
    """Files java.nio.file
      |Files - a.Outer
      |""".stripMargin
  )

  check(
    "commit",
    """
      |package a
      |
      |object Main{
      |  Map.emp@@
      |}
      |""".stripMargin,
    """|empty[K, V]: Map[K,V] (commit: '')
       |""".stripMargin,
    includeCommitCharacter = true,
    compat = Map(
      "2.11" -> "empty[A, B]: Map[A,B] (commit: '')"
    )
  )

  check(
    "commit1",
    """
      |package a
      |
      |object Main{
      |  identity@@
      |}
      |""".stripMargin,
    """|identity[A](x: A): A (commit: '')
       |""".stripMargin,
    includeCommitCharacter = true
  )

  check(
    "numeric-sort",
    """
      |package a
      |
      |object Main{
      |  scala.Function@@
      |}
      |""".stripMargin,
    // assert that we don't sort lexicographically: Function1, Function11, ..., Function2, ...
    """|Function scala
       |Function0 scala
       |Function1 scala
       |Function2 scala
       |Function3 scala
       |Function4 scala
       |Function5 scala
       |Function6 scala
       |Function7 scala
       |Function8 scala
       |Function9 scala
       |Function10 scala
       |Function11 scala
       |Function12 scala
       |Function13 scala
       |Function14 scala
       |Function15 scala
       |Function16 scala
       |Function17 scala
       |Function18 scala
       |Function19 scala
       |Function20 scala
       |Function21 scala
       |Function22 scala
       |PartialFunction scala
       |""".stripMargin
  )

  check(
    "sam",
    """
      |object A {
      |  new java.util.ArrayList[String]().forEach(p => p.toChar@@)
      |}
    """.stripMargin,
    """|toCharArray(): Array[Char]
       |""".stripMargin,
    compat = Map(
      "2.11" -> "" // SAM was introduced in Scala 2.12
    )
  )

  check(
    "implicit",
    """
      |object A {
      |  Array.concat@@
      |}
    """.stripMargin,
    """|concat[T: ClassTag](xss: Array[T]*): Array[T]
       |""".stripMargin
  )
  check(
    "bounds",
    """
      |object A {
      |  java.nio.file.Files.readAttributes@@
      |}
    """.stripMargin,
    """|readAttributes(path: Path, attributes: String, options: LinkOption*): Map[String,Object]
       |readAttributes[A <: BasicFileAttributes](path: Path, type: Class[A], options: LinkOption*): A
       |""".stripMargin
  )

  check(
    "local",
    """
      |object A {
      |  locally {
      |    val thisIsLocal = 1
      |    thisIsLoc@@
      |  }
      |}
    """.stripMargin,
    """|thisIsLocal: Int
       |""".stripMargin
  )
  check(
    "local1",
    """
      |import scala.concurrent.DelayedLazyVal
      |
      |object Main {
      |
      |  List(1).map { client =>
      |    val x = 2
      |    DelayedLazyVal@@
      |    val y = 1
      |  }
      |
      |}
    """.stripMargin,
    """|DelayedLazyVal scala.concurrent
       |""".stripMargin
  )

  check(
    "local2",
    """
      |object Main {
      |  def foo(): Unit = {
      |    val prefixaa = 1
      |    locally {
      |      val prefixbb = 2
      |      println(prefix@@)
      |      val prefixcc = 3
      |    }
      |    val prefixyy = 4
      |  }
      |}
    """.stripMargin,
    """|prefixbb: Int
       |prefixaa: Int
       |""".stripMargin
  )

  check(
    "singleton",
    """
      |class A {
      |  def incrementThisType(): this.type = x
      |  incrementThisType@@
      |}
    """.stripMargin,
    """|incrementThisType(): A.this.type (with underlying type singleton.A)
       |""".stripMargin
  )

  check(
    "deprecated",
    """
      |class A {
      |  1.until@@
      |}
    """.stripMargin,
    """|until(end: Long): NumericRange.Exclusive[Long]
       |until(end: Long, step: Long): NumericRange.Exclusive[Long]
       |until(end: Int): Range
       |until(end: Int, step: Int): Range
       |until(end: Double): Range.Partial[Double,NumericRange[Double]]
       |until(end: Double, step: Double): NumericRange.Exclusive[Double]
       |""".stripMargin,
    // NOTE(olafur) The compiler produces non-deterministic results for this
    // test case, sometime it uses Double and sometimes it uses Float depending
    // other whether its a clean compiler or reused one.
    postProcessObtained = _.replaceAllLiterally("Float", "Double"),
    stableOrder = false,
    compat = Map(
      "2.13" ->
        """|until(end: Int): Range
           |until(end: Int, step: Int): Range
           |until(end: Long): NumericRange.Exclusive[Long]
           |until(end: Long, step: Long): NumericRange.Exclusive[Long]
           |""".stripMargin,
      "2.12.4" ->
        """|until(end: Double): Range.Partial[Double,NumericRange[Double]]
           |until(end: Double, step: Double): NumericRange.Exclusive[Double]
           |until(end: Long): NumericRange.Exclusive[Long]
           |until(end: Long, step: Long): NumericRange.Exclusive[Long]
        """.stripMargin,
      "2.11" ->
        """|until(end: Double): Range.Partial[Double,NumericRange[Double]]
           |until(end: Double, step: Double): NumericRange.Exclusive[Double]
           |until(end: Long): NumericRange.Exclusive[Long]
           |until(end: Long, step: Long): NumericRange.Exclusive[Long]
           |""".stripMargin
    )
  )

  def classFoo: String =
    """
      |import scala.language.dynamics
      |class Foo extends Dynamic {
      |  def banana: Int = 42
      |  def selectDynamic(field: String): Foo = this
      |  def applyDynamicNamed(name: String)(arg: (String, Int)): Foo = this
      |  def updateDynamic(name: String)(value: Int): Foo = this
      |}
      |""".stripMargin

  check(
    "dynamic",
    s"""|$classFoo
        |object Main {
        |  new Foo().bana@@
        |}
        |""".stripMargin,
    """|banana: Int
       |""".stripMargin
  )

  check(
    "dynamic2",
    s"""|$classFoo
        |object Main {
        |  val x = new Foo().foo.bana@@
        |}
        |""".stripMargin,
    """|banana: Int
       |""".stripMargin
  )

  check(
    "dynamic3",
    s"""|$classFoo
        |object Main {
        |  val foo = new Foo()
        |  (foo.bar = 42).bana@@
        |}
        |""".stripMargin,
    """|banana: Int
       |""".stripMargin
  )

  check(
    "dynamic4",
    s"""|$classFoo
        |object Main {
        |  val foo = new Foo().foo(x = 42).bana@@
        |}
        |""".stripMargin,
    """|banana: Int
       |""".stripMargin
  )

  check(
    "dynamic5",
    s"""|$classFoo
        |object Main {
        |  val foo = new Foo()
        |  List(foo.selectDy@@)
        |}
        |""".stripMargin,
    """|selectDynamic(field: String): Foo
       |""".stripMargin
  )

  check(
    "type",
    s"""|object Main {
        |  val foo: ListBuffe@@
        |}
        |""".stripMargin,
    """|ListBuffer - scala.collection.mutable
       |""".stripMargin
  )

  check(
    "type1",
    s"""|object Main {
        |  val foo: Map[Int, ListBuffe@@]
        |}
        |""".stripMargin,
    """|ListBuffer - scala.collection.mutable
       |""".stripMargin
  )

  check(
    "type2",
    s"""|object Main {
        |  new scala.Iterable@@
        |}
        |""".stripMargin,
    """|Iterable scala.collection
       |Iterable[+A] = Iterable
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|Iterable scala.collection
           |Iterable[+A] = Iterable
           |IterableOnce[+A] = IterableOnce
           |""".stripMargin
    )
  )

  check(
    "pat",
    s"""|object Main {
        |  Option(1) match {
        |    case Som@@
        |}
        |""".stripMargin,
    """|Some scala
       |""".stripMargin
  )

  check(
    "pat1",
    s"""|object Main {
        |  Option(1) match {
        |    case List(Som@@)
        |}
        |""".stripMargin,
    """|Some scala
       |""".stripMargin
  )

  check(
    "adt",
    s"""|object Main {
        |  Option(1) match {
        |    case No@@
        |}
        |""".stripMargin,
    """|None scala
       |NoManifest scala.reflect
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "adt1",
    s"""|object Main {
        |  Option(1) match {
        |    case S@@
        |}
        |""".stripMargin,
    """|Some scala
       |Seq scala.collection
       |Set scala.collection.immutable
       |""".stripMargin,
    topLines = Some(3),
    compat = Map(
      "2.13" ->
        """|Some scala
           |Seq scala.collection.immutable
           |Set scala.collection.immutable
           |""".stripMargin
    )
  )

  check(
    "adt2",
    s"""|object Main {
        |  Option(1) match {
        |    case _: S@@
        |}
        |""".stripMargin,
    """|Some scala
       |Seq scala.collection
       |Set scala.collection.immutable
       |""".stripMargin,
    topLines = Some(3),
    compat = Map(
      "2.13" ->
        """|Some scala
           |Seq scala.collection.immutable
           |Set scala.collection.immutable
           |""".stripMargin
    )
  )

  check(
    "adt3",
    s"""|import Matches._
        |object Matches {
        |  val Number = "".r
        |}
        |object Main {
        |  locally {
        |    val NotString = 42
        |    "" match {
        |      case N@@
        |  }
        |}
        |""".stripMargin,
    """|Number: Regex
       |NotString: Int
       |Nil scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "adt4",
    s"""|object Main {
        |  val Number = "".r
        |  "" match {
        |    case _: N@@
        |}
        |""".stripMargin,
    """|Number: Regex
       |Nil scala.collection.immutable
       |NoManifest scala.reflect
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "adt5",
    s"""|object Main {
        |  val Number = "".r
        |  "" match {
        |    case _: N@@
        |}
        |""".stripMargin,
    """|Number: Regex
       |Nil scala.collection.immutable
       |NoManifest scala.reflect
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "underscore",
    s"""|object Main {
        |  List(1).exists(_@@)
        |}
        |""".stripMargin,
    // assert that `_root_` is not a completion item.
    ""
  )

  check(
    "filterText",
    s"""|object Main {
        |  "".substring@@
        |}
        |""".stripMargin,
    """substring(beginIndex: Int): String
      |substring(beginIndex: Int, endIndex: Int): String
      |""".stripMargin,
    filterText = "substring"
  )

  check(
    "error",
    s"""|object Main {
        |  def foo(myError: String): String = {
        |    println(myErr@@)
        |    // type mismatch: obtained Unit, expected String
        |  }
        |}
        |""".stripMargin,
    "myError: String"
  )

  check(
    "sort",
    s"""|object Main {
        |  def printnnn = ""
        |  def printmmm = ""
        |  locally {
        |    val printxxx = ""
        |    print@@
        |  }
        |}
        |""".stripMargin,
    """|printxxx: String
       |printmmm: String
       |printnnn: String
       |print(x: Any): Unit
       |""".stripMargin,
    topLines = Some(4)
  )

  check(
    "fuzzy-member",
    s"""|class Foo {
        |  def getTimeStamp: Int = 0
        |}
        |object Main {
        |  new Foo().time@@
        |}
        |""".stripMargin,
    """|getTimeStamp: Int
       |""".stripMargin
  )

  check(
    "fuzzy-member-sort",
    s"""|class Foo {
        |  def toInt: Int = 0
        |  def instance: Int = 42
        |  def intNumber: Int = 42
        |}
        |object Main {
        |  new Foo().int@@
        |}
        |""".stripMargin,
    """|intNumber: Int
       |toInt: Int
       |instance: Int
       |asInstanceOf[T0]: T0
       |isInstanceOf[T0]: Boolean
       |""".stripMargin
  )

}
