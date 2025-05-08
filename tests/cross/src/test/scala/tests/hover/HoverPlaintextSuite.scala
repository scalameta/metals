package tests.hover
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.ContentType
import scala.meta.pc.PresentationCompilerConfig

import tests.pc.BaseHoverSuite

class HoverPlaintextSuite extends BaseHoverSuite {

  override protected def requiresScalaLibrarySources: Boolean = true

  override protected def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl().copy(
      snippetAutoIndent = false,
      hoverContentType = ContentType.PLAINTEXT
    )

  check(
    "basic-plaintext",
    """|
       |/** 
       |  * Some docstring
       |  */
       |case class Alpha(x: Int) {
       |}
       |
       |object Main {
       |  val x = <<Alp@@ha(2)>>
       |}
       |""".stripMargin,
    """|def apply(x: Int): Alpha
       |
       |Some docstring
       |
       |""".stripMargin
  )

  check(
    "fold-plaintext",
    """|object a {
       |  /**
       |   *  @define option [[scala.Option]]
       |   *  @define f `f`
       |   */
       |  case class OptionWrapper[A](val x: Option[A]) {
       |    /** Returns the result of applying $f to this $option's
       |    *  value if the $option is nonempty.  Otherwise, evaluates
       |    *  expression `ifEmpty`.
       |    *
       |    * This is equivalent to:
       |    * {{{
       |    * option match {
       |    *   case Some(x) => f(x)
       |    *   case None    => ifEmpty
       |    * }
       |    * }}}
       |    * This is also equivalent to:
       |    * {{{
       |    * option map f getOrElse ifEmpty
       |    * }}}
       |    *  @param  ifEmpty the expression to evaluate if empty.
       |    *  @param  f       the function to apply if nonempty.
       |    */
       |    final def fold[B](ifEmpty: => B)(f: A => B): B = ???
       |   }
       |
       |  <<OptionWrapper(Some(1)).fo@@ld("")(_ => ???)>>
       |}
       |""".stripMargin,
    """|Expression type:
       |String
       |
       |Symbol signature:
       |final def fold[B](ifEmpty: => B)(f: Int => B): B
       |
       |Returns the result of applying f to this [[scala.Option]]'s
       | value if the [[scala.Option]] is nonempty.  Otherwise, evaluates
       | expression ifEmpty.
       |
       |This is equivalent to:
       |
       |{{{
       |option match {
       |  case Some(x) => f(x)
       |  case None    => ifEmpty
       |}
       |}}}
       |
       |This is also equivalent to:
       |
       |{{{
       |option map f getOrElse ifEmpty
       |}}}
       |
       |@param ifEmpty: the expression to evaluate if empty.
       |@param f: the function to apply if nonempty.
       |""".stripMargin
  )

  check(
    "head-plaintext",
    """|object a {
       |  /**
       |  * @define coll iterable collection
       |  * @define orderDependent
       |  *
       |  *    Note: might return different results for different runs, unless the underlying collection type is ordered.
       |  */
       |  case class MyList[A](l: List[A]) {
       |   /** Selects the first element of this $coll.
       |    *  $orderDependent
       |    *  @return  the first element of this $coll.
       |    *  @throws NoSuchElementException if the $coll is empty.
       |    */
       |    def head: A = l.head
       |  }
       |  <<MyList(List(1)).he@@ad>>
       |}
       |""".stripMargin,
    """|def head: Int
       |
       |Selects the first element of this iterable collection.
       | Note: might return different results for different runs, unless the underlying collection type is ordered.
       |
       |@returns the first element of this iterable collection.
       |
       |@throws NoSuchElementException: if the iterable collection is empty.
       |""".stripMargin
  )

  check(
    "trait-plaintext",
    """|trait XX
       |object Main extends <<X@@X>>{}
       |""".stripMargin,
    "abstract trait XX: XX",
    compat = Map("3" -> "trait XX: XX")
  )

  check(
    "function-chain4-plaintext",
    """
      |trait Consumer {
      |  def subConsumer[T](i: T): T
      |  def consume(value: Int)(n: Int): Unit
      |}
      |
      |object O {
      |  val consumer: Consumer = ???
      |  List(1).foreach(<<consumer.su@@bConsumer(consumer)>>.consume(1))
      |}
      |""".stripMargin,
    """|Expression type:
       |Consumer
       |
       |Symbol signature:
       |def subConsumer[T](i: T): T
       |""".stripMargin
  )
}
