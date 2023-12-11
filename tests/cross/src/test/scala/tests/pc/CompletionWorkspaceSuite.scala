package tests.pc

import tests.BaseCompletionSuite

class CompletionWorkspaceSuite extends BaseCompletionSuite {

  checkEdit(
    "files",
    """package pkg
      |object Main {
      |  val x = Files@@
      |}
      |""".stripMargin,
    """|package pkg
       |
       |import java.nio.file.Files
       |object Main {
       |  val x = Files
       |}
       |""".stripMargin
  )

  checkEditLine(
    "import",
    """package pkg
      |object Main {
      |  ___
      |}
      |""".stripMargin,
    "import Files@@",
    "import java.nio.file.Files",
    filterText = "Files"
  )

  checkEditLine(
    "import-escape",
    """package pkg
      |
      |package app {
      |  object Main {
      |    ___
      |  }
      |}
      |package `type` {
      |  object Banana
      |}
      |""".stripMargin,
    "import Banana@@",
    "import pkg.`type`.Banana"
  )

  checkEdit(
    "conflict",
    """package pkg
      |trait Serializable
      |object Main extends Serializable@@
      |""".stripMargin,
    """package pkg
      |trait Serializable
      |object Main extends java.io.Serializable
      |""".stripMargin,
    filter = _ == "Serializable - java.io"
  )

  checkEdit(
    "import-conflict",
    """package `import-conflict`
      |object Main {
      |  val java = 42
      |  val x = Files@@
      |}
      |""".stripMargin,
    """|package `import-conflict`
       |
       |import java.nio.file.Files
       |object Main {
       |  val java = 42
       |  val x = Files
       |}
       |""".stripMargin,
    filter = _ == "Files - java.nio.file"
  )

  checkEdit(
    "import-conflict2",
    """package `import-conflict2`
      |object java
      |object Main {
      |  val x = Files@@
      |}
      |""".stripMargin,
    """|package `import-conflict2`
       |
       |import _root_.java.nio.file.Files
       |object java
       |object Main {
       |  val x = Files
       |}
       |""".stripMargin,
    filter = _ == "Files - java.nio.file"
  )

  checkEdit(
    "import-conflict3",
    """|package `import-conflict3`
       |import java.util.concurrent.Future
       |case class Foo(
       |  name: Future@@
       |)
       |""".stripMargin,
    """|package `import-conflict3`
       |import java.util.concurrent.Future
       |case class Foo(
       |  name: scala.concurrent.Future[$0]
       |)
       |""".stripMargin,
    filter = elem =>
      if (scalaVersion.startsWith("3")) elem == "Future[T] - scala.concurrent"
      else elem == "Future - scala.concurrent",
    compat = Map(
      "2" ->
        """|package `import-conflict3`
           |import java.util.concurrent.Future
           |case class Foo(
           |  name: scala.concurrent.Future
           |)
           |""".stripMargin
    )
  )

  checkEdit(
    "import-conflict4",
    """|package `import-conflict4`
       |import java.util.concurrent._
       |case class Foo(
       |  name: Future@@
       |)
       |""".stripMargin,
    """|package `import-conflict4`
       |import java.util.concurrent._
       |case class Foo(
       |  name: scala.concurrent.Future[$0]
       |)
       |""".stripMargin,
    filter = elem =>
      if (scalaVersion.startsWith("3")) elem == "Future[T] - scala.concurrent"
      else elem == "Future - scala.concurrent",
    compat = Map(
      "2" ->
        """|package `import-conflict4`
           |import java.util.concurrent._
           |case class Foo(
           |  name: scala.concurrent.Future
           |)
           |""".stripMargin
    )
  )

  checkEdit(
    "import-no-conflict",
    """|package `import-no-conflict`
       |import java.util.concurrent.{Future => _, _}
       |case class Foo(
       |  name: Future@@
       |)
       |""".stripMargin,
    """|package `import-no-conflict`
       |import java.util.concurrent.{Future => _, _}
       |import scala.concurrent.Future
       |case class Foo(
       |  name: Future[$0]
       |)
       |""".stripMargin,
    filter = elem =>
      if (scalaVersion.startsWith("3")) elem == "Future[T] - scala.concurrent"
      else elem == "Future - scala.concurrent",
    compat = Map(
      "2" ->
        """|package `import-no-conflict`
           |import java.util.concurrent.{Future => _, _}
           |import scala.concurrent.Future
           |case class Foo(
           |  name: Future
           |)
           |""".stripMargin
    )
  )

