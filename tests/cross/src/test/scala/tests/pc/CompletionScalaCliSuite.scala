package tests.pc

import tests.BaseCompletionSuite

class CompletionScalaCliSuite extends BaseCompletionSuite {
  check(
    "simple",
    """|//> using lib "io.cir@@
       |package A
       |""".stripMargin,
    "io.circe",
  )

  checkEdit(
    "multiple-deps",
    """|// Multiple using lib
       |//> using lib ???
       |// //> using lib ???
       |//> using lib io.circe::circe-core_na@@
       |package A
       |""".stripMargin,
    """|// Multiple using lib
       |//> using lib ???
       |// //> using lib ???
       |//> using lib io.circe::circe-core_native0.4
       |package A
       |""".stripMargin,
  )

  check(
    "single-colon",
    """|//> using lib "io.circe:circe-core_na@@
       |package A
       |""".stripMargin,
    """|circe-core_native0.4_2.12
       |circe-core_native0.4_2.13
       |circe-core_native0.4_3
       |""".stripMargin,
  )

  check(
    "version",
    """|//> using lib "io.circe::circe-core_native0.4:@@"
       |package A
       |""".stripMargin,
    "0.14.3",
  )

  check(
    "multiple-libs",
    """|//> using lib "io.circe::circe-core:0.14.0", "io.circe::circe-core_na@@"
       |package A
       |""".stripMargin,
    "circe-core_native0.4",
  )

  check(
    "script",
    scriptWrapper(
      """|//> using lib "io.circe:circe-core_na@@
         |
         |""".stripMargin,
      "script.sc.scala",
    ),
    """|circe-core_native0.4_2.12
       |circe-core_native0.4_2.13
       |circe-core_native0.4_3
       |""".stripMargin,
    filename = "script.sc.scala",
    enablePackageWrap = false,
  )

  check(
    "closing-quote",
    """|//> using lib "io.circe::circe-core:0.14.0"@@
       |package A
       |""".stripMargin,
    "",
  )

  check(
    "whitespace",
    """|//> using lib "io.circe::circe-co @@
       |package A
       |""".stripMargin,
    "",
  )

  check(
    "plugin".tag(
      IgnoreScalaVersion(version =>
        Set("2.12.12", "2.12.16", "3.2.1")(version) ||
          version.contains(
            "NIGHTLY"
          ) || version.contains(
            "-RC"
          ) || version.contains(
            "-bin-"
          )
      )
    ),
    """|//> using plugin "org.polyvariant:::@@
       |package A
       |""".stripMargin,
    "better-tostring",
  )

  checkEdit(
    "alternative-sorting",
    """|//> using lib "co.fs2::fs2-core:@@"
       |package A
       |""".stripMargin,
    """|//> using lib "co.fs2::fs2-core:3.4.0"
       |package A
       |""".stripMargin,
    filter = _.startsWith("3.4"),
  )

  private def scriptWrapper(code: String, filename: String): String =
    // Vaguely looks like a scala file that ScalaCLI generates
    // from a sc file.
    s"""|
        |object ${filename.stripSuffix(".sc.scala")} {
        |/*<script>*/${code}
        |}
        |""".stripMargin

}
