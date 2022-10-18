package tests.pc

import tests.BaseCompletionSuite

class CompletionSbtLibSuite extends BaseCompletionSuite {

  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala3)

  check(
    "source",
    """|val dependency = "io.cir@@" % 
       |""".stripMargin,
    """|io.circe
       |""".stripMargin,
    filename = "A.sbt",
  )

  check(
    "single-percent",
    """|val dependency = "io.circe" % "circe-core_na@@"
       |""".stripMargin,
    """|circe-core_native0.4_2.12
       |circe-core_native0.4_2.13
       |circe-core_native0.4_3
       |""".stripMargin,
    filename = "A.sbt",
  )

  check(
    "double-percent",
    """|val dependency = "io.circe" %% "circe-core@@"
       |""".stripMargin,
    """|circe-core
       |circe-core_native0.4
       |circe-core_sjs0.6
       |circe-core_sjs1
       |circe-core_sjs1.0-RC2
       |""".stripMargin,
    filename = "A.sbt",
  )

  check(
    "version",
    """|val dependency = "io.circe" %% "circe-core_native0.4" % "@@"
       |""".stripMargin,
    """|0.14.3
       |""".stripMargin,
    filename = "A.sbt",
  )

  checkEdit(
    "double-percent-edit",
    """|val dependency = "io.circe" %% "circe-core_n@@"
       |""".stripMargin,
    """|val dependency = "io.circe" %% "circe-core_native0.4"
       |""".stripMargin,
    filename = "A.sbt",
  )
}
