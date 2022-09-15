package tests.pc

import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig

import tests.BaseCompletionSuite

class CompletionPatternSuite extends BaseCompletionSuite {

  def paramHint: Option[String] = Some("param-hint")

  override def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl().copy(
      _parameterHintsCommand = paramHint
    )

  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala2)

  checkEdit(
    "empty",
    """
      |object A {
      |  Option(1) match {
      |    case @@ =>
      |  }
      |}""".stripMargin,
    """
      |object A {
      |  Option(1) match {
      |    case Some(value)$0 =>
      |  }
      |}""".stripMargin,
    filter = _.contains("Some(value)"),
  )

  check(
    "ident",
    """
      |object A {
      |  Option(1) match {
      |    case S@@ =>
      |  }
      |}""".stripMargin,
    """|Some(value) scala
       |""".stripMargin,
    topLines = Some(1),
  )

  check(
    "bind",
    """
      |object A {
      |  Option(1) match {
      |    case abc @ @@ =>
      |  }
      |}""".stripMargin,
    """|None scala
       |Some(value) scala
       |""".stripMargin,
    topLines = Some(2),
  )
  check(
    "bind-ident",
    """
      |object A {
      |  Option(1) match {
      |    case abc @ S@@ =>
      |  }
      |}""".stripMargin,
    """|Some(value) scala
       |""".stripMargin,
    topLines = Some(1),
  )
  check(
    "wildcard",
    """
      |object A {
      |  Option(1) match {
      |    case _: @@ =>
      |  }
      |}""".stripMargin,
    """|Some[?] scala
       |""".stripMargin,
    topLines = Some(1),
  )
  check(
    "wildcard-ident",
    """
      |object A {
      |  Option(1) match {
      |    case _: S@@ =>
      |  }
      |}""".stripMargin,
    """|Some[?] scala
       |""".stripMargin,
    topLines = Some(1),
  )

  check(
    "typed-bind",
    """
      |object A {
      |  Option(1) match {
      |    case ab: @@ =>
      |  }
      |}""".stripMargin,
    """|Some[?] scala
       |""".stripMargin,
    topLines = Some(1),
  )

  check(
    "typed-bind-ident",
    """
      |object A {
      |  Option(1) match {
      |    case ab: S@@ =>
      |  }
      |}""".stripMargin,
    """|Some[?] scala
       |""".stripMargin,
    topLines = Some(1),
  )
}
