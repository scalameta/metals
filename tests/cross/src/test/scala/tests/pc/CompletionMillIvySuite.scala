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
       |""".stripMargin,
    filename = "build.sc",
  )

  check(
    "java-completions",
    """|val dependency = ivy"io.circe:circe-core_na@@"
       |""".stripMargin,
    """|circe-core_native0.4_2.12
       |circe-core_native0.4_2.13
       |circe-core_native0.4_3
       |""".stripMargin,
    filename = "build.sc",
  )

  check(
    "scala-completions",
    """|val dependency = ivy"io.circe::circe-core@@"
       |""".stripMargin,
    """|circe-core
       |circe-core_native0.4
       |circe-core_sjs0.6
       |circe-core_sjs1
       |circe-core_sjs1.0-RC2
       |""".stripMargin,
    filename = "build.sc",
  )

  check(
    "version",
    """|val dependency = ivy"io.circe::circe-core_native0.4:@@"
       |""".stripMargin,
    """|0.14.3
       |""".stripMargin,
    filename = "build.sc",
  )
}