  checkEdit(
    "imported-names-check1",
    """|package `imported-names-check`
       |import scala.concurrent.Future
       |object A {
       |  Await@@
       |}
       |""".stripMargin,
    """|package `imported-names-check`
       |import scala.concurrent.Future
       |import scala.concurrent.Await
       |object A {
       |  Await
       |}
       |""".stripMargin,
    filter = _ == "Await - scala.concurrent"
  )

  checkEdit(
    "extends",
    """package pkg
      |object Main extends CompletableFutur@@
      |""".stripMargin,
    """package pkg
      |
      |import java.util.concurrent.CompletableFuture
      |object Main extends CompletableFuture[$0]
      |""".stripMargin,
    compat = Map(
      "2" ->
        """package pkg
          |
          |import java.util.concurrent.CompletableFuture
          |object Main extends CompletableFuture
          |""".stripMargin
    ),
    assertSingleItem = false
  )

  checkEdit(
    "replace",
    """package pkg
      |object Main extends CompletableFu@@ture
      |""".stripMargin,
    """package pkg
      |
      |import java.util.concurrent.CompletableFuture
      |object Main extends CompletableFuture[$0]
      |""".stripMargin,
    compat = Map(
      "2" ->
        """package pkg
          |
          |import java.util.concurrent.CompletableFuture
          |object Main extends CompletableFuture
          |""".stripMargin
    ),
    assertSingleItem = false
  )

