package tests.hover

import tests.pc.BaseHoverSuite

object HoverTermSuite extends BaseHoverSuite {

  check(
    "map",
    """object a {
      |  <<List(1).ma@@p(x => x.toString)>>
      |}
      |""".stripMargin,
    """|List[String]
       |final override def map[B, That](f: Int => B)(implicit bf: CanBuildFrom[List[Int],B,That]): That
       |""".stripMargin.hover
  )

  check(
    "apply",
    """object a {
      |  <<Li@@st(1)>>.map(x => x.toString)
      |}
      |""".stripMargin,
    """|List[Int]
       |override def apply[A](xs: A*): List[A]
       |""".stripMargin.hover,
    compat = Map(
      "2.13" ->
        """|List[Int]
           |def apply[A](elems: A*): List[A]
           |""".stripMargin.hover
    )
  )

  check(
    "case-apply",
    """case class Person(name: String)
      |object a {
      |  <<Per@@son("")>>
      |}
      |""".stripMargin,
    """|def apply(name: String): Person
       |""".stripMargin.hover
  )

  check(
    "interpolator-arg",
    """
      |object a {
      |  val name = "John"
      |  <<s"Hello ${na@@me}">>
      |}
      |""".stripMargin,
    """|val name: String
       |""".stripMargin.hover
  )

  check(
    "interpolator-name",
    """
      |object a {
      |  val name = "John"
      |  <<@@s"Hello ${name}">>
      |}
      |""".stripMargin,
    """|def s(args: Any*): String
       |""".stripMargin.hover,
    compat = Map(
      "2.13" ->
        """|String
           |def s(args: Any*): String = macro
           |""".stripMargin.hover
    )
  )

  check(
    "interpolator-macro",
    """
      |object a {
      |  val height = 1.9d
      |  val name = "James"
      |  <<@@f"$name%s is $height%2.2f meters tall">>
      |}
      |""".stripMargin,
    """|String
       |def f[A >: Any](args: A*): String = macro
       |""".stripMargin.hover
  )

  check(
    "interpolator-apply",
    """
      |object a {
      |  implicit class Xtension(s: StringContext) {
      |    object num {
      |      def apply[T](a: T)(implicit ev: Int): T = ???
      |    }
      |  }
      |  implicit val n = 42
      |  <<@@num"Hello $n">>
      |}
      |""".stripMargin,
    """|Int
       |def apply[T](a: T)(implicit ev: Int): T
       |""".stripMargin.hover
  )

  check(
    "interpolator-unapply",
    """
      |object a {
      |  implicit class Xtension(s: StringContext) {
      |    object num {
      |      def unapply(a: Int): Option[Int] = ???
      |    }
      |  }
      |  42 match {
      |    case nu@@m"$n" =>
      |  }
      |}
      |""".stripMargin,
    """|Int
       |def unapply(a: Int): Option[Int]
       |""".stripMargin.hover
  )

  check(
    "new",
    """
      |class Foo(name: String, age: Int)
      |object a {
      |  <<new Fo@@o("", 42)>>
      |}
      |""".stripMargin,
    """|def this(name: String, age: Int): Foo
       |""".stripMargin.hover
  )

  check(
    "new-tparam",
    """
      |class Foo[T](name: String, age: T)
      |object a {
      |  <<new Fo@@o("", 42)>>
      |}
      |""".stripMargin,
    """|Foo[Int]
       |def this(name: String, age: T): Foo[T]
       |""".stripMargin.hover
  )

  check(
    "new-tparam2",
    """
      |class Foo[T](name: String, age: T)
      |object a {
      |  <<new Fo@@o[Int]("", 42)>>
      |}
      |""".stripMargin,
    """|Foo[Int]
       |def this(name: String, age: T): Foo[T]
       |""".stripMargin.hover
  )

  check(
    "new-anon",
    """
      |class Foo(name: String, age: Int)
      |object a {
      |  new Fo@@o("", 42) {
      |    val x = 2
      |  }
      |}
      |""".stripMargin,
    ""
  )

  check(
    "for-guard",
    """
      |object a {
      |  for {
      |    x <- List(1)
      |    if <<@@x>> > 2
      |  } yield x
      |}
      |""".stripMargin,
    """|x: Int
       |""".stripMargin.hover
  )

