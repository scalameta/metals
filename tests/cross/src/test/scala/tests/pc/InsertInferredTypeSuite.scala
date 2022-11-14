package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits

import coursierapi.Dependency
import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.BaseCodeActionSuite

class InsertInferredTypeSuite extends BaseCodeActionSuite {

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val binaryVersion = createBinaryVersion(scalaVersion)
    Seq(
      Dependency.of("org.typelevel", s"cats-effect_$binaryVersion", "3.1.1")
    )
  }

  checkEdit(
    "val",
    """|object A{
       |  val <<alpha>> = 123
       |}""".stripMargin,
    """|object A{
       |  val alpha: Int = 123
       |}""".stripMargin,
  )

  checkEdit(
    "wrong-def-params",
    """|object A{
       |  def <<alpha>>(a: Int, b: String): String = 123
       |}""".stripMargin,
    """|object A{
       |  def alpha(a: Int, b: String): Int = 123
       |}""".stripMargin,
  )

  checkEdit(
    "wrong-val",
    """|object A{
       |  val <<alpha>>:  String = 123
       |}""".stripMargin,
    """|object A{
       |  val alpha: Int = 123
       |}""".stripMargin,
  )

  checkEdit(
    "wrong-val2",
    """|object A{
       |  val <<alpha>> :  String = List(1, 2, 3)
       |}""".stripMargin,
    """|object A{
       |  val alpha: List[Int] = List(1, 2, 3)
       |}""".stripMargin,
  )

  checkEdit(
    "wrong-val3",
    """|object A{
       |  val <<alpha>> :  List[Int] = ""
       |}""".stripMargin,
    """|object A{
       |  val alpha: String = ""
       |}""".stripMargin,
  )

  checkEdit(
    "wrong-val4",
    """|object A{
       |  val <<alpha>> :  List[Int] = s""
       |}""".stripMargin,
    """|object A{
       |  val alpha: String = s""
       |}""".stripMargin,
  )

  checkEdit(
    "wrong-def",
    """|object A{
       |  def <<alpha>> :  String = 123
       |}""".stripMargin,
    """|object A{
       |  def alpha: Int = 123
       |}""".stripMargin,
  )

  checkEdit(
    "wrong-def2",
    """|object A{
       |  def <<alpha>> :  String = List(1, 2, 3)
       |}""".stripMargin,
    """|object A{
       |  def alpha: List[Int] = List(1, 2, 3)
       |}""".stripMargin,
  )

  checkEdit(
    "wrong-def3",
    """|object A{
       |  def <<alpha>> :  List[Int] = ""
       |}""".stripMargin,
    """|object A{
       |  def alpha: String = ""
       |}""".stripMargin,
  )

  checkEdit(
    "wrong-def4",
    """|object A{
       |  def <<alpha>> :  List[Int] = s""
       |}""".stripMargin,
    """|object A{
       |  def alpha: String = s""
       |}""".stripMargin,
  )

  checkEdit(
    "wrong-def-toplevel".tag(IgnoreScala2),
    """|def hello =
       |  val <<a>> :  List[Int] = ""
       |""".stripMargin,
    """|def hello =
       |  val a: String = ""
       |
       |""".stripMargin,
  )

  checkEdit(
    "toplevel".tag(IgnoreScala2),
    """|def <<alpha>> = List("")
       |""".stripMargin,
    """|def alpha: List[String] = List("")
       |""".stripMargin,
  )

  checkEdit(
    "tuple",
    """|object A{
       |  val (<<alpha>>, beta) = (123, 12)
       |}""".stripMargin,
    """|object A{
       |  val (alpha: Int, beta) = (123, 12)
       |}""".stripMargin,
  )

  checkEdit(
    "tuple-inner",
    """|object A{
       |  val ((<<alpha>>, gamma), beta) = ((123, 1), 12)
       |}""".stripMargin,
    """|object A{
       |  val ((alpha: Int, gamma), beta) = ((123, 1), 12)
       |}
       |""".stripMargin,
  )

  checkEdit(
    "tuple-var",
    """|object A{
       |  var (<<alpha>>, beta) = (123, 12)
       |}""".stripMargin,
    """|object A{
       |  var (alpha: Int, beta) = (123, 12)
       |}""".stripMargin,
  )

  checkEdit(
    "var",
    """|object A{
       |  var <<alpha>> = (123, 12)
       |}""".stripMargin,
    """|object A{
       |  var alpha: (Int, Int) = (123, 12)
       |}""".stripMargin,
  )

  checkEdit(
    "def",
    """|object A{
       |  def <<alpha>> = (123, 12)
       |}""".stripMargin,
    """|object A{
       |  def alpha: (Int, Int) = (123, 12)
       |}""".stripMargin,
  )

  checkEdit(
    "def-comment",
    """|object A{
       |  def <<alpha>> /* [] */= (123, 12)
       |}""".stripMargin,
    """|object A{
       |  def alpha: (Int, Int) /* [] */= (123, 12)
       |}
       |""".stripMargin,
  )

  checkEdit(
    "def-comment-param",
    """|object A{
       |  def <<alpha>>() /* [] */= (123, 12)
       |}""".stripMargin,
    """|object A{
       |  def alpha(): (Int, Int) /* [] */= (123, 12)
       |}
       |""".stripMargin,
  )

  checkEdit(
    "def-param",
    """|object A{
       |  def <<alpha>>(a : String) = (123, 12)
       |}""".stripMargin,
    """|object A{
       |  def alpha(a : String): (Int, Int) = (123, 12)
       |}""".stripMargin,
  )

  checkEdit(
    "def-type-param",
    """|object A{
       |  def <<alpha>>[T] = (123, 12)
       |}""".stripMargin,
    """|object A{
       |  def alpha[T]: (Int, Int) = (123, 12)
       |}""".stripMargin,
  )

  checkEdit(
    "auto-import",
    """|object A{
       |  val <<buffer>> = List("").toBuffer
       |}""".stripMargin,
    """|import scala.collection.mutable.Buffer
       |object A{
       |  val buffer: Buffer[String] = List("").toBuffer
       |}""".stripMargin,
  )

  checkEdit(
    "lambda",
    """|object A{
       |  val toStringList = List(1, 2, 3).map(<<int>> => int.toString)
       |}""".stripMargin,
    """|object A{
       |  val toStringList = List(1, 2, 3).map((int: Int) => int.toString)
       |}""".stripMargin,
  )

  checkEdit(
    "lambda-existing-brace",
    """|object A{
       |  val toStringList = List(1, 2, 3).map( /*{}*/(<<int>>) => int.toString)
       |}""".stripMargin,
    """|object A{
       |  val toStringList = List(1, 2, 3).map( /*{}*/(int: Int) => int.toString)
       |}""".stripMargin,
  )

  checkEdit(
    "lambda-brace",
    """|object A{
       |  val toStringList = List(1, 2, 3).map{<<int>> => int.toString}
       |}""".stripMargin,
    """|object A{
       |  val toStringList = List(1, 2, 3).map{int: Int => int.toString}
       |}""".stripMargin,
    compat = Map(
      "3" ->
        """|object A{
           |  val toStringList = List(1, 2, 3).map{(int: Int) => int.toString}
           |}""".stripMargin
    ),
  )

  checkEdit(
    "pattern-match-paren",
    """|object A{
       |  val list = List(1, 2, 3) match {
       |    case <<head>> :: tail => tail
       |    case Nil => Nil
       |  }
       |}""".stripMargin,
    """|object A{
       |  val list = List(1, 2, 3) match {
       |    case (head: Int) :: tail => tail
       |    case Nil => Nil
       |  }
       |}""".stripMargin,
  )

  checkEdit(
    "pattern-match-tuple",
    """|object A{
       |  val list = (1, 2) match {
       |    case (3, two) => 3
       |    case (one, <<two>>) => 2
       |  }
       |}""".stripMargin,
    """|object A{
       |  val list = (1, 2) match {
       |    case (3, two) => 3
       |    case (one, two: Int) => 2
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "pattern-match-option",
    """|object A{
       |  Option(1) match {
       |    case Some(<<t>>) => t
       |    case None =>
       |  }
       |}""".stripMargin,
    """|object A{
       |  Option(1) match {
       |    case Some(t: Int) => t
       |    case None =>
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "pattern-match-list",
    """|object A{
       |  List(1, 2, 3, 4) match {
       |    case List(<<t>>, next, other, _) => t
       |    case _ =>
       |  }
       |}""".stripMargin,
    """|object A{
       |  List(1, 2, 3, 4) match {
       |    case List(t: Int, next, other, _) => t
       |    case _ =>
       |  }
       |}
       |""".stripMargin,
  )

  checkEdit(
    "pattern-match",
    """|object A{
       |  val list = 1 match {
       |    case 2 => "Two!"
       |    case <<otherDigit>> => "Not two!"
       |  }
       |}""".stripMargin,
    """|object A{
       |  val list = 1 match {
       |    case 2 => "Two!"
       |    case otherDigit: Int => "Not two!"
       |  }
       |}""".stripMargin,
  )

  checkEdit(
    "for-comprehension",
    """|object A{
       |  for {
       |    <<i>> <- 1 to 10
       |    j <- 1 to 11
       |  } yield (i, j)
       |}""".stripMargin,
    """|object A{
       |  for {
       |    i: Int <- 1 to 10
       |    j <- 1 to 11
       |  } yield (i, j)
       |}
       |""".stripMargin,
  )

  checkEdit(
    "for-comprehension",
    """|object A{
       |  for {
       |    i <- 1 to 10
       |    <<j>> = i
       |  } yield (i, j)
       |}""".stripMargin,
    """|object A{
       |  for {
       |    i <- 1 to 10
       |    j: Int = i
       |  } yield (i, j)
       |}
       |""".stripMargin,
  )

  val additionalSpace: String = if (isScala3Version(scalaVersion)) " " else ""
  checkEdit(
    "higher-kinded-types",
    """|package io
       |
       |import cats.effect.Resource
       |
       |object Main2 extends IOApp {
       |
       |  trait Logger[T[_]]
       |
       |  def mkLogger[F[_]]: Resource[F, Logger[F]] = ???
       |
       |  def <<serve>>[F[_]]() =
       |    for {
       |      logger <- mkLogger[F]
       |    } yield ()
       |
       |}
       |""".stripMargin,
    s"""|package io
        |
        |import cats.effect.Resource
        |
        |object Main2 extends IOApp {
        |
        |  trait Logger[T[_]]
        |
        |  def mkLogger[F[_]]: Resource[F, Logger[F]] = ???
        |
        |  def serve[F[_]](): Resource[F,${additionalSpace}Unit] =
        |    for {
        |      logger <- mkLogger[F]
        |    } yield ()
        |
        |}
        |""".stripMargin,
  )

  checkEdit(
    "path",
    """|import java.nio.file.Paths
       |object ExplicitResultTypesPrefix {
       |  class Path
       |  def path = Paths.get("")
       |  object inner {
       |    val file = path
       |    object inner {
       |      val nio: java.nio.file.Path = path
       |      object inner {
       |        val <<java>> = path
       |      }
       |    }
       |  }
       |
       |}""".stripMargin,
    """|import java.nio.file.Paths
       |object ExplicitResultTypesPrefix {
       |  class Path
       |  def path = Paths.get("")
       |  object inner {
       |    val file = path
       |    object inner {
       |      val nio: java.nio.file.Path = path
       |      object inner {
       |        val java: _root_.java.nio.file.Path = path
       |      }
       |    }
       |  }
       |
       |}
       |""".stripMargin,
  )

  checkEdit(
    "renamed",
    """|import java.time.{Instant => I}
       |
       |trait Main {
       |  val every: I = ???
       |  val <<second>> = every
       |}
       |
       |""".stripMargin,
    """|import java.time.{Instant => I}
       |
       |trait Main {
       |  val every: I = ???
       |  val second: I = every
       |}
       |
       |""".stripMargin,
  )

  checkEdit(
    "renamed-package",
    """|import java.{ time => t }
       |
       |trait Main {
       |  val every: t.Instant = ???
       |  val <<second>> = every
       |}
       |
       |""".stripMargin,
    """|import java.{ time => t }
       |
       |trait Main {
       |  val every: t.Instant = ???
       |  val second: t.Instant = every
       |}
       |""".stripMargin,
  )

  checkEdit(
    "renamed-package-long",
    """|import scala.{ concurrent => c }
       |
       |trait Main {
       |  val every: c.duration.Duration = ???
       |  val <<second>> = every
       |}
       |
       |""".stripMargin,
    """|import scala.{ concurrent => c }
       |
       |trait Main {
       |  val every: c.duration.Duration = ???
       |  val second: c.duration.Duration = every
       |}
       |""".stripMargin,
  )

  checkEdit(
    "error",
    """|final case class Dependency(
       |    org: String,
       |    name: Option[String],
       |    version: Option[String]
       |)
       |
       |object Dependency {
       |  def <<apply>>(org: String) = Dependency(org, None, None)
       |  def apply(org: String, name: String) = Dependency(org, Some(name), None)
       |}
       |""".stripMargin,
    """|final case class Dependency(
       |    org: String,
       |    name: Option[String],
       |    version: Option[String]
       |)
       |
       |object Dependency {
       |  def apply(org: String): Any = Dependency(org, None, None)
       |  def apply(org: String, name: String) = Dependency(org, Some(name), None)
       |}
       |""".stripMargin,
  )

  checkEdit(
    "either",
    """|object O{
       |  def <<returnEither>>(value: String) = {
       |    if (value == "left") Left("a") else Right("b")
       |  }
       |}""".stripMargin,
    """|object O{
       |  def returnEither(value: String): Either[String,String] = {
       |    if (value == "left") Left("a") else Right("b")
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object O{
           |  def returnEither(value: String): Either[String, String] = {
           |    if (value == "left") Left("a") else Right("b")
           |  }
           |}
           |""".stripMargin
    ),
  )

  checkEdit(
    "backticks-1",
    """|object O{
       |  val <<`bar`>> = 42
       |}""".stripMargin,
    """|object O{
       |  val `bar`: Int = 42
       |}
       |""".stripMargin,
  )

  checkEdit(
    "backticks-2",
    """|object O{
       |  def <<`bar`>> = 42
       |}""".stripMargin,
    """|object O{
       |  def `bar`: Int = 42
       |}
       |""".stripMargin,
  )

  checkEdit(
    "backticks-3",
    """|object O{
       |  List(1).map(<<`a`>> => a + 1)
       |}""".stripMargin,
    """|object O{
       |  List(1).map((`a`: Int) => a + 1)
       |}
       |""".stripMargin,
  )

  checkEdit(
    "literal-types1".tag(IgnoreScalaVersion.forLessThan("2.13.0")),
    """|object O {
       |  val a: Some[1] = Some(1)
       |  val <<b>> = a
       |}
       |""".stripMargin,
    """|object O {
       |  val a: Some[1] = Some(1)
       |  val b: Some[1] = a
       |}
       |""".stripMargin,
  )

  checkEdit(
    "refined-types",
    """|object O{
       |  trait Foo {
       |    type T
       |    type G
       |  }
       |
       |  val <<c>> = new Foo { type T = Int; type G = Long}
       |}
       |""".stripMargin,
    """|object O{
       |  trait Foo {
       |    type T
       |    type G
       |  }
       |
       |  val c: Foo{type T = Int; type G = Long} = new Foo { type T = Int; type G = Long}
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object O{
           |  trait Foo {
           |    type T
           |    type G
           |  }
           |
           |  val c: Foo{type T >: Int <: Int; type G >: Long <: Long} = new Foo { type T = Int; type G = Long}
           |}
           |""".stripMargin
    ),
  )

  checkEdit(
    "refined-types2",
    """|object O{
       |  trait Foo {
       |    type T
       |  }
       |  val c = new Foo { type T = Int }
       |  val <<d>> = c
       |}
       |""".stripMargin,
    """|object O{
       |  trait Foo {
       |    type T
       |  }
       |  val c = new Foo { type T = Int }
       |  val d: Foo{type T = Int} = c
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object O{
           |  trait Foo {
           |    type T
           |  }
           |  val c = new Foo { type T = Int }
           |  val d: Foo{type T >: Int <: Int} = c
           |}
           |""".stripMargin
    ),
  )

  checkEdit(
    "refined-types3",
    """|object O{
       |  trait Foo {
       |    type T
       |  }
       |
       |  val <<c>> = new Foo { type T = Int }
       |}
       |""".stripMargin,
    """|object O{
       |  trait Foo {
       |    type T
       |  }
       |
       |  val c: Foo{type T = Int} = new Foo { type T = Int }
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object O{
           |  trait Foo {
           |    type T
           |  }
           |
           |  val c: Foo{type T >: Int <: Int} = new Foo { type T = Int }
           |}
           |""".stripMargin
    ),
  )

  checkEdit(
    "refined-types4".tag(IgnoreScala2),
    """|trait Foo extends Selectable {
       |  type T
       |}
       |
       |val <<c>> = new Foo {
       |  type T = Int
       |  val x = 0
       |  def y = 0
       |  var z = 0
       |}
       |""".stripMargin,
    """|trait Foo extends Selectable {
       |  type T
       |}
       |
       |val c: Foo{type T >: Int <: Int; val x: Int; def y: Int; val z: Int; def z_=(x$1: Int): Unit} = new Foo {
       |  type T = Int
       |  val x = 0
       |  def y = 0
       |  var z = 0
       |}
       |""".stripMargin,
  )
  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty,
  )(implicit location: Location): Unit =
    test(name) {
      val edits = getAutoImplement(original)
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def getAutoImplement(
      original: String,
      filename: String = "file:/A.scala",
  ): List[l.TextEdit] = {
    val (code, _, offset) = params(original)
    val result = presentationCompiler
      .insertInferredType(
        CompilerOffsetParams(URI.create(filename), code, offset, cancelToken)
      )
      .get()
    result.asScala.toList
  }

}
