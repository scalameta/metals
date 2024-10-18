package tests.pc

import tests.BaseCompletionSuite

class CompletionMillIvySuite extends BaseCompletionSuite {

  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala3)

  check(
    "source",
    """|val dependency = ivy"io.cir@@"
       |""".stripMargin,
    """|io.circe
       |io.circul
       |""".stripMargin,
    filename = "build.sc"
  )

  check(
    "source",
    """|val dependency = ivy"io.cir@@"
       |""".stripMargin,
    """|io.circe
       |io.circul
       |""".stripMargin,
    filename = "build.sc"
  )

  check(
    "source",
    """|package build
       |object `package` extends RootModule {
       |  val dependency = ivy"io.cir@@"
       |}
       |""".stripMargin,
    """|io.circe
       |io.circul
       |""".stripMargin,
    filename = "build.mill.scala"
  )

  check(
    "source",
    """|package build
       |object `package` extends RootModule {
       |  val dependency = ivy"io.cir@@"
       |}
       |""".stripMargin,
    """|io.circe
       |io.circul
       |""".stripMargin,
    filename = "build.mill"
  )

  check(
    "source",
    """|package build
       |val dependency = ivy"io.cir@@"
       |""".stripMargin,
    """|io.circe
       |io.circul
       |""".stripMargin,
    filename = "build.mill"
  )

  check(
    "java-completions",
    """|val dependency = ivy"io.circe:circe-core_na@@"
       |""".stripMargin,
    """|circe-core_native0.4_2.12
       |circe-core_native0.4_2.13
       |circe-core_native0.4_3
       |circe-core_native0.5_2.12
       |circe-core_native0.5_2.13
       |circe-core_native0.5_3
       |""".stripMargin,
    filename = "build.sc"
  )

  checkEdit(
    "scala-completions-edit",
    """|val dependency = ivy"io.circe:@@"
       |""".stripMargin,
    """|val dependency = ivy"io.circe::circe-config"
       |""".stripMargin,
    filename = "build.sc",
    filter = _ == "circe-config"
  )

  check(
    "scala-completions".tag(IgnoreScala211),
    """|val dependency = ivy"io.circe::circe-core@@"
       |""".stripMargin,
    """|circe-core
       |circe-core_native0.4
       |circe-core_native0.5
       |circe-core_sjs0.6
       |circe-core_sjs1
       |circe-core_sjs1.0-RC2
       |""".stripMargin,
    filename = "build.sc"
  )

  check(
    "version".tag(IgnoreScala211),
    """|val dependency = ivy"io.circe::circe-core_sjs1:0.13@@"
       |""".stripMargin,
    """|0.13.0
       |""".stripMargin,
    filename = "build.sc"
  )

  check(
    "version2",
    """|val dependency = ivy"org.typelevel:cats-core_2.11:@@"
       |""".stripMargin,
    """|1.0.1
       |1.0.0
       |1.0.0-RC2
       |1.0.0-RC1
       |1.0.0-MF
       |""".stripMargin,
    filter = _.startsWith("1.0"),
    filename = "build.sc"
  )

  checkEdit(
    "version-no-double-colon-edit",
    """|val dependency = ivy"org.typelevel:cats-core_2.11:@@"
       |""".stripMargin,
    """|val dependency = ivy"org.typelevel:cats-core_2.11:1.0.1"
       |""".stripMargin,
    filter = _ == "1.0.1",
    filename = "build.sc"
  )

  check(
    "version-double-colon2".tag(IgnoreScala211),
    """|val dependency = ivy"org.typelevel::cats-core::2.10@@"
       |""".stripMargin,
    """|2.10.0
       |""".stripMargin,
    filename = "build.sc"
  )

  checkEdit(
    "version-double-colon-edit".tag(IgnoreScala211),
    """|val dependency = ivy"org.typelevel::cats-core::2.10@@"
       |""".stripMargin,
    """|val dependency = ivy"org.typelevel::cats-core::2.10.0"
       |""".stripMargin,
    filename = "build.sc"
  )

  check(
    "version-double-colon3",
    """|val dependency = ivy"org.typelevel:cats-core_2.11::1.0.@@"
       |""".stripMargin,
    "",
    filename = "build.sc"
  )

}