  checkEdit(
    "block1",
    """|object Main {
       |  def foo(): Unit = {
       |    Files@@
       |  }
       |}
       |""".stripMargin,
    """|import java.nio.file.Files
       |object Main {
       |  def foo(): Unit = {
       |    Files
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "block2",
    """|object Main {
       |  def foo(): Unit = {
       |    val x = 1
       |    Files@@
       |  }
       |}
       |""".stripMargin,
    """|import java.nio.file.Files
       |object Main {
       |  def foo(): Unit = {
       |    val x = 1
       |    Files
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "block3",
    """|object Main {
       |  def foo(): Unit = {
       |    val x = 1
       |    println("".substring(Files@@))
       |  }
       |}
       |""".stripMargin,
    """|import java.nio.file.Files
       |object Main {
       |  def foo(): Unit = {
       |    val x = 1
       |    println("".substring(Files))
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "match",
    """|object Main {
       |  def foo(): Unit = 1 match {
       |    case 2 =>
       |      Files@@
       |  }
       |}
       |""".stripMargin,
    """|import java.nio.file.Files
       |object Main {
       |  def foo(): Unit = 1 match {
       |    case 2 =>
       |      Files
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "case-if",
    """|object Main {
       |  def foo(): Unit = 1 match {
       |    case 2 if {
       |      Files@@
       |     } =>
       |  }
       |}
       |""".stripMargin,
    """|import java.nio.file.Files
       |object Main {
       |  def foo(): Unit = 1 match {
       |    case 2 if {
       |      Files
       |     } =>
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "match-typed".tag(
      IgnoreScalaVersion.forLessThan("3.4.0-RC1-bin-20231004-hash-NIGHTLY")
    ),
    """|object Main {
       |  def foo(): Unit = null match {
       |    case x: ArrayDeque@@ =>
       |  }
       |}
       |""".stripMargin,
    """|import java.util.ArrayDeque
       |object Main {
       |  def foo(): Unit = null match {
       |    case x: ArrayDeque[$0] =>
       |  }
       |}
       |""".stripMargin,
    filter = _.contains("java.util"),
    assertSingleItem = false
  )

  checkEdit(
    "type",
    """|object Main {
       |  def foo(): Unit = {
       |    val x: Failure@@
       |  }
       |}
       |""".stripMargin,
    """|import scala.util.Failure
       |object Main {
       |  def foo(): Unit = {
       |    val x: Failure[$0]
       |  }
       |}
       |""".stripMargin,
    filter = _.contains("scala.util"),
    compat = Map(
      "2" ->
        """|import scala.util.Failure
           |object Main {
           |  def foo(): Unit = {
           |    val x: Failure
           |  }
           |}
           |""".stripMargin
    ),
    assertSingleItem = false
  )

  checkEdit(
    "partial-function",
    """package pkg
      |object Main {
      |  List(1).collect {
      |    case 2 =>
      |      Files@@
      |  }
      |}
      |""".stripMargin,
    """|package pkg
       |
       |import java.nio.file.Files
       |object Main {
       |  List(1).collect {
       |    case 2 =>
       |      Files
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "for",
    """package pkg
      |object Main {
      |  for {
      |    x <- List(1)
      |    y = x + 1
      |    if y > 2
      |    _ = Files@@
      |  } yield x
      |}
      |""".stripMargin,
    """|package pkg
       |
       |import java.nio.file.Files
       |object Main {
       |  for {
       |    x <- List(1)
       |    y = x + 1
       |    if y > 2
       |    _ = Files
       |  } yield x
       |}
       |""".stripMargin
  )

  checkEdit(
    "for2",
    """package pkg
      |object Main {
      |  for {
      |    x <- List(1)
      |    _ = {
      |      println(1)
      |      Files@@
      |    }
      |  } yield x
      |}
      |""".stripMargin,
    """|package pkg
       |
       |import java.nio.file.Files
       |object Main {
       |  for {
       |    x <- List(1)
       |    _ = {
       |      println(1)
       |      Files
       |    }
       |  } yield x
       |}
       |""".stripMargin
  )

  checkEdit(
    "for3",
    """package pkg
      |object Main {
      |  for {
      |    x <- List(1)
      |    if {
      |      println(x)
      |      Files@@
      |    }
      |  } yield x
      |}
      |""".stripMargin,
    """|package pkg
       |
       |import java.nio.file.Files
       |object Main {
       |  for {
       |    x <- List(1)
       |    if {
       |      println(x)
       |      Files
       |    }
       |  } yield x
       |}
       |""".stripMargin
  )

  checkEditLine(
    "backtick".tag(IgnoreScala3),
    """package `type`
      |abstract class Foo {
      |  def backtick: Foo
      |}
      |object Main extends Foo {
      |  class Foo // conflict
      |  ___
      |}
      |""".stripMargin,
    "def backtick@@",
    "def backtick: `type`.Foo = ${0:???}"
  )

  checkEdit(
    "annotation-def-with-middle-space",
    """|
       |object Main {
       |  @noinline
       |  def foo: ArrayBuffer@@ [Int] = ???
       |}
       |""".stripMargin,
    """|import scala.collection.mutable.ArrayBuffer
       |
       |object Main {
       |  @noinline
       |  def foo: ArrayBuffer [Int] = ???
       |}
       |""".stripMargin,
    filter = _ == "ArrayBuffer - scala.collection.mutable"
  )

  checkEdit(
    "annotation-class",
    """|package annotationclass
       |object Main {
       |  @deprecated("", "")
       |  class Foo extends ArrayBuffer@@[Int]
       |}
       |""".stripMargin,
    """|package annotationclass
       |
       |import scala.collection.mutable.ArrayBuffer
       |object Main {
       |  @deprecated("", "")
       |  class Foo extends ArrayBuffer[Int]
       |}
       |""".stripMargin,
    filter = _ == "ArrayBuffer - scala.collection.mutable"
  )

  checkEdit(
    "annotation-trait",
    """|package annotationtrait
       |object Main {
       |  @deprecated("", "")
       |  trait Foo extends ArrayBuffer@@[Int]
       |}
       |""".stripMargin,
    """|package annotationtrait
       |
       |import scala.collection.mutable.ArrayBuffer
       |object Main {
       |  @deprecated("", "")
       |  trait Foo extends ArrayBuffer[Int]
       |}
       |""".stripMargin,
    filter = _ == "ArrayBuffer - scala.collection.mutable"
  )

  checkEdit(
    "class-param",
    """|package classparam
       |case class Foo(
       |  name: Future@@[String]
       |)
       |""".stripMargin,
    """|package classparam
       |
       |import scala.concurrent.Future
       |case class Foo(
       |  name: Future[String]
       |)
       |""".stripMargin,
    filter = _ == "Future - scala.concurrent"
  )

  checkEdit(
    "docstring",
    """|package docstring
       |/**
       | * Hello
       | */
       |object Main {
       |  val x = Future@@
       |}
       |""".stripMargin,
    """|package docstring
       |
       |import scala.concurrent.Future
       |/**
       | * Hello
       | */
       |object Main {
       |  val x = Future
       |}
       |""".stripMargin,
    filter = _ == "Future - scala.concurrent"
  )

  checkEdit(
    "docstring-import",
    """|package docstring
       |import scala.util._
       |/**
       | * Hello
       | */
       |object Main {
       |  val x = Future@@
       |}
       |""".stripMargin,
    """|package docstring
       |import scala.util._
       |import scala.concurrent.Future
       |/**
       | * Hello
       | */
       |object Main {
       |  val x = Future
       |}
       |""".stripMargin,
    filter = _ == "Future - scala.concurrent"
  )

  checkEdit(
    "empty-pkg",
    """|import scala.util._
       |object Main {
       |  val x = Future@@
       |}
       |""".stripMargin,
    """|import scala.util._
       |import scala.concurrent.Future
       |object Main {
       |  val x = Future
       |}
       |""".stripMargin,
    filter = _ == "Future - scala.concurrent"
  )

  checkEdit(
    "parent-object",
    """|object Main {
       |  Implicits@@
       |}
       |""".stripMargin,
    """|import scala.concurrent.ExecutionContext
       |object Main {
       |  ExecutionContext.Implicits
       |}
       |""".stripMargin,
    filter = _ == "Implicits - scala.concurrent.ExecutionContext",
    compat = Map {
      "3" ->
        """|import scala.concurrent.ExecutionContext.Implicits
           |object Main {
           |  Implicits
           |}
           |""".stripMargin
    }
  )

  // this test was intended to check that import is rendered correctly - without `$` symbol
  // but it spotted the difference in scala2/scala3 `AutoImports` implementation
  // this one might be removed / joined with `parent-object-scala2` in future
  checkEdit(
    "parent-object-scala3".tag(IgnoreScala2),
    """|object Main {
       |  Implicits@@
       |}
       |""".stripMargin,
    """|import scala.concurrent.ExecutionContext.Implicits
       |object Main {
       |  Implicits
       |}
       |""".stripMargin,
    filter = _ == "Implicits - scala.concurrent.ExecutionContext"
  )

  checkEdit(
    "specify-owner",
    """|object Main {
       |  Map@@
       |}
       |""".stripMargin,
    """|import scala.collection.mutable
       |object Main {
       |  mutable.Map
       |}
       |""".stripMargin,
    filter = _ == "Map - scala.collection.mutable"
  )

  checkEdit(
    "renamed-mutable",
    """|import scala.collection.{mutable => mut}
       |object Main {
       |  Map@@
       |}
       |""".stripMargin,
    """|import scala.collection.{mutable => mut}
       |object Main {
       |  mut.Map
       |}
       |""".stripMargin,
    filter = _ == "Map - scala.collection.mutable"
  )

  checkEdit(
    "ju-import",
    """|object Main {
       |  Map@@
       |}
       |""".stripMargin,
    """|import java.{util => ju}
       |object Main {
       |  ju.Map
       |}
       |""".stripMargin,
    filter = _ == "Map - java.util"
  )

  checkEdit(
    "ju-import-dup",
    """|import java.{util => ju}
       |object Main {
       |  Map@@
       |}
       |""".stripMargin,
    """|import java.{util => ju}
       |object Main {
       |  ju.Map
       |}
       |""".stripMargin,
    filter = _ == "Map - java.util"
  )

  check(
    "ordering-1",
    """|import scala.concurrent.Future
       |object Main {
       |  def foo(
       |    x: Futu@@
       |  ): String = ???
       |}
       |""".stripMargin,
    """|Future scala.concurrent
       |Future - java.util.concurrent
       |FutureTask - java.util.concurrent
       |""".stripMargin,
    topLines = Some(3),
    compat = Map(
      "2.11" ->
        """|Future scala.concurrent
           |Future - java.util.concurrent
           |Future - scala.concurrent.impl
           |""".stripMargin,
      "2.13" ->
        """|Future scala.concurrent
           |Future - java.util.concurrent
           |FutureOps - scala.jdk.FutureConverters
           |""".stripMargin,
      "3" ->
        """|Future[T] scala.concurrent
           |Future scala.concurrent
           |Future[V] - java.util.concurrent
           |""".stripMargin
    )
  )

  check(
    "ordering-2",
    """|import java.util.concurrent.Future
       |object Main {
       |  def foo(
       |    x: Futu@@
       |  ): String = ???
       |}
       |""".stripMargin,
    """|Future java.util.concurrent
       |Future - scala.concurrent
       |FutureTask - java.util.concurrent
       |""".stripMargin,
    topLines = Some(3),
    compat = Map(
      "2.11" ->
        """|Future java.util.concurrent
           |Future - scala.concurrent
           |Future - scala.concurrent.impl
           |""".stripMargin,
      "2.13" ->
        """|Future java.util.concurrent
           |Future - scala.concurrent
           |FutureOps - scala.jdk.FutureConverters
           |""".stripMargin,
      "3" ->
        """|Future[V] java.util.concurrent
           |Future java.util.concurrent
           |Future[T] - scala.concurrent
           |""".stripMargin
    )
  )

  checkEdit(
    "apply-method".tag(IgnoreScala2),
    """|object Main {
       |  val a = ListBuf@@
       |}""".stripMargin,
    """|import scala.collection.mutable.ListBuffer
       |object Main {
       |  val a = ListBuffer($0)
       |}""".stripMargin,
    filter = _.contains("[A]")
  )

  checkEdit(
    "type-import",
    """|package a {
       |  object A {
       |    type Beta = String
       |    def m(): Int = ???
       |  }
       |}
       |
       |package b {
       |  object B{
       |    val x: Bet@@
       |  }
       |}""".stripMargin,
    """|package a {
       |  object A {
       |    type Beta = String
       |    def m(): Int = ???
       |  }
       |}
       |
       |package b {
       |
       |  import a.A
       |  object B{
       |    val x: A.Beta
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|import a.A.Beta
           |package a {
           |  object A {
           |    type Beta = String
           |    def m(): Int = ???
           |  }
           |}
           |
           |package b {
           |  object B{
           |    val x: Beta
           |  }
           |}
           |""".stripMargin
    )
  )

  checkEdit(
    "directly-in-pkg".tag(IgnoreScalaVersion.forLessThan("3.2.2")),
    """|package a:
       |  object Y:
       |    val bar = 123
       |  val fooBar = 123
       |
       |package b:
       |  def main() = fooB@@
       |""".stripMargin,
    """|import a.fooBar
       |package a:
       |  object Y:
       |    val bar = 123
       |  val fooBar = 123
       |
       |package b:
       |  def main() = fooBar
       |""".stripMargin
  )

  check(
    "nested-pkg".tag(IgnoreScalaVersion.forLessThan("3.2.2")),
    """|package a:
       |  package c: // some comment
       |    def increment2 = 2
       |  def increment = 1
       |
       |package d:
       |  val increment3 = 3
       |
       |
       |package b:
       |  def main: Unit = incre@@
       |""".stripMargin,
    """|increment3: Int
       |increment: Int
       |increment2: Int
       |""".stripMargin
  )

  check(
    "indent-method".tag(IgnoreScalaVersion.forLessThan("3.2.2")),
    """|package a:
       |  val y = 123
       |  given intGiven: Int = 123
       |  type Alpha = String
       |  class Foo(x: Int)
       |  object X:
       |    val x = 123
       |  def fooBar(x: Int) = x + 1
       |  package b:
       |    def fooBar(x: String) = x.length
       |
       |package c:
       |  def main() = foo@@
       |""".stripMargin,
    """|fooBar(x: Int): Int
       |fooBar(x: String): Int
       |""".stripMargin
  )

  check(
    "case_class_param",
    """|case class Foo(fooBar: Int, gooBar: Int)
       |class Bar(val fooBaz: Int, val fooBal: Int) {
       |  val fooBar: Option[Int] = Some(1)
       |}
       |object A {
       |  val fooBar: List[Int] = List(1)
       |}
       |
       |object Main {
       |  val fooBar = "Abc"
       |  val x = fooBa@@
       |}
       |""".stripMargin,
    """|fooBar: String
       |fooBar: List[Int]
       |""".stripMargin,
    compat = Map(
      "2" -> """|fooBar: String
                |fooBar - case_class_param.A: List[Int]
                |""".stripMargin
    )
  )

  check(
    "type-apply".tag(IgnoreScala2),
    """|package demo
       |
       |package other:
       |  type MyType = Long
       |
       |  object MyType:
       |    def apply(m: Long): MyType = m
       |
       |val j = MyTy@@
       |""".stripMargin,
    """|MyType(m: Long): MyType
       |MyType - demo.other""".stripMargin
  )

  check(
    "type-apply2".tag(IgnoreScala2),
    """|package demo
       |
       |package other:
       |  object MyType:
       |    def apply(m: Long): MyType = m
       |
       |  type MyType = Long
       |
       |val j = MyTy@@
       |""".stripMargin,
    """|MyType(m: Long): MyType
       |MyType - demo.other""".stripMargin
  )

  checkEdit(
    "method-name-conflict".tag(IgnoreScala2),
    """|package demo
       |
       |object O {
       |  def mmmm(x: Int) = x + 3
       |  class Test {
       |    val mmmm = "abc"
       |    val foo = mmmm@@
       |  }
       |}
       |""".stripMargin,
    """|package demo
       |
       |object O {
       |  def mmmm(x: Int) = x + 3
       |  class Test {
       |    val mmmm = "abc"
       |    val foo = demo.O.mmmm($0)
       |  }
       |}
       |""".stripMargin,
    filter = _.contains("mmmm(x: Int)")
  )

}
