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

  override val compatProcess: Map[String, String => String] = Map(
    "3" -> { (s: String) =>
      // In Scala3 wildcard type has been changed from [_] to [?]
      s.replace("Some[_]", "Some[?]")
    },
    "2.11" -> { (s: String) => s.replace("Some(value)", "Some(x)") }
  )

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
    filter = s => s.contains("Some(value)") || s.contains("Some(x)")
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
    topLines = Some(1)
  )

  check(
    "bind",
    """
      |object A {
      |  Option(1) match {
      |    case ma@@
      |  }
      |}""".stripMargin,
    "",
    compat = Map(
      scala3PresentationCompilerVersion ->
        """|main scala
           |macros - scala.languageFeature.experimental
           |macroImpl - scala.reflect.macros.internal
           |""".stripMargin,
      "3.3.2" ->
        """|main scala
           |macros - languageFeature.experimental
           |macroImpl(referenceToMacroImpl: Any): macroImpl
           |macroImpl - scala.reflect.macros.internal
           |""".stripMargin,
      "3" ->
        """|main scala
           |macros - scala.languageFeature.experimental
           |macroImpl(referenceToMacroImpl: Any): macroImpl
           |macroImpl - scala.reflect.macros.internal
           |""".stripMargin,
      ">=3.4.1-RC1-bin-20240201-hash-NIGHTLY" ->
        """|macros - scala.languageFeature.experimental
           |macroImpl - scala.reflect.macros.internal
           |""".stripMargin
    )
  )

  check(
    "bind2",
    """
      |object A {
      |  Option(1) match {
      |    case abc @ @@ =>
      |  }
      |}""".stripMargin,
    """|None scala
       |Some(value) scala
       |""".stripMargin,
    topLines = Some(2)
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
    topLines = Some(1)
  )
  check(
    "wildcard",
    """
      |object A {
      |  Option(1) match {
      |    case _: @@ =>
      |  }
      |}""".stripMargin,
    """|None scala
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|Some[?] scala
           |""".stripMargin
    ),
    topLines = Some(1)
  )
  check(
    "wildcard-ident",
    """
      |object A {
      |  Option(1) match {
      |    case _: S@@ =>
      |  }
      |}""".stripMargin,
    """|Some[_] scala
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "typed-bind",
    """
      |object A {
      |  Option(1) match {
      |    case ab: @@ =>
      |  }
      |}""".stripMargin,
    """|None scala
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|Some[?] scala
           |""".stripMargin
    ),
    topLines = Some(1)
  )

  check(
    "typed-bind-ident",
    """
      |object A {
      |  Option(1) match {
      |    case ab: S@@ =>
      |  }
      |}""".stripMargin,
    """|Some[_] scala
       |""".stripMargin,
    topLines = Some(1)
  )
}
