package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments
import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ImportMissingSymbol
import scala.meta.internal.metals.codeactions.SourceAddMissingImports
import scala.meta.internal.metals.codeactions.ImportMissingSymbolQuickFix
import org.eclipse.lsp4j.CodeActionKind

class ImportMissingSymbolLspSuite
    extends BaseCodeActionLspSuite("importMissingSymbol") {

  // ---------------------------------------------------------------------------
  // Tests for ImportMissingSymbolQuickFix (CodeActionKind.QuickFix)
  // These tests verify the existing behavior for manual import resolution
  // ---------------------------------------------------------------------------

  check(
    "basic",
    """|package a
       |
       |object A {
       |  val f = <<Future>>.successful(2)
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${CreateNewSymbol.title("Future")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(2)
       |}
       |""".stripMargin,
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  check(
    "all-mixed",
    """|package a
       |
       |object A {
       |  <<val f = Future.successful(2)
       |  val t: Future[Unit] = Promise[Unit]().future>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.allSymbolsTitle}
        |${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Promise", "scala.concurrent")}
        |${ImportMissingSymbol.title("Promise", "scala.concurrent.impl")}
        |${CreateNewSymbol.title("Future")}
        |${CreateNewSymbol.title("Promise")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(2)
       |  val t: Future[Unit] = Promise[Unit]().future
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  check(
    "all-mixed-rename",
    """|package a
       |
       |import scala.{concurrent => cu}
       |
       |object A {
       |  <<val f = Future.successful(2)
       |  val t: Future[Unit] = Promise[Unit]().future>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.allSymbolsTitle}
        |${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Promise", "scala.concurrent")}
        |${ImportMissingSymbol.title("Promise", "scala.concurrent.impl")}
        |${CreateNewSymbol.title("Future")}
        |${CreateNewSymbol.title("Promise")}
        |""".stripMargin,
    """|package a
       |
       |import scala.{concurrent => cu}
       |
       |object A {
       |  val f = cu.Future.successful(2)
       |  val t: cu.Future[Unit] = Promise[Unit]().future
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  check(
    "enclosed-range",
    """|package a
       |
       |object A {
       |  val f = Fu<<tu>>re.successful(2)
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${CreateNewSymbol.title("Future")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(2)
       |}
       |""".stripMargin,
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  check(
    "multiple-tries",
    """|package a
       |
       |object A {
       |  val f = <<Try{}
       |  val g = Try{}>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Try", "scala.util")}
        |${CreateNewSymbol.title("Try")}
        |""".stripMargin,
    """|package a
       |
       |import scala.util.Try
       |
       |object A {
       |  val f = Try{}
       |  val g = Try{}
       |}
       |""".stripMargin,
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  check(
    "multi-same-line-ambiguous",
    """|package a
       |
       |object A {
       |  val f = <<new Future(Instant.now)>>
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.allSymbolsTitle}
        |${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${ImportMissingSymbol.title("Instant", "java.time")}
        |${CreateNewSymbol.title("Future")}
        |${CreateNewSymbol.title("Instant")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = new Future(Instant.now)
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
    selectedActionIndex = 1,
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  check(
    "multi-across-lines-non-ambiguous",
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(<<Instant.now)
       |  val b = ListBuffer.newBuilder[Int]>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.allSymbolsTitle}
        |${ImportMissingSymbol.title("Instant", "java.time")}
        |${ImportMissingSymbol.title("ListBuffer", "scala.collection.mutable")}
        |${CreateNewSymbol.title("Instant")}
        |${CreateNewSymbol.title("ListBuffer")}
        |${ConvertToNamedArguments.title("successful(...)")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |import java.time.Instant
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val f = Future.successful(Instant.now)
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
    kind =
      List(ImportMissingSymbolQuickFix.kind, CodeActionKind.RefactorRewrite),
  )

  check(
    "multi-across-lines-ambiguous-and-non-ambiguous",
    """|package a
       |
       |object A {
       |  <<val f: Future[Int] = ???
       |  val i = Instant.now
       |  val a = "  " + "  " + "  "
       |  val b = ListBuffer.newBuilder[Int]>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.allSymbolsTitle}
        |${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${ImportMissingSymbol.title("Instant", "java.time")}
        |${ImportMissingSymbol.title("ListBuffer", "scala.collection.mutable")}
        |${CreateNewSymbol.title("Future")}
        |${CreateNewSymbol.title("Instant")}
        |${CreateNewSymbol.title("ListBuffer")}
        |""".stripMargin,
    """|package a
       |
       |import java.time.Instant
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val f: Future[Int] = ???
       |  val i = Instant.now
       |  val a = "  " + "  " + "  "
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  check(
    "multi-same-symbol",
    """|package a
       |
       |object A {
       |  <<val f: Future[Int] = ???
       |  val i = Instant.now
       |  val b = ListBuffer.newBuilder[Int]
       |  val t: Future[ListBuffer] = ???>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.allSymbolsTitle}
        |${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${ImportMissingSymbol.title("Instant", "java.time")}
        |${ImportMissingSymbol.title("ListBuffer", "scala.collection.mutable")}
        |${CreateNewSymbol.title("Future")}
        |${CreateNewSymbol.title("Instant")}
        |${CreateNewSymbol.title("ListBuffer")}
        |""".stripMargin,
    """|package a
       |
       |import java.time.Instant
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val f: Future[Int] = ???
       |  val i = Instant.now
       |  val b = ListBuffer.newBuilder[Int]
       |  val t: Future[ListBuffer] = ???
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  check(
    "multi-package-object",
    """|package a
       |
       |package object b {
       | object A {
       |    val f = Future.successful(<<Instant.now)>>
       |    val b = ListBuffer.newBuilder[Int]
       | }
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Instant", "java.time")}
        |${CreateNewSymbol.title("Instant")}
        |${ConvertToNamedArguments.title("successful(...)")}
        |""".stripMargin,
    """|package a
       |
       |import java.time.Instant
       |
       |package object b {
       | object A {
       |    val f = Future.successful(Instant.now)
       |    val b = ListBuffer.newBuilder[Int]
       | }
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
    kind =
      List(ImportMissingSymbolQuickFix.kind, CodeActionKind.RefactorRewrite),
  )

  check(
    "i5567",
    """package p {
      |
      |  import scala.collection.mutable
      |
      |  class C {
      |    def f = mutable.Map.empty[Int, Int]
      |    val p = <<Instant.now>>
      |  }
      |}
      |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Instant", "java.time")}
        |${CreateNewSymbol.title("Instant")}
        |""".stripMargin,
    """|package p {
       |
       |  import scala.collection.mutable
       |  import java.time.Instant
       |
       |  class C {
       |    def f = mutable.Map.empty[Int, Int]
       |    val p = Instant.now
       |  }
       |}
       |""".stripMargin,
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  check(
    "6180-0",
    s"""|package example {
        |  trait Foo
        |}
        |package x {
        |  case class B(
        |  ) extends <<F>>oo
        |}
        |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Foo", "example")}
        |${CreateNewSymbol.title("Foo")}
        |""".stripMargin,
    """|package example {
       |  trait Foo
       |}
       |package x {
       |
       |  import example.Foo
       |  case class B(
       |  ) extends Foo
       |}
       |""".stripMargin,
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  check(
    "i6732-happy",
    s"""|package example.a {
        |  private [example] object A {
        |    val foo = "foo"
        |  }
        |}
        |package example.b {
        |  object B {
        |    val bar = <<A>>.foo
        |  }
        |}
        |""".stripMargin,
    s"""|${ImportMissingSymbol.title("A", "example.a")}
        |${CreateNewSymbol.title("A")}
        |""".stripMargin,
    s"""|package example.a {
        |  private [example] object A {
        |    val foo = "foo"
        |  }
        |}
        |package example.b {
        |
        |  import _root_.example.a.A
        |  object B {
        |    val bar = A.foo
        |  }
        |}
        |""".stripMargin,
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  check(
    "i6732-negative (private)",
    s"""|package example.a {
        |  private object A {
        |    val foo = "foo"
        |  }
        |}
        |package example.b {
        |  object B {
        |    val bar = <<A>>
        |  }
        |}
        |""".stripMargin,
    "",
    s"""|package example.a {
        |  private object A {
        |    val foo = "foo"
        |  }
        |}
        |package example.b {
        |  object B {
        |    val bar = A
        |  }
        |}
        |""".stripMargin,
    expectNoDiagnostics = false,
    kind = List(ImportMissingSymbolQuickFix.kind),
    filterAction = _.getTitle() == ImportMissingSymbol.title("A", "example.a"),
  )

  check(
    "i6732-negative (private with out of path access boundary)",
    s"""|package exampleA.a {
        |  private [exampleA] object A {
        |    val foo = "foo"
        |  }
        |}
        |package exampleB.b {
        |  object B {
        |    val bar = <<A>>
        |  }
        |}
        |""".stripMargin,
    "",
    s"""|package exampleA.a {
        |  private [exampleA] object A {
        |    val foo = "foo"
        |  }
        |}
        |package exampleB.b {
        |  object B {
        |    val bar = A
        |  }
        |}
        |""".stripMargin,
    expectNoDiagnostics = false,
    filterAction = _.getTitle() == ImportMissingSymbol.title("A", "example.a"),
    kind = List(ImportMissingSymbolQuickFix.kind),
  )

  // ---------------------------------------------------------------------------
  // Tests for SourceAddMissingImports (source.addMissingImports)
  // These tests verify the auto-import behavior that only imports unambiguous symbols
  // ---------------------------------------------------------------------------

  check(
    "source-add-missing-imports-single-unambiguous",
    """|package a
       |
       |object <<A>> {
       |  val i = Instant.now
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Instant", "java.time")}
        |""".stripMargin,
    """|package a
       |
       |import java.time.Instant
       |
       |object A {
       |  val i = Instant.now
       |}
       |""".stripMargin,
    kind = List(SourceAddMissingImports.kind),
  )

  check(
    "source-add-missing-imports-multiple-unambiguous",
    """|package a
       |
       |object <<A>> {
       |  val i = Instant.now
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin,
    s"""|${SourceAddMissingImports.title}
        |""".stripMargin,
    """|package a
       |
       |import java.time.Instant
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val i = Instant.now
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin,
    kind = List(SourceAddMissingImports.kind),
  )

  check(
    "source-add-missing-imports-skip-ambiguous",
    """|package a
       |
       |object A {
       |  <<val f: Future[Int] = ???
       |  val i = Instant.now>>
       |}
       |""".stripMargin,
    s"""|${SourceAddMissingImports.title}
        |""".stripMargin,
    """|package a
       |
       |import java.time.Instant
       |
       |object A {
       |  val f: Future[Int] = ???
       |  val i = Instant.now
       |}
       |""".stripMargin,
    kind = List(SourceAddMissingImports.kind),
    expectNoDiagnostics = false,
    filterAction = _.getTitle() == SourceAddMissingImports.title,
  )

  check(
    "source-add-missing-imports-mixed-symbols",
    """|package a
       |
       |object A {
       |  <<val i = Instant.now
       |  val f: Future[Int] = ???
       |  val p = Path.of("/tmp")
       |  val b = ListBuffer.newBuilder[Int]>>
       |}
       |""".stripMargin,
    s"""|${SourceAddMissingImports.title}
        |""".stripMargin,
    """|package a
       |
       |import java.nio.file.Path
       |import java.time.Instant
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val i = Instant.now
       |  val f: Future[Int] = ???
       |  val p = Path.of("/tmp")
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin,
    kind = List(SourceAddMissingImports.kind),
    expectNoDiagnostics = false,
    filterAction = _.getTitle() == SourceAddMissingImports.title,
  )

  check(
    "source-add-missing-imports-no-action-when-only-ambiguous",
    """|package a
       |
       |object A {
       |  <<val f: Future[Int] = ???>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${CreateNewSymbol.title("Future")}
        |""".stripMargin,
    """|package a
       |
       |import java.util.concurrent.Future
       |
       |object A {
       |  val f: Future[Int] = ???
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
    expectNoDiagnostics = false,
  )
}
