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
       |->[B](y: B): (List.type, B)
       |+(other: String): String
       |ensuring(cond: Boolean): List.type
       |ensuring(cond: List.type => Boolean): List.type
       |ensuring(cond: Boolean, msg: => Any): List.type
       |ensuring(cond: List.type => Boolean, msg: => Any): List.type
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
           |->[B](y: B): (List.type, B)
           |+(other: String): String
           |ensuring(cond: Boolean): List.type
           |ensuring(cond: List.type => Boolean): List.type
           |ensuring(cond: Boolean, msg: => Any): List.type
           |ensuring(cond: List.type => Boolean, msg: => Any): List.type
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
    """|scala.collection.mutable.ListBuffer scala.collection.mutable
       |""".stripMargin
  )

  check(
    "type1",
    s"""|object Main {
        |  val foo: Map[Int, ListBuffe@@]
        |}
        |""".stripMargin,
    """|scala.collection.mutable.ListBuffer scala.collection.mutable
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
       |""".stripMargin
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
       |ClassNotFoundException java.lang
       |CloneNotSupportedException java.lang
       |EnumConstantNotPresentException java.lang
       |NoClassDefFoundError java.lang
       |NoSuchFieldError java.lang
       |NoSuchFieldException java.lang
       |NoSuchMethodError java.lang
       |NoSuchMethodException java.lang
       |NotImplementedError scala
       |NotNull scala
       |TypeNotPresentException java.lang
       |""".stripMargin
  )

  check(
    "adt1",
    s"""|object Main {
        |  Option(1) match {
        |    case S@@
        |}
        |""".stripMargin,
    """|Some scala
       |IndexedSeq scala.collection
       |Seq scala.collection
       |""".stripMargin,
    topLines = Some(3)
  )

  check(
    "adt2",
    s"""|object Main {
        |  Option(1) match {
        |    case _: S@@
        |}
        |""".stripMargin,
    """|Some scala
       |IndexedSeq scala.collection
       |Seq scala.collection
       |""".stripMargin,
    topLines = Some(3)
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
       |Nothing scala
       |Null scala
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
       |Nothing scala
       |Null scala
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg",
    s"""|object Main {
        |  assert(@@)
        |}
        |""".stripMargin,
    """|assertion = : Boolean
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg1",
    s"""|object Main {
        |  assert(assertion = true, @@)
        |}
        |""".stripMargin,
    """|message = : => Any
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg2",
    s"""|object Main {
        |  assert(true, @@)
        |}
        |""".stripMargin,
    """|message = : => Any
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  def user: String =
    """|case class User(
       |    name: String = "John",
       |    age: Int = 42,
       |    address: String = "",
       |    followers: Int = 0
       |)
       |""".stripMargin
  check(
    "arg3",
    s"""|
        |$user
        |object Main {
        |  User("", address = "", @@)
        |}
        |""".stripMargin,
    """|age = : Int
       |followers = : Int
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg4",
    s"""|
        |$user
        |object Main {
        |  User("", @@, address = "")
        |}
        |""".stripMargin,
    """|age = : Int
       |followers = : Int
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg5",
    s"""|
        |$user
        |object Main {
        |  User("", @@ address = "")
        |}
        |""".stripMargin,
    """|address = : String
       |followers = : Int
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg6",
    s"""|
        |$user
        |object Main {
        |  User("", @@ "")
        |}
        |""".stripMargin,
    """|address = : String
       |age = : Int
       |followers = : Int
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg7",
    s"""|
        |object Main {
        |  Option[Int](@@)
        |}
        |""".stripMargin,
    """|x = : A
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg8",
    s"""|
        |object Main {
        |  "".stripSuffix(@@)
        |}
        |""".stripMargin,
    """|suffix = : String
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg9",
    // `until` has multiple implicit conversion alternatives
    s"""|
        |object Main {
        |  1.until(@@)
        |}
        |""".stripMargin,
    """|end = : Int
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg10",
    s"""|$user
        |object Main {
        |  User(addre@@)
        |}
        |""".stripMargin,
    """|address = : String
       |""".stripMargin
  )

  check(
    "arg11",
    s"""|object Main {
        |  def curry(a: Int)(banana: Int): Int = ???
        |  curry(1)(bana@@)
        |}
        |""".stripMargin,
    """|banana = : Int
       |""".stripMargin
  )

  check(
    "arg12",
    s"""|object Main {
        |  def curry(a: Int)(banana: Int): Int = ???
        |  curry(bana@@)
        |}
        |""".stripMargin,
    ""
  )

  check(
    "arg13",
    s"""|object Main {
        |  Array("")(evidence@@)
        |}
        |""".stripMargin,
    // assert that `evidence$1` is excluded.
    ""
  )

}