  check(
    "for-flatMap",
    """
      |object a {
      |  <<for {
      |    x <@@- Option(1)
      |    if x > 2
      |    y <- Some(x)
      |  } yield x.toString>>
      |}
      |""".stripMargin,
    """|Option[String]
       |def flatMap[B](f: Int => Option[B]): Option[B]
       |""".stripMargin.hover
  )

  check(
    "for-map",
    """
      |object a {
      |  for {
      |    x <- Option(1)
      |    if x > 2
      |    <<y <@@- Some(x)
      |  } yield x.toString>>
      |}
      |""".stripMargin,
    """|Option[String]
       |final def map[B](f: Int => B): Option[B]
       |""".stripMargin.hover
  )

  check(
    "for-keyword",
    """
      |object a {
      |  <<fo@@r {
      |    x <- Option(1)
      |    if x > 2
      |    y <- Some(x)
      |  } yield x.toString>>
      |}
      |""".stripMargin,
    """|Option[String]
       |def flatMap[B](f: Int => Option[B]): Option[B]
       |""".stripMargin.hover
  )

  check(
    "for-yield-keyword",
    """
      |object a {
      |  for {
      |    x <- Option(1)
      |    if x > 2
      |    <<y <- Some(x.toLong)
      |  } yi@@eld x.toString>>
      |}
      |""".stripMargin,
    """|Option[String]
       |final def map[B](f: Long => B): Option[B]
       |""".stripMargin.hover
  )

  check(
    "for-if-keyword",
    """
      |object a {
      |  for {
      |    x <- <<Option(1)
      |    i@@f x > 2>>
      |    y <- Some(x)
      |  } yield x.toString
      |}
      |""".stripMargin,
    """|final def withFilter(p: Int => Boolean): Option[Int]#WithFilter
       |""".stripMargin.hover
  )

  check(
    "object",
    """
      |import java.nio.file._
      |object a {
      |  FileVisit@@Result.CONTINUE
      |}
      |""".stripMargin,
    """|```scala
       |class java.nio.file.FileVisitResult
       |```
       |""".stripMargin
  )

  check(
    "object2",
    """package app
      |import java.nio.file._
      |object Outer {
      |  object Foo {
      |    class Inner
      |  }
      |}
      |object a {
      |  new Outer.Fo@@o.Inner
      |}
      |""".stripMargin,
    """|```scala
       |object app.Outer.Foo
       |```
       |""".stripMargin,
    automaticPackage = false
  )

  check(
    "import",
    """
      |import java.n@@io.file._
      |""".stripMargin,
    """|```scala
       |package java.nio
       |```
       |""".stripMargin
  )

  check(
    "import2",
    """
      |import jav@@a.nio.file._
      |""".stripMargin,
    """|```scala
       |package java
       |```
       |""".stripMargin
  )

  check(
    "import3",
    """
      |import java.nio.fil@@e._
      |""".stripMargin,
    """|```scala
       |package java.nio.file
       |```
       |""".stripMargin
  )

  check(
    "import4",
    """
      |import java.nio.file.{Fil@@es => File,Paths}
      |""".stripMargin,
    """|class java.nio.file.Files
       |""".stripMargin.hover
  )

  check(
    "import5",
    """
      |import java.nio.file.{Files => File,P@@aths}
      |""".stripMargin,
    """|class java.nio.file.Paths
       |""".stripMargin.hover
  )

  check(
    "implicit-conv",
    """
      |object Main {
      |  <<"".substring(0, 1).stripSu@@ffix("")>>
      |}
      |""".stripMargin,
    """|def stripSuffix(suffix: String): String
       |""".stripMargin.hover
  )

  check(
    "implicit-conv2",
    """case class Text[T](value: T)
      |object Text {
      |  implicit def conv[T](value: T): Text[T] =
      |    Text(value)
      |}
      |object Main {
      |  def foo[T](text: Text[T]): T = text.value
      |  val number = 42
      |  foo(<<num@@ber>>)
      |}
      |""".stripMargin,
    """|val number: Int
       |""".stripMargin.hover
  )

  check(
    "widen",
    """
      |object Main {
      |  println(<<java.nio.file.FileVisitResult.CONTIN@@UE>>)
      |}
      |""".stripMargin,
    """final val CONTINUE: FileVisitResult""".hover
  )
}
