package tests.pc

import coursierapi.Dependency
import tests.BaseCompletionSuite

/**
 * Regression test for https://github.com/scalameta/metals/issues/2634
 *
 * Member completions on `stream.pull` (`fs2.Stream.ToPull`, an AnyVal value
 * class) returned nothing when the selection occurred mid-chain and the rest
 * of the chain failed to typecheck once the `_CURSOR_` instrumentation was
 * inserted (e.g. a trailing `.flatMap(...)`). Removing the trailing call made
 * completions work again.
 */
class Issue2634CompletionSuite extends BaseCompletionSuite {

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val binaryVersion = createBinaryVersion(scalaVersion)
    Seq(Dependency.of("co.fs2", s"fs2-core_$binaryVersion", "3.9.0"))
  }

  // The Scala 3 presentation compiler is a separate implementation living in
  // the dotty repository and fs2 3.9.0 is not published for 2.11.
  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala3.and(IgnoreScala211))

  private val preamble =
    """|import cats.MonadThrow
       |import cats.syntax.all._
       |import fs2.Stream
       |
       |object Main {
       |""".stripMargin

  // Exact shape from the issue: cursor mid-chain, the trailing .flatMap makes
  // the enclosing expression fail to typecheck once _CURSOR_ is inserted.
  checkItems(
    "topull-member-mid-chain-with-trailing-flatmap",
    preamble +
      """|  def onEmptyOrNonEmpty[F[_]: MonadThrow, A, B](stream: Stream[F, A])(onEmpty: F[B])(implicit SC: fs2.Compiler[F, F]): F[B] =
         |    stream.pull.peek1@@.void.stream.compile.last.flatMap(_.getOrElse(onEmpty))
         |}
         |""".stripMargin,
    items => items.map(_.getLabel()).exists(_.startsWith("peek1"))
  )

  // Control from the issue's "Additional context": same chain without the
  // trailing .flatMap already worked in 2021 and must keep working.
  checkItems(
    "topull-member-no-trailing-call",
    preamble +
      """|  def control[F[_]: MonadThrow, A, B](stream: Stream[F, A])(onEmpty: F[B])(implicit SC: fs2.Compiler[F, F]) =
         |    stream.pull.peek1@@.void.stream.compile.last
         |}
         |""".stripMargin,
    items => items.map(_.getLabel()).exists(_.startsWith("peek1"))
  )

  checkItems(
    "topull-member-empty-query",
    preamble +
      """|  def emptyQuery[F[_]: MonadThrow, A](stream: Stream[F, A]): Unit = {
         |    stream.pull.@@
         |  }
         |}
         |""".stripMargin,
    items => {
      val labels = items.map(_.getLabel())
      // The full ToPull member set is recovered, not just `peek1`.
      Seq("peek", "peek1", "uncons", "uncons1").forall(expected =>
        labels.exists(_.startsWith(expected))
      )
    }
  )

  // The qualifier recovers but the query matches no member: the retry must
  // give up gracefully (and restore the grafted tree, which the repeated
  // request on the warm compiler would catch).
  test("topull-member-unmatched-query") {
    val code = preamble +
      """|  def unmatched[F[_]: MonadThrow, A, B](stream: Stream[F, A])(onEmpty: F[B])(implicit SC: fs2.Compiler[F, F]): F[B] =
         |    stream.pull.zzz@@.void.stream.compile.last.flatMap(_.getOrElse(onEmpty))
         |}
         |""".stripMargin
    def labels(restart: Boolean): Seq[String] =
      getItems(code, restart = restart).map(_.getLabel())
    assert(!labels(restart = true).exists(_.startsWith("peek")))
    assert(!labels(restart = false).exists(_.startsWith("peek")))
  }

  // The qualifier is genuinely ill-typed, so the re-typecheck cannot recover
  // it either: completions stay empty instead of crashing.
  checkItems(
    "topull-member-unrecoverable-qualifier",
    preamble +
      """|  def unrecoverable[F[_]: MonadThrow, A, B](stream: Stream[F, A])(onEmpty: F[B])(implicit SC: fs2.Compiler[F, F]): F[B] =
         |    doesNotExist.pull.peek1@@.void.stream.compile.last.flatMap(_.getOrElse(onEmpty))
         |}
         |""".stripMargin,
    items => !items.map(_.getLabel()).exists(_.startsWith("peek1"))
  )

  // The cursor sits later in the chain, so the qualifier to recover is itself
  // a longer expression that needs several implicit conversions
  // (`Stream.InvariantOps` for `.pull`, cats' functor syntax for `.void`).
  checkItems(
    "topull-member-deeper-in-chain",
    preamble +
      """|  def deeper[F[_]: MonadThrow, A, B](stream: Stream[F, A])(onEmpty: F[B])(implicit SC: fs2.Compiler[F, F]): F[B] =
         |    stream.pull.peek1.void.stream@@.compile.last.flatMap(_.getOrElse(onEmpty))
         |}
         |""".stripMargin,
    items => items.map(_.getLabel()).exists(_.startsWith("stream"))
  )

  // The recovery temporarily grafts the re-typechecked qualifier type onto
  // the cached typechecked tree and must restore it once the request is done.
  // Re-running the same completion on the warm compiler reuses that cached
  // tree (same source content, no restart), so a broken restore would corrupt
  // the second request.
  test("topull-member-recovery-is-repeatable") {
    val code = preamble +
      """|  def repeated[F[_]: MonadThrow, A, B](stream: Stream[F, A])(onEmpty: F[B])(implicit SC: fs2.Compiler[F, F]): F[B] =
         |    stream.pull.peek1@@.void.stream.compile.last.flatMap(_.getOrElse(onEmpty))
         |}
         |""".stripMargin
    def hasPeek1(restart: Boolean): Boolean =
      getItems(code, restart = restart)
        .map(_.getLabel())
        .exists(_.startsWith("peek1"))
    assert(hasPeek1(restart = true), "completions should recover `peek1`")
    assert(
      hasPeek1(restart = false),
      "repeated completions on the warm compiler should recover `peek1` again"
    )
  }

  // Applying a recovered completion mid-chain produces a usable edit, not just
  // a label: completing `peek@@` to `peek1` leaves the rest of the chain intact.
  checkEdit(
    "topull-member-accepted-edit",
    preamble +
      """|  def accepted[F[_]: MonadThrow, A, B](stream: Stream[F, A])(onEmpty: F[B])(implicit SC: fs2.Compiler[F, F]): F[B] =
         |    stream.pull.peek@@.void.stream.compile.last.flatMap(_.getOrElse(onEmpty))
         |}
         |""".stripMargin,
    preamble +
      """|  def accepted[F[_]: MonadThrow, A, B](stream: Stream[F, A])(onEmpty: F[B])(implicit SC: fs2.Compiler[F, F]): F[B] =
         |    stream.pull.peek1.void.stream.compile.last.flatMap(_.getOrElse(onEmpty))
         |}
         |""".stripMargin,
    assertSingleItem = false,
    filter = _.startsWith("peek1")
  )
}
