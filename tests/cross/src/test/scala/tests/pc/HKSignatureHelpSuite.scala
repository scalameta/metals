package tests.pc

import coursierapi._
import tests.BaseSignatureHelpSuite

class HKSignatureHelpSuite extends BaseSignatureHelpSuite {

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala211
  )

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val binaryVersion = createBinaryVersion(scalaVersion)
    Seq(Dependency.of("org.typelevel", s"cats-core_$binaryVersion", "2.8.0"))
  }

  check(
    "foldmap",
    """import cats.implicits._
      |import cats._
      |object a {
      |  Foldable[Option].foldMap(a @@)
      |}
      |""".stripMargin,
    """|foldMap[A, B](fa: Option[A])(f: A => B)(implicit B: Monoid[B]): B
       |              ^^^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|foldMap[A, B](fa: Option[A])(f: A => B)(using B: cats.kernel.Monoid[B]): B
           |              ^^^^^^^^^^^^^
           |""".stripMargin,
      ">=3.4.1-RC1-bin-20240120-hash-NIGHTLY" ->
        """|foldMap[A, B](fa: Option[A])(f: A => B)(using B: Monoid[B]): B
           |              ^^^^^^^^^^^^^
           |""".stripMargin
    )
  )

  // https://github.com/scalameta/metals/issues/5055
  check(
    "named".tag(IgnoreScala3),
    """|
       |object demo {
       |  val f: Int = fun(
       |    logHeaders = true,
       |    logBody = true,
       |    logAction = None,
       |    @@
       |  )
       |  def defaultRedactHeadersWhen(name: String): Boolean = false
       |
       |  /**
       |    *
       |    *
       |    * @param logHeaders
       |    * @param logBody
       |    * @param redactHeadersWhen test description
       |    * @param logAction
       |    * @return
       |    */
       |  def fun(
       |    logHeaders: Boolean,
       |    logBody: Boolean,
       |    redactHeadersWhen: String => Boolean = defaultRedactHeadersWhen,
       |    logAction: Option[String => Unit] = None,
       |  ): Int = ???
       |}
       |""".stripMargin,
    """|**Parameters**
       |- `redactHeadersWhen`: test description
       |fun(<logHeaders: Boolean>, <logBody: Boolean>, <logAction: Option[String => Unit] = None>, <redactHeadersWhen: String => Boolean = defaultRedactHeadersWhen>): Int
       |                                                                                           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
       |  @param <redactHeadersWhen test description
       |""".stripMargin,
    includeDocs = true
  )

}
