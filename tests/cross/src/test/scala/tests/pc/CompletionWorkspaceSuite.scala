package tests.pc

import tests.BaseCompletionSuite

object CompletionWorkspaceSuite extends BaseCompletionSuite {

  checkEditLine(
    "files",
    """package pkg
      |object Main {
      |___
      |}
      |""".stripMargin,
    "  Files@@",
    """  import java.nio.file.Files
      |  Files""".stripMargin
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
      |  Files@@
      |}
      |""".stripMargin,
    """package `import-conflict`
      |object Main {
      |  val java = 42
      |  import _root_.java.nio.file.Files
      |  Files
      |}
      |""".stripMargin,
    filter = _ == "Files - java.nio.file"
  )

  checkEdit(
    "extends",
    """package pkg
      |object Main extends CompletableFutur@@
      |""".stripMargin,
    """package pkg
      |import java.util.concurrent.CompletableFuture
      |object Main extends CompletableFuture
      |""".stripMargin
  )

  checkEdit(
    "replace",
    """package pkg
      |object Main extends CompletableFu@@ture
      |""".stripMargin,
    """package pkg
      |import java.util.concurrent.CompletableFuture
      |object Main extends CompletableFuture
      |""".stripMargin
  )

  checkEdit(
    "block1",
    """|object Main {
       |  def foo(): Unit = {
       |    Files@@
       |  }
       |}
       |""".stripMargin,
    """|object Main {
       |  import java.nio.file.Files
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
    """|object Main {
       |  def foo(): Unit = {
       |    val x = 1
       |    import java.nio.file.Files
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
    """|object Main {
       |  def foo(): Unit = {
       |    val x = 1
       |    import java.nio.file.Files
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
    """|object Main {
       |  import java.nio.file.Files
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
    """|object Main {
       |  import java.nio.file.Files
       |  def foo(): Unit = 1 match {
       |    case 2 if {
       |      Files
       |     } =>
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "match-typed",
    """|object Main {
       |  def foo(): Unit = null match {
       |    case x: ArrayDeque@@ =>
       |  }
       |}
       |""".stripMargin,
    """|object Main {
       |  import java.{util => ju}
       |  def foo(): Unit = null match {
       |    case x: ju.ArrayDeque =>
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "type",
    """|object Main {
       |  def foo(): Unit = {
       |    val x: Failure@@
       |  }
       |}
       |""".stripMargin,
    """|object Main {
       |  def foo(): Unit = {
       |    import scala.util.Failure
       |    val x: Failure
       |  }
       |}
       |""".stripMargin,
    filter = _.contains("scala.util")
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
    """package pkg
      |object Main {
      |  import java.nio.file.Files
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
    """package pkg
      |object Main {
      |  import java.nio.file.Files
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
    """package pkg
      |object Main {
      |  for {
      |    x <- List(1)
      |    _ = {
      |      println(1)
      |      import java.nio.file.Files
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
    """package pkg
      |object Main {
      |  for {
      |    x <- List(1)
      |    if {
      |      println(x)
      |      import java.nio.file.Files
      |      Files
      |    }
      |  } yield x
      |}
      |""".stripMargin
  )

  checkEditLine(
    "backtick",
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
    "annotation-def",
    """|
       |object Main {
       |  @noinline
       |  def foo: ArrayBuffer@@[Int] = ???
       |}
       |""".stripMargin,
    """|
       |object Main {
       |  import scala.collection.mutable
       |  @noinline
       |  def foo: mutable.ArrayBuffer[Int] = ???
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
       |object Main {
       |  import scala.collection.mutable
       |  @deprecated("", "")
       |  class Foo extends mutable.ArrayBuffer[Int]
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
       |object Main {
       |  import scala.collection.mutable
       |  @deprecated("", "")
       |  trait Foo extends mutable.ArrayBuffer[Int]
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
       |import scala.concurrent.Future
       |case class Foo(
       |  name: Future[String]
       |)
       |""".stripMargin,
    filter = _ == "Future - scala.concurrent"
  )
}
