package tests.pc

import tests.BaseCompletionSuite

class CompletionSuite extends BaseCompletionSuite {

  override def requiresJdkSources: Boolean = true

  check(
    "scope",
    """
      |object A {
      |  Lis@@
      |}""".stripMargin,
    """|List scala.collection.immutable
       |List - java.awt
       |List - java.util
       |JList - javax.swing
       |ListUI - javax.swing.plaf
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|List scala.collection.immutable
           |LazyList scala.collection.immutable
           |List - java.awt
           |List - java.util
           |JList - javax.swing
           |""".stripMargin,
      "3" ->
        """|List scala.collection.immutable
           |List - java.awt
           |List - java.util
           |List - scala.collection.immutable
           |List[A](elems: A*): CC[A]
           |""".stripMargin,
      ">=3.4.1-RC1-bin-20240201-hash-NIGHTLY" ->
        """|List scala.collection.immutable
           |List[A](elems: A*): CC[A]
           |List - java.awt
           |List - java.util
           |ListMap[K, V](elems: (K, V)*): CC[K, V]
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
    "cursor".tag(IgnoreScala3),
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

  val dot211: String =
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

  val dot213: String =
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
       |getClass(): Class[_ <: Object]
       |hashCode(): Int
       |isInstanceOf[T0]: Boolean
       |synchronized[T0](x$1: T0): T0
       |toString(): String
       |""".stripMargin

  val dot2137: String =
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
       |fromSpecific(from: Any)(it: IterableOnce[A]): List[A]
       |fromSpecific(it: IterableOnce[A]): List[A]
       |newBuilder(from: Any): Builder[A,List[A]]
       |newBuilder: Builder[A,List[A]]
       |toFactory(from: Any): Factory[A,List[A]]
       |apply(from: Any): Builder[A,List[A]]
       |formatted(fmtstr: String): String
       |asInstanceOf[T0]: T0
       |equals(obj: Object): Boolean
       |getClass(): Class[_ <: Object]
       |hashCode(): Int
       |isInstanceOf[T0]: Boolean
       |synchronized[T0](x$1: T0): T0
       |toString(): String
       |""".stripMargin

  check(
    // before 3.0.1 completions with the same name were included in one completion in a random order
    "dot".tag(
      IgnoreScalaVersion(Set("3.0.0"))
    ),
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
      "2.11" -> dot211,
      "2.13.5" -> dot213,
      "2.13.6" -> dot213,
      "2.13" -> dot2137,
      "3" ->
        """|empty[A]: List[A]
           |from[B](coll: IterableOnce[B]): List[B]
           |newBuilder[A]: Builder[A, List[A]]
           |apply[A](elems: A*): List[A]
           |concat[A](xss: Iterable[A]*): List[A]
           |fill[A](n1: Int, n2: Int)(elem: => A): List[List[A] @uncheckedVariance]
           |fill[A](n1: Int, n2: Int, n3: Int)(elem: => A): List[List[List[A]] @uncheckedVariance]
           |fill[A](n1: Int, n2: Int, n3: Int, n4: Int)(elem: => A): List[List[List[List[A]]] @uncheckedVariance]
           |fill[A](n1: Int, n2: Int, n3: Int, n4: Int, n5: Int)(elem: => A): List[List[List[List[List[A]]]] @uncheckedVariance]
           |fill[A](n: Int)(elem: => A): List[A]
           |iterate[A](start: A, len: Int)(f: A => A): List[A]
           |range[A: Integral](start: A, end: A): List[A]
           |range[A: Integral](start: A, end: A, step: A): List[A]
           |tabulate[A](n1: Int, n2: Int)(f: (Int, Int) => A): List[List[A] @uncheckedVariance]
           |tabulate[A](n1: Int, n2: Int, n3: Int)(f: (Int, Int, Int) => A): List[List[List[A]] @uncheckedVariance]
           |tabulate[A](n1: Int, n2: Int, n3: Int, n4: Int)(f: (Int, Int, Int, Int) => A): List[List[List[List[A]]] @uncheckedVariance]
           |tabulate[A](n1: Int, n2: Int, n3: Int, n4: Int, n5: Int)(f: (Int, Int, Int, Int, Int) => A): List[List[List[List[List[A]]]] @uncheckedVariance]
           |tabulate[A](n: Int)(f: Int => A): List[A]
           |unapplySeq[A](x: List[A] @uncheckedVariance): UnapplySeqWrapper[A]
           |unfold[A, S](init: S)(f: S => Option[(A, S)]): List[A]
           |->[B](y: B): (A, B)
           |ensuring(cond: Boolean): A
           |ensuring(cond: A => Boolean): A
           |ensuring(cond: Boolean, msg: => Any): A
           |ensuring(cond: A => Boolean, msg: => Any): A
           |fromSpecific(from: From)(it: IterableOnce[A]): C
           |fromSpecific(it: IterableOnce[A]): C
           |nn: x.type & T
           |toFactory(from: From): Factory[A, C]
           |formatted(fmtstr: String): String
           |→[B](y: B): (A, B)
           |iterableFactory[A]: Factory[A, List[A]]
           |asInstanceOf[X0]: X0
           |equals(x$0: Any): Boolean
           |getClass[X0 >: List.type](): Class[? <: X0]
           |hashCode(): Int
           |isInstanceOf[X0]: Boolean
           |synchronized[X0](x$0: X0): X0
           |toString(): String
           |wait(): Unit
           |wait(x$0: Long): Unit
           |wait(x$0: Long, x$1: Int): Unit
           |""".stripMargin,
      ">=3.4.1-RC1-bin-20240201-hash-NIGHTLY" ->
        """|empty[A]: List[A]
           |from[B](coll: IterableOnce[B]): List[B]
           |newBuilder[A]: Builder[A, List[A]]
           |apply[A](elems: A*): List[A]
           |concat[A](xss: Iterable[A]*): List[A]
           |fill[A](n1: Int, n2: Int)(elem: => A): List[List[A] @uncheckedVariance]
           |fill[A](n1: Int, n2: Int, n3: Int)(elem: => A): List[List[List[A]] @uncheckedVariance]
           |fill[A](n1: Int, n2: Int, n3: Int, n4: Int)(elem: => A): List[List[List[List[A]]] @uncheckedVariance]
           |fill[A](n1: Int, n2: Int, n3: Int, n4: Int, n5: Int)(elem: => A): List[List[List[List[List[A]]]] @uncheckedVariance]
           |fill[A](n: Int)(elem: => A): List[A]
           |iterate[A](start: A, len: Int)(f: A => A): List[A]
           |range[A: Integral](start: A, end: A): List[A]
           |range[A: Integral](start: A, end: A, step: A): List[A]
           |tabulate[A](n1: Int, n2: Int)(f: (Int, Int) => A): List[List[A] @uncheckedVariance]
           |tabulate[A](n1: Int, n2: Int, n3: Int)(f: (Int, Int, Int) => A): List[List[List[A]] @uncheckedVariance]
           |tabulate[A](n1: Int, n2: Int, n3: Int, n4: Int)(f: (Int, Int, Int, Int) => A): List[List[List[List[A]]] @uncheckedVariance]
           |tabulate[A](n1: Int, n2: Int, n3: Int, n4: Int, n5: Int)(f: (Int, Int, Int, Int, Int) => A): List[List[List[List[List[A]]]] @uncheckedVariance]
           |tabulate[A](n: Int)(f: Int => A): List[A]
           |unapplySeq[A](x: List[A] @uncheckedVariance): UnapplySeqWrapper[A]
           |unfold[A, S](init: S)(f: S => Option[(A, S)]): List[A]
           |->[B](y: B): (List.type, B)
           |ensuring(cond: Boolean): List.type
           |ensuring(cond: List.type => Boolean): List.type
           |ensuring(cond: Boolean, msg: => Any): List.type
           |ensuring(cond: List.type => Boolean, msg: => Any): List.type
           |fromSpecific(from: Any)(it: IterableOnce[Nothing]): List[Nothing]
           |fromSpecific(it: IterableOnce[Nothing]): List[Nothing]
           |nn: List.type & List.type
           |toFactory(from: Any): Factory[Nothing, List[Nothing]]
           |formatted(fmtstr: String): String
           |→[B](y: B): (List.type, B)
           |iterableFactory[A]: Factory[A, List[A]]
           |asInstanceOf[X0]: X0
           |equals(x$0: Any): Boolean
           |getClass[X0 >: List.type](): Class[? <: X0]
           |hashCode(): Int
           |isInstanceOf[X0]: Boolean
           |synchronized[X0](x$0: X0): X0
           |toString(): String
           |wait(): Unit
           |wait(x$0: Long): Unit
           |wait(x$0: Long, x$1: Int): Unit
           |""".stripMargin
      // wait(x$0: Long) won't be replaced with timeoutMills here
      // but it will be replaced in `completionItem/resolve`
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
    """|XtensionMethod(a: Int): A.XtensionMethod
       |""".stripMargin,
    compat = Map(
      "3" -> "XtensionMethod(a: Int): XtensionMethod"
    )
  )

  check(
    "fuzzy".tag(IgnoreScala3),
    """
      |object A {
      |  def userService = 1
      |  uService@@
      |}""".stripMargin,
    """|userService: Int
       |""".stripMargin
  )

  check(
    "fuzzy1".tag(IgnoreScalaVersion(_ => isJava8)),
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
       |PooledConnectionBuilder - javax.sql
       |CertPathBuilderException - java.security.cert
       |PKIXCertPathBuilderResult - java.security.cert
       |""".stripMargin,
    compat = Map(
      "2" ->
        """|ProcessBuilder java.lang
           |ProcessBuilder - scala.sys.process
           |CertPathBuilder - java.security.cert
           |CertPathBuilderSpi - java.security.cert
           |ProcessBuilderImpl - scala.sys.process
           |CertPathBuilderResult - java.security.cert
           |PKIXBuilderParameters - java.security.cert
           |PooledConnectionBuilder - javax.sql
           |CertPathBuilderException - java.security.cert
           |PKIXCertPathBuilderResult - java.security.cert
           |""".stripMargin,
      ">=3.4.1-RC1-bin-20240201-hash-NIGHTLY" ->
        """|ProcessBuilder - scala.sys.process
           |CertPathBuilder - java.security.cert
           |CertPathBuilderSpi - java.security.cert
           |ProcessBuilderImpl - scala.sys.process
           |CertPathBuilderResult - java.security.cert
           |PKIXBuilderParameters - java.security.cert
           |PooledConnectionBuilder - javax.sql
           |CertPathBuilderException - java.security.cert
           |PKIXCertPathBuilderResult - java.security.cert
           |""".stripMargin
    )
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
           |""".stripMargin,
      "3" ->
        """|TrieMap scala.collection.concurrent
           |TrieMap[K, V](elems: (K, V)*): CC[K, V]
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
    "import-star-basic",
    """
      |import scala.collection.immutable.List.*@@
      |""".stripMargin,
    "",
    compat = Map(
      "3" ->
        """|*
           |""".stripMargin
    )
  )

