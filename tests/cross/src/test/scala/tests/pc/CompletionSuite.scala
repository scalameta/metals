package tests.pc

import tests.BaseCompletionSuite

object CompletionSuite extends BaseCompletionSuite {
  override def beforeAll(): Unit = {
    indexJDK()
  }

  check(
    "scope",
    """
      |object A {
      |  Lis@@
      |}""".stripMargin,
    """|List scala.collection.immutable
       |java.awt.List java.awt
       |java.util.List java.util
       |scala.collection.immutable.ListMap scala.collection.immutable
       |scala.collection.mutable.ListMap scala.collection.mutable
       |scala.collection.immutable.ListSet scala.collection.immutable
       |java.awt.peer.ListPeer java.awt.peer
       |org.w3c.dom.NameList org.w3c.dom
       |org.w3c.dom.NodeList org.w3c.dom
       |java.util.ArrayList java.util
       |org.w3c.dom.stylesheets.MediaList org.w3c.dom.stylesheets
       |""".stripMargin
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
  check(
    "tparam2",
    """
      |object A {
      |  Map.empty[Int, String].getOrEl@@
      |}""".stripMargin,
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
       |""".stripMargin
  )

  check(
    "dot",
    """
      |object A {
      |  List.@@
      |}""".stripMargin,
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
       |->[B](y: B): (A, B)
       |+(other: String): String
       |ensuring(cond: A => Boolean): A
       |ensuring(cond: Boolean): A
       |ensuring(cond: A => Boolean, msg: => Any): A
       |ensuring(cond: Boolean, msg: => Any): A
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

  check(
    "implicit-class",
    """
      |object A {
      |  implicit class XtensionMethod(a: Int) {
      |    def increment = a + 1
      |  }
      |  Xtension@@
      |}""".stripMargin,
    """|XtensionMethod(a: Int): A.XtensionMethod
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
       |scala.sys.process.ProcessBuilder scala.sys.process
       |java.security.cert.CertPathBuilder java.security.cert
       |java.security.cert.CertPathBuilderSpi java.security.cert
       |scala.sys.process.ProcessBuilderImpl scala.sys.process
       |java.security.cert.CertPathBuilderResult java.security.cert
       |java.security.cert.PKIXBuilderParameters java.security.cert
       |java.security.cert.CertPathBuilderException java.security.cert
       |java.security.cert.PKIXCertPathBuilderResult java.security.cert
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
       |scala.collection.parallel.mutable.ParTrieMap scala.collection.parallel.mutable
       |scala.collection.immutable.HashMap.HashTrieMap scala.collection.immutable.HashMap
       |scala.collection.parallel.mutable.ParTrieMapCombiner scala.collection.parallel.mutable
       |scala.collection.parallel.mutable.ParTrieMapSplitter scala.collection.parallel.mutable
       |scala.collection.concurrent.TrieMapSerializationEnd scala.collection.concurrent
       |""".stripMargin
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
    """|scala.collection.JavaConverters scala.collection
       |scala.collection.JavaConversions scala.collection
       |scala.concurrent.JavaConversions scala.concurrent
       |scala.collection.convert.AsJavaConverters scala.collection.convert
       |""".stripMargin,
    compat = Map(
      "2.11" -> """|scala.collection.JavaConverters scala.collection
                   |scala.collection.JavaConversions scala.collection
                   |scala.concurrent.JavaConversions scala.concurrent
                   |""".stripMargin
    )
  )

  check(
    "import1",
    """
      |import Paths@@
      |""".stripMargin,
    """|java.nio.file.Paths java.nio.file
       |""".stripMargin
  )

  check(
    "import2",
    """
      |import Catch@@
      |""".stripMargin,
    """|scala.util.control.Exception.Catch scala.util.control.Exception
       |""".stripMargin
  )

  check(
    "import3",
    """
      |import Path@@
      |""".stripMargin,
    """|java.nio.file.Path java.nio.file
       |java.nio.file.Paths java.nio.file
       |java.awt.geom.Path2D java.awt.geom
       |java.security.cert.CertPath java.security.cert
       |java.awt.font.LayoutPath java.awt.font
       |java.awt.geom.GeneralPath java.awt.geom
       |java.nio.file.PathMatcher java.nio.file
       |org.w3c.dom.xpath.XPathResult org.w3c.dom.xpath
       |java.awt.geom.PathIterator java.awt.geom
       |org.w3c.dom.xpath.XPathEvaluator org.w3c.dom.xpath
       |org.w3c.dom.xpath.XPathException org.w3c.dom.xpath
       |""".stripMargin
  )

  check(
    "accessible",
    """
      |package a
      |import MetaData@@
      |""".stripMargin,
    """|java.sql.DatabaseMetaData java.sql
       |java.sql.ParameterMetaData java.sql
       |java.sql.ResultSetMetaData java.sql
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
    """|a.Outer.Inner a.Outer
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
      |a.Outer.Files a.Outer
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
    """|empty[K, V]: Map[K,V] (commit: '.')
       |""".stripMargin,
    includeCommitCharacter = true,
    compat = Map(
      "2.11" -> "empty[A, B]: Map[A,B] (commit: '.')"
    )
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
    "singleton",
    """
      |class A {
      |  def incrementThisType(): this.type = x
      |  incrementThisType@@
      |}
    """.stripMargin,
    """|incrementThisType(): A.this.type (with underlying type A)
       |""".stripMargin
  )
  check(
    "deprecated",
    """
      |class A {
      |  1.until@@
      |}
    """.stripMargin,
    """|until(end: T): NumericRange.Exclusive[T]
       |until(end: T, step: T): NumericRange.Exclusive[T]
       |until(end: Int): Range
       |until(end: Int, step: Int): Range
       |until(end: T): Range.Partial[T,NumericRange[T]]
       |until(end: T, step: T): NumericRange.Exclusive[T]
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|until(end: T): FractionalProxy#ResultWithoutStep
           |until(end: T, step: T): NumericRange.Exclusive[T]
           |until(end: T): NumericRange.Exclusive[T]
           |until(end: T, step: T): NumericRange.Exclusive[T]
           |""".stripMargin
    )
  )
}