  check(
    "import-star-multi-import",
    """
      |import scala.collection.immutable.List.{range => r, *@@}
      |""".stripMargin,
    "",
    compat = Map(
      "3" ->
        """|*
           |""".stripMargin
    )
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
      "2.11" ->
        """|JavaConverters - scala.collection
           |JavaConversions - scala.collection
           |JavaConversions - scala.concurrent
           |""".stripMargin,
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
      "3" -> """|AsJavaConverters - scala.collection.convert
                |JavaConverters - scala.collection
                |JavaConversions - scala.concurrent
                |AsJavaConsumer - scala.jdk.FunctionWrappers
                |FromJavaConsumer - scala.jdk.FunctionWrappers
                |AsJavaBiConsumer - scala.jdk.FunctionWrappers
                |AsJavaIntConsumer - scala.jdk.FunctionWrappers
                |AsJavaLongConsumer - scala.jdk.FunctionWrappers
                |FromJavaBiConsumer - scala.jdk.FunctionWrappers
                |FromJavaIntConsumer - scala.jdk.FunctionWrappers
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
    "import3".tag(IgnoreScalaVersion(_ => isJava8)),
    """
      |import Path@@
      |""".stripMargin,
    """|Path - java.nio.file
       |Paths - java.nio.file
       |XPath - javax.xml.xpath
       |Path2D - java.awt.geom
       |CertPath - java.security.cert
       |TreePath - javax.swing.tree
       |XPathType - javax.xml.crypto.dsig.spec
       |LayoutPath - java.awt.font
       |XPathNodes - javax.xml.xpath
       |PathMatcher - java.nio.file
       |XPathResult - org.w3c.dom.xpath
       |""".stripMargin
  )

  check(
    "import4",
    """
      |import scala.collection.AbstractIterator@@
      |""".stripMargin,
    """|AbstractIterator scala.collection
       |""".stripMargin
  )

  check(
    "accessible",
    """
      |package a
      |import MetaData@@
      |""".stripMargin,
    """|RowSetMetaData - javax.sql
       |DatabaseMetaData - java.sql
       |ParameterMetaData - java.sql
       |ResultSetMetaData - java.sql
       |RowSetMetaDataImpl - javax.sql.rowset
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
    "",
    compat = Map(
      "3" -> "Inner a.Outer",
      /* TODO Seems that changes in 2.13.5 made this pop up and this
       * might have been a bug in presentation compiler that we were using
       * https://github.com/scalameta/metals/issues/2546
       */
      "2.13" -> "Inner a.Outer"
    )
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
      "2.11" -> "empty[A, B]: Map[A,B] (commit: '')",
      "3" -> "empty[K, V]: Map[K, V] (commit: '')" // space between K V
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
      |  import scala.Function@@
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
       |""".stripMargin,
    // Scala 2.13.5 adds additional completions that actually fit, but are not useful for this test
    topLines = Some(25),
    compat = Map(
      "3" ->
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
           |""".stripMargin
    )
  )

  check(
    "sam".tag(IgnoreScala211),
    """
      |object A {
      |  new java.util.ArrayList[String]().forEach(p => p.toChar@@)
      |}
    """.stripMargin,
    """|toCharArray(): Array[Char]
       |""".stripMargin
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
    "implicit-evidence-many",
    """
      |object A {
      |  object B {
      |    def test[T: Ordering: Numeric](x: T): T = ???
      |  }
      |  B.tes@@
      |}
    """.stripMargin,
    """|test[T: Ordering: Numeric](x: T): T
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|readAttributes(path: Path, attributes: String, options: LinkOption*): java.util.Map[String, Object]
           |readAttributes[A <: BasicFileAttributes](path: Path, type: Class[A], options: LinkOption*): A
           |""".stripMargin
    )
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
       |""".stripMargin,
    compat = Map(
      "3" -> """|DelayedLazyVal scala.concurrent
                |DelayedLazyVal[T](f: () => T, body: => Unit)(exec: ExecutionContext): DelayedLazyVal[T]""".stripMargin,
      "2.13" -> "DelayedLazyVal - scala.concurrent"
    )
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
       |""".stripMargin,
    compat = Map(
      scala3PresentationCompilerVersion -> "incrementThisType(): A.this.type",
      "3" -> "incrementThisType(): (A.this : singleton.A)"
    )
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
    postProcessObtained = _.replace("Float", "Double"),
    stableOrder = false,
    compat = Map(
      "2.11" ->
        """|until(end: Double): Range.Partial[Double,NumericRange[Double]]
           |until(end: Double, step: Double): NumericRange.Exclusive[Double]
           |until(end: Long): NumericRange.Exclusive[Long]
           |until(end: Long, step: Long): NumericRange.Exclusive[Long]
           |""".stripMargin,
      "2.13" ->
        """|until(end: Int): Range
           |until(end: Int, step: Int): Range
           |until(end: Long): NumericRange.Exclusive[Long]
           |until(end: Long, step: Long): NumericRange.Exclusive[Long]
           |""".stripMargin,
      "3" ->
        """|until(end: Int): Range
           |until(end: Int, step: Int): Range
           |until(end: T): Exclusive[T]
           |until(end: T, step: T): Exclusive[T]
           |""".stripMargin,
      ">=3.4.1-RC1-bin-20240201-hash-NIGHTLY" ->
        """|until(end: Int): Range
           |until(end: Int, step: Int): Range
           |until(end: Long): Exclusive[Long]
           |until(end: Long, step: Long): Exclusive[Long]
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
       |""".stripMargin,
    compat = Map(
      "3" -> "selectDynamic(field: String): Foo",
      ">=3.4.0-RC1-bin-20231004-hash-NIGHTLY" -> "banana: Int"
    )
  )

  check(
    "dynamic2",
    s"""|$classFoo
        |object Main {
        |  val x = new Foo().foo.bana@@
        |}
        |""".stripMargin,
    """|banana: Int
       |""".stripMargin,
    compat = Map(
      "3" -> "selectDynamic(field: String): Foo",
      ">=3.4.0-RC1-bin-20231004-hash-NIGHTLY" -> "banana: Int"
    )
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
       |""".stripMargin,
    compat = Map(
      "3" -> "selectDynamic(field: String): Foo",
      ">=3.4.0-RC1-bin-20231004-hash-NIGHTLY" -> "banana: Int"
    )
  )

  check(
    "dynamic4",
    s"""|$classFoo
        |object Main {
        |  val foo = new Foo().foo(x = 42).bana@@
        |}
        |""".stripMargin,
    """|banana: Int
       |""".stripMargin,
    compat = Map(
      "3" -> "selectDynamic(field: String): Foo",
      ">=3.4.0-RC1-bin-20231004-hash-NIGHTLY" -> "banana: Int"
    )
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|ListBuffer[A] - scala.collection.mutable
           |ListBuffer - scala.collection.mutable
           |""".stripMargin
    )
  )

  check(
    "type1",
    s"""|object Main {
        |  val foo: Map[Int, ListBuffe@@]
        |}
        |""".stripMargin,
    """|ListBuffer - scala.collection.mutable
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|ListBuffer[A] - scala.collection.mutable
           |ListBuffer - scala.collection.mutable
           |""".stripMargin
    )
  )

  check(
    "type2"
      // covering it for Scala 3 has to do with covering the
      // CompletionKind.Member case in enrichWithSymbolSearch.
      // The non-members have to get filtered out.
      .tag(IgnoreScala3),
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
    """|Some(value) scala
       |""".stripMargin,
    compat = Map(
      "2.11" -> "Some(x) scala",
      "3" ->
        """|Some(value) scala
           |Some scala
           |Some[A](value: A): Some[A]
           |""".stripMargin
    )
  )

  check(
    "pat1",
    s"""|object Main {
        |  Option(1) match {
        |    case List(Som@@)
        |}
        |""".stripMargin,
    """|Some scala
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|Some scala
           |Some[A](value: A): Some[A]
           |""".stripMargin
    )
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
    """|Some(value) scala
       |Seq scala.collection
       |Set scala.collection.immutable
       |""".stripMargin,
    topLines = Some(3),
    compat = Map(
      "2.13" ->
        """|Some(value) scala
           |Seq scala.collection.immutable
           |Set scala.collection.immutable
           |""".stripMargin,
      "2.11" ->
        """|Some(x) scala
           |Seq scala.collection
           |Set scala.collection.immutable
           |""".stripMargin,
      "3" ->
        """|Some(value) scala
           |Seq scala.collection.immutable
           |Set scala.collection.immutable
           |""".stripMargin
    )
  )

  check(
    "adt2",
    s"""|object Main {
        |  Option(1) match {
        |    case _: Som@@
        |}
        |""".stripMargin,
    """|Some[_] scala
       |""".stripMargin,
    topLines = Some(1),
    compat = Map(
      "2.13" ->
        """|Some[_] scala
           |""".stripMargin,
      "3" ->
        """|Some[?] scala
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
    """|NotString: Int
       |Number: Regex
       |""".stripMargin,
    topLines = Option(2),
    compat = Map(
      "3" ->
        """|NotString: Int
           |Number: Regex
           |""".stripMargin
    )
  )

  check(
    "adt4",
    s"""|object Main {
        |  val Number = "".r
        |  "" match {
        |    case _: Numb@@
        |}
        |""".stripMargin,
    """|Number: Regex
       |""".stripMargin,
    topLines = Option(1)
  )

  check(
    "no-methods-on-case-type",
    s"""|object Main {
        |  val Number = "".r
        |  "" match {
        |    case _: NotImpl@@
        |}
        |""".stripMargin,
    """|NotImplementedError scala
       |""".stripMargin,
    topLines = Some(1)
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
    "fuzzy-member".tag(IgnoreScala3),
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|intNumber: Int
           |""".stripMargin
    )
  )

  check(
    "fields-first",
    s"""|class Foo {
        |  def yeti1: Int = 0
        |  def yeti2: Int = 42
        |  val yeti3 = ""
        |}
        |object Main {
        |  new Foo().ye@@
        |}
        |""".stripMargin,
    """|yeti3: String
       |yeti1: Int
       |yeti2: Int
       |""".stripMargin,
    topLines = Some(3)
  )

  check(
    "using".tag(IgnoreScala2),
    s"""|class Foo {
        |  def max[T](x: T, y: T)(using ord: Ordered[T]): T =
        |    if ord.compare(x, y) < 0 then y else x
        |}
        |object Main {
        |  new Foo().max@@
        |}
        |""".stripMargin,
    """|max[T](x: T, y: T)(using ord: Ordered[T]): T
       |""".stripMargin
  )

  check(
    "annon-context".tag(IgnoreScala2),
    s"""|class Foo {
        |  def max[T](x: T, y: T)(using Ordered[T]): T = ???
        |}
        |object Main {
        |  new Foo().max@@
        |}
        |""".stripMargin,
    """|max[T](x: T, y: T)(using Ordered[T]): T
       |""".stripMargin
  )

  check(
    "ordering-1",
    s"""|object Main {
        |  languageFeature.@@
        |}
        |""".stripMargin,
    """|dynamics scala.languageFeature
       |existentials scala.languageFeature
       |experimental scala.languageFeature
       |higherKinds scala.languageFeature
       |implicitConversions scala.languageFeature
       |""".stripMargin,
    topLines = Some(5),
    compat = Map(
      // higherKinds was deprecated in 2.13.11 but current dotty still depends on 2.13.10
      ">=2.13.11" ->
        """|dynamics scala.languageFeature
           |existentials scala.languageFeature
           |experimental scala.languageFeature
           |implicitConversions scala.languageFeature
           |postfixOps scala.languageFeature
           |""".stripMargin,
      ">=3.0.0" ->
        """|dynamics scala.languageFeature
           |existentials scala.languageFeature
           |experimental scala.languageFeature
           |higherKinds scala.languageFeature
           |implicitConversions scala.languageFeature
           |""".stripMargin,
      ">=3.3.2-RC3" ->
        """|dynamics languageFeature
           |existentials languageFeature
           |experimental languageFeature
           |implicitConversions languageFeature
           |postfixOps languageFeature
           |""".stripMargin,
      ">=3.4.0-RC1-bin-20230921-3d539e6-NIGHTLY" ->
        """|dynamics scala.languageFeature
           |existentials scala.languageFeature
           |experimental scala.languageFeature
           |implicitConversions scala.languageFeature
           |postfixOps scala.languageFeature
           |""".stripMargin
    )
  )

  check(
    "ordering-2",
    s"""|object Main {
        |  1.@@
        |}
        |""".stripMargin,
    """|!=(x: Byte): Boolean
       |!=(x: Char): Boolean
       |!=(x: Double): Boolean
       |!=(x: Float): Boolean
       |!=(x: Int): Boolean
       |!=(x: Long): Boolean
       |!=(x: Short): Boolean
       |%(x: Byte): Int
       |%(x: Char): Int
       |%(x: Double): Double
       |""".stripMargin,
    topLines = Some(10)
  )

  check(
    "ordering-3",
    s"""|class A {
        |  def fooA: String = ""
        |}
        |
        |class B extends A {
        |  def fooB: String = ""
        |}
        |
        |object Main {
        |    val x = new B()
        |    x.foo@@
        |}
        |""".stripMargin,
    """|fooB: String
       |fooA: String
       |""".stripMargin,
    topLines = Some(2)
  )

  // issues with scala 3 https://github.com/lampepfl/dotty/pull/13515
  check(
    "ordering-4".tag(IgnoreScalaVersion.for3LessThan("3.1.1")),
    s"""|class Main {
        |  def main(fooC: Int): Unit = {
        |    val fooA = 1
        |    val fooB = 2
        |    println(foo@@)
        |  }
        |  def foo: String = ""
        |}
        |""".stripMargin,
    """|fooB: Int
       |fooA: Int
       |fooC: Int
       |foo: String
       |""".stripMargin,
    topLines = Some(4)
  )

  checkEdit(
    "newline-dot",
    """|object O {
       |  val a = List(1, 2)
       |    .m@@
       |}""".stripMargin,
    """|object O {
       |  val a = List(1, 2)
       |    .map($0)
       |}""".stripMargin,
    filter = _.contains("map["),
    assertSingleItem = false
  )

  checkEdit(
    "dot-error-tree-edit",
    """
      |case class A(x: Int) {
      |  "".@@
      |  def foo: Int = {
      |    val a = 42
      |     a
      |  }
      |}""".stripMargin,
    """
      |case class A(x: Int) {
      |  "".toInt
      |  def foo: Int = {
      |    val a = 42
      |     a
      |  }
      |}""".stripMargin,
    filter = _.startsWith("toInt:")
  )

  checkItems(
    "select-ignores-next-line",
    s"""
       |object Main {
       |  def hello = {
       |    val name = Option("Bob")
       |    name.@@
       |    println(msg)
       |  }
       |}
       |""".stripMargin,
    _.nonEmpty
  )

  check(
    "empty-template-braces",
    s"""|package x
        |object Foo {
        |  def bar: Int = 42
        |  @@
        |}""".stripMargin,
    """|Foo x
       |bar: Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "empty-template-optional-braces1".tag(IgnoreScala2),
    s"""|package x
        |object Foo:
        |  def bar: Int = 42
        |  @@
        |""".stripMargin,
    """|Foo x
       |bar: Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "empty-line-optional-braces2".tag(IgnoreScala2),
    s"""|package x
        |object Foo:
        |  def bar: Int = 42
        |  def baz: Int =
        |    val x = 1
        |    val y = 2
        |    @@
        |""".stripMargin,
    """|y: Int
       |x: Int
       |Foo x
       |bar: Int
       |""".stripMargin,
    topLines = Some(4)
  )

  check(
    "empty-line-optional-braces3".tag(IgnoreScala2),
    s"""|package x
        |object Foo:
        |  def bar: Int = 42
        |  def baz: Int =
        |    val x = 1
        |    val y = 2
        |  @@
        |""".stripMargin,
    """|Foo x
       |bar: Int
       |baz: Int
       |""".stripMargin,
    topLines = Some(3)
  )

  checkEdit(
    "tab-indented",
    s"""|package x
        |object Foo {
        |	def bar: Int =
        |		42.@@
        |}
        |""".stripMargin,
    s"""|package x
        |object Foo {
        |	def bar: Int =
        |		42.toInt
        |}
        |""".stripMargin,
    filter = _.startsWith("toInt")
  )

  check(
    "no-completions",
    s"""|package nocomp
        |object Foo {
        |  errored.@@
        |}
        |""".stripMargin,
    ""
  )

  check(
    "pkg".tag(IgnoreScalaVersion.for3LessThan("3.1.3")),
    s"""|object Foo {
        |  scala.coll@@
        |}
        |""".stripMargin,
    "collection scala"
  )

  check(
    "pkg-typed".tag(IgnoreScalaVersion.for3LessThan("3.1.3")),
    s"""|object Foo {
        |  val a : scala.coll@@
        |}
        |""".stripMargin,
    "collection scala"
  )

  check(
    "pkg-new".tag(IgnoreScalaVersion.for3LessThan("3.1.3")),
    s"""|object Foo {
        |  new scala.coll@@
        |}
        |""".stripMargin,
    "collection scala"
  )

  check(
    "pkg-scala".tag(IgnoreScalaVersion.for3LessThan("3.1.3")),
    s"""|object Foo {
        |  scala@@
        |}
        |""".stripMargin,
    """|scala <root>
       |""".stripMargin,
    compat = Map(
      "2" ->
        """|scala _root_
           |""".stripMargin
    )
  )

  check(
    "class-members-trait-issue".tag(IgnoreScala211),
    s"""|package x
        |class Foo(
        |  first: java.util.List[Int],
        |  second: String,
        |) {
        |  fir@@
        |  def abc: Int = 23
        |}
        |""".stripMargin,
    """|first: java.util.List[Int]
       |""".stripMargin,
    compat = Map(
      "2" ->
        """|first: List[Int]
           |""".stripMargin
    )
  )

  check(
    "object-at-type-pos",
    s"""|object Foo {
        |  class FFF
        |}
        |object Main {
        |  def f1(a: Fo@@)
        |}
        |""".stripMargin,
    """|Foo object-at-type-pos
       |""".stripMargin,
    compat = Map(
      "2" ->
        """|Foo `object-at-type-pos`
           |""".stripMargin
    ),
    topLines = Some(1)
  )

  check(
    "wildcard-param",
    """
      |object A {
      |  List(1, 3, 4).map { _ =>
      |    @@
      |  }
      |}""".stripMargin,
    "",
    filter = _.startsWith("_")
  )

  check(
    "no-params-method",
    """|object O {
       |  Some(1).get@@
       |}
       |""".stripMargin,
    "get: Int",
    topLines = Some(1)
  )

  check(
    "backtick-type".tag(IgnoreScala3),
    s"""|package b
        |class `t-t`
        |package a {
        |  object `B-A` {
        |    class `x-x`
        |    case class Foo[A](i: A) {
        |      def add(d: => `t-t`) = this
        |    }
        |  }
        |}
        |
        |class O(ddddd : a.`B-A`.Foo[a.`B-A`.`x-x`]) {
        |  val foo = ddddd.a@@
        |}""".stripMargin,
    "add(d: => `t-t`): `B-A`.Foo[`B-A`.`x-x`]",
    topLines = Some(1)
  )

  // In Scala 3 we get: (x$1 : (String, Unit)) @unchecked scala
  // should be solved by https://github.com/scalameta/metals/pull/5292
  check(
    "for-comprehension".tag(IgnoreScala3),
    """|object B {
       |  val a = for {
       |    foo <- List("a", "b", "c")
       |    abc@@ = println("Print!")
       |  } yield abc
       |}
       |""".stripMargin,
    ""
  )

  check(
    "for-comprehension2".tag(IgnoreScala3),
    """|object B {
       |  val a = for {
       |    foo <- List("a", "b", "c")
       |    abcCURSORde = println("Print!")
       |  } yield abc@@
       |}
       |""".stripMargin,
    "abcCURSORde: Unit"
  )

  check(
    "type-with-params",
    s"""|object O {
        | type TTT[A <: Int] = List[A]
        | val t: TT@@
        |}
        |""".stripMargin,
    "TTT",
    compat = Map(
      "3" -> "TTT[A <: Int]"
    ),
    includeDetail = false
  )

  check(
    "type-with-params-with-detail",
    s"""|object O {
        | type TTT[A <: Int] = List[A]
        | val t: TT@@
        |}
        |""".stripMargin,
    "TTT[A] = O.TTT",
    compat = Map(
      "3" -> "TTT[A <: Int] = List[A]"
    )
  )

  check(
    "type-lambda".tag(IgnoreScala2),
    s"""|object O {
        | type TTT = [A <: Int] =>> List[A]
        | val t: TT@@
        |}
        |""".stripMargin,
    "TTT[A <: Int]",
    includeDetail = false
  )

  check(
    "type-lambda2".tag(IgnoreScala2),
    s"""|object O {
        | type TTT[K <: Int] = [V] =>> Map[K, V]
        | val t: TT@@
        |}
        |""".stripMargin,
    "TTT[K <: Int]",
    includeDetail = false
  )

  check(
    "type-lambda2-with-detail".tag(IgnoreScala2),
    s"""|object O {
        | type TTT[K <: Int] = [V] =>> Map[K, V]
        | val t: TT@@
        |}
        |""".stripMargin,
    "TTT[K <: Int] = [V] =>> Map[K, V]"
  )

  check(
    "type-bound",
    s"""|trait O {
        | type TTT <: Int
        | val t: TT@@
        |}
        |""".stripMargin,
    "TTT<: O.TTT",
    compat = Map(
      "3" -> "TTT <: Int"
    )
  )

  check(
    "class-with-params",
    s"""|object O {
        | class AClass[A <: Int]
        | object AClass
        | val v: ACla@@
        |}
        |""".stripMargin,
    "AClass `class-with-params`.O",
    compat = Map(
      "3" ->
        """|AClass[A <: Int] class-with-params.O
           |AClass class-with-params.O
           |AbstractTypeClassManifest - scala.reflect.ClassManifestFactory
           |""".stripMargin
    )
  )

  check(
    "extension-definition-scope".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|trait Foo
       |object T:
       |  extension (x: Fo@@)
       |""".stripMargin,
    """|Foo extension-definition-scope
       |Font - java.awt
       |Form - java.text.Normalizer
       |Format - java.text
       |FontPeer - java.awt.peer
       |FormView - javax.swing.text.html
       |Formatter - java.util
       |Formatter - java.util.logging
       |FocusEvent - java.awt.event
       |FontMetrics - java.awt
       |Found - scala.collection.Searching
       |""".stripMargin
  )

  check(
    "extension-definition-symbol-search".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|object T:
       |  extension (x: ListBuffe@@)
       |""".stripMargin,
    """|ListBuffer[A] - scala.collection.mutable
       |ListBuffer - scala.collection.mutable
       |""".stripMargin
  )

  check(
    "extension-definition-type-parameter".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|trait Foo
       |object T:
       |  extension [A <: Fo@@]
       |""".stripMargin,
    """|Foo extension-definition-type-parameter
       |Font - java.awt
       |Form - java.text.Normalizer
       |Format - java.text
       |FontPeer - java.awt.peer
       |FormView - javax.swing.text.html
       |Formatter - java.util
       |Formatter - java.util.logging
       |FocusEvent - java.awt.event
       |FontMetrics - java.awt
       |Found - scala.collection.Searching
       |""".stripMargin
  )

  check(
    "extension-definition-type-parameter-symbol-search".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|object T:
       |  extension [A <: ListBuffe@@]
       |""".stripMargin,
    """|ListBuffer[A] - scala.collection.mutable
       |ListBuffer - scala.collection.mutable
       |""".stripMargin
  )

  check(
    "extension-definition-using-param-clause".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|trait Foo
       |object T:
       |  extension (using Fo@@)
       |""".stripMargin,
    """|Foo extension-definition-using-param-clause
       |Font - java.awt
       |Form - java.text.Normalizer
       |Format - java.text
       |FontPeer - java.awt.peer
       |FormView - javax.swing.text.html
       |Formatter - java.util
       |Formatter - java.util.logging
       |FocusEvent - java.awt.event
       |FontMetrics - java.awt
       |Found - scala.collection.Searching
       |""".stripMargin
  )

  check(
    "extension-definition-mix-1".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|trait Foo
       |object T:
       |  extension (x: Int)(using Fo@@)
       |""".stripMargin,
    """|Foo extension-definition-mix-1
       |Font - java.awt
       |Form - java.text.Normalizer
       |Format - java.text
       |FontPeer - java.awt.peer
       |FormView - javax.swing.text.html
       |Formatter - java.util
       |Formatter - java.util.logging
       |FocusEvent - java.awt.event
       |FontMetrics - java.awt
       |Found - scala.collection.Searching
       |""".stripMargin
  )

  check(
    "extension-definition-mix-2".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|trait Foo
       |object T:
       |  extension (using Fo@@)(x: Int)(using Foo)
       |""".stripMargin,
    """|Foo extension-definition-mix-2
       |Font - java.awt
       |Form - java.text.Normalizer
       |Format - java.text
       |FontPeer - java.awt.peer
       |FormView - javax.swing.text.html
       |Formatter - java.util
       |Formatter - java.util.logging
       |FocusEvent - java.awt.event
       |FontMetrics - java.awt
       |Found - scala.collection.Searching
       |""".stripMargin
  )

  check(
    "extension-definition-mix-3".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|trait Foo
       |object T:
       |  extension (using Foo)(x: Int)(using Fo@@)
       |""".stripMargin,
    """|Foo extension-definition-mix-3
       |Font - java.awt
       |Form - java.text.Normalizer
       |Format - java.text
       |FontPeer - java.awt.peer
       |FormView - javax.swing.text.html
       |Formatter - java.util
       |Formatter - java.util.logging
       |FocusEvent - java.awt.event
       |FontMetrics - java.awt
       |Found - scala.collection.Searching
       |""".stripMargin
  )

  check(
    "extension-definition-mix-4".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|trait Foo
       |object T:
       |  extension [A](x: Fo@@)
       |""".stripMargin,
    """|Foo extension-definition-mix-4
       |Font - java.awt
       |Form - java.text.Normalizer
       |Format - java.text
       |FontPeer - java.awt.peer
       |FormView - javax.swing.text.html
       |Formatter - java.util
       |Formatter - java.util.logging
       |FocusEvent - java.awt.event
       |FontMetrics - java.awt
       |Found - scala.collection.Searching
       |""".stripMargin
  )

  check(
    "extension-definition-mix-5".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|trait Foo
       |object T:
       |  extension [A](using Fo@@)(x: Int)
       |""".stripMargin,
    """|Foo extension-definition-mix-5
       |Font - java.awt
       |Form - java.text.Normalizer
       |Format - java.text
       |FontPeer - java.awt.peer
       |FormView - javax.swing.text.html
       |Formatter - java.util
       |Formatter - java.util.logging
       |FocusEvent - java.awt.event
       |FontMetrics - java.awt
       |Found - scala.collection.Searching
       |""".stripMargin
  )

  check(
    "extension-definition-mix-6".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|trait Foo
       |object T:
       |  extension [A](using Foo)(x: Fo@@)
       |""".stripMargin,
    """|Foo extension-definition-mix-6
       |Font - java.awt
       |Form - java.text.Normalizer
       |Format - java.text
       |FontPeer - java.awt.peer
       |FormView - javax.swing.text.html
       |Formatter - java.util
       |Formatter - java.util.logging
       |FocusEvent - java.awt.event
       |FontMetrics - java.awt
       |Found - scala.collection.Searching
       |""".stripMargin
  )

  check(
    "extension-definition-mix-7".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|trait Foo
       |object T:
       |  extension [A](using Foo)(x: Fo@@)(using Foo)
       |""".stripMargin,
    """|Foo extension-definition-mix-7
       |Font - java.awt
       |Form - java.text.Normalizer
       |Format - java.text
       |FontPeer - java.awt.peer
       |FormView - javax.swing.text.html
       |Formatter - java.util
       |Formatter - java.util.logging
       |FocusEvent - java.awt.event
       |FontMetrics - java.awt
       |Found - scala.collection.Searching
       |""".stripMargin
  )

  check(
    "extension-definition-select".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|object Test:
       |  class TestSelect()
       |object T:
       |  extension (x: Test.TestSel@@)
       |""".stripMargin,
    """|TestSelect extension-definition-select.Test
       |""".stripMargin
  )

  check(
    "extension-definition-select-mix-1".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|object Test:
       |  class TestSelect()
       |object T:
       |  extension (using Int)(x: Test.TestSel@@)
       |""".stripMargin,
    """|TestSelect extension-definition-select-mix-1.Test
       |""".stripMargin
  )

  check(
    "extension-definition-select-mix-2".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|object Test:
       |  class TestSelect[T]()
       |object T:
       |  extension [T](x: Test.TestSel@@)
       |""".stripMargin,
    """|TestSelect[T] extension-definition-select-mix-2.Test
       |TestSelect extension-definition-select-mix-2.Test
       |""".stripMargin
  )

  checkEdit(
    "no-square-brackets".tag(IgnoreScala2),
    """|object O:
       |  val a = List.appl@@
       |""".stripMargin,
    """|object O:
       |  val a = List.apply($0)
       |""".stripMargin
  )

  checkEdit(
    "multiline-comment",
    """|package a
       |object O {
       |  /*@@
       |  def f = 1
       |}
       |""".stripMargin,
    """|package a
       |object O {
       |  /* $0 */
       |  def f = 1
       |}
       |""".stripMargin
  )

  checkEdit(
    "prepend-instead-of-replace",
    """|object O {
       |  printl@@println()
       |}
       |""".stripMargin,
    """|object O {
       |  printlnprintln()
       |}
       |""".stripMargin,
    assertSingleItem = false
  )

  checkEdit(
    "prepend-instead-of-replace-duplicate-word",
    """|object O {
       |  println@@println()
       |}
       |""".stripMargin,
    """|object O {
       |  printlnprintln()
       |}
       |""".stripMargin,
    assertSingleItem = false
  )

  checkEdit(
    "replace-when-inside",
    """|object O {
       |  print@@ln()
       |}
       |""".stripMargin,
    """|object O {
       |  println()
       |}
       |""".stripMargin,
    assertSingleItem = false
  )

  checkEdit(
    "replace-exact-same",
    """|object O {
       |  println@@()
       |}
       |""".stripMargin,
    """|object O {
       |  println()
       |}
       |""".stripMargin,
    assertSingleItem = false
  )

  check(
    "multi-imports",
    """|import scala.collection.{AbstractMap, Set@@}
       |""".stripMargin,
    """|Set scala.collection
       |SetLike scala.collection
       |SetProxy scala.collection
       |SetProxyLike scala.collection
       |AbstractSet scala.collection
       |""".stripMargin,
    topLines = Some(5),
    compat = Map(
      "3" ->
        """|Set scala.collection
           |SetOps scala.collection
           |""".stripMargin,
      "2.13" -> """|Set scala.collection
                   |SetOps scala.collection
                   |AbstractSet scala.collection
                   |BitSet scala.collection
                   |BitSetOps scala.collection
                   |""".stripMargin
    )
  )

  check(
    "multi-imports-empty-query",
    """|import scala.collection.{AbstractMap, @@}
       |""".stripMargin,
    """|+: scala.collection
       |:+ scala.collection
       |AbstractIterable scala.collection
       |AbstractIterator scala.collection
       |AbstractMap scala.collection
       |""".stripMargin,
    topLines = Some(5),
    compat = Map(
      "3" ->
        """|GenIterable scala.collection
           |GenMap scala.collection
           |GenSeq scala.collection
           |GenSet scala.collection
           |GenTraversable scala.collection
           |""".stripMargin,
      "2.13" -> """|AbstractIndexedSeqView scala.collection
                   |AbstractIterable scala.collection
                   |AbstractIterator scala.collection
                   |AbstractMap scala.collection
                   |AbstractMapView scala.collection
                   |""".stripMargin
    )
  )

  check(
    "import-rename",
    """|import scala.collection.{AbstractMap => Set@@}
       |""".stripMargin,
    ""
  )

}
