package tests.pc

import tests.BaseCompletionSuite

class CompletionScalaCliSuite extends BaseCompletionSuite {

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala211
  )

  check(
    "simple",
    """|//> using lib "io.cir@@
       |package A
       |""".stripMargin,
    """|io.circe
       |io.circul""".stripMargin
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
    assertSingleItem = false,
    itemIndex = 0
  )

  check(
    "single-colon",
    """|//> using lib "io.circe:circe-core_na@@
       |package A
       |""".stripMargin,
    """|circe-core_native0.4_2.12
       |circe-core_native0.4_2.13
       |circe-core_native0.4_3
       |circe-core_native0.5_2.12
       |circe-core_native0.5_2.13
       |circe-core_native0.5_3
       |""".stripMargin
  )

  check(
    "version",
    """|//> using lib "io.circe::circe-core_sjs1:0.14.10@@"
       |package A
       |""".stripMargin,
    "0.14.10"
  )

  // We don't to add `::` before version if `sjs1` is specified
  checkEdit(
    "version-edit",
    """|//> using lib "io.circe::circe-core_sjs1:0.14.10@@"
       |package A
       |""".stripMargin,
    """|//> using lib "io.circe::circe-core_sjs1:0.14.10"
       |package A
       |""".stripMargin
  )

  check(
    "multiple-libs",
    """|//> using lib "io.circe::circe-core:0.14.0", "io.circe::circe-core_na@@"
       |package A
       |""".stripMargin,
    """|circe-core_native0.4
       |circe-core_native0.5""".stripMargin
  )

  check(
    "script",
    scriptWrapper(
      """|//> using lib "io.circe:circe-core_na@@
         |
         |""".stripMargin,
      "script.sc.scala"
    ),
    """|circe-core_native0.4_2.12
       |circe-core_native0.4_2.13
       |circe-core_native0.4_3
       |circe-core_native0.5_2.12
       |circe-core_native0.5_2.13
       |circe-core_native0.5_3
       |""".stripMargin,
    filename = "script.sc.scala",
    enablePackageWrap = false
  )

  check(
    "closing-quote",
    """|//> using lib "io.circe::circe-core:0.14.0"@@
       |package A
       |""".stripMargin,
    ""
  )

  check(
    "whitespace",
    """|//> using lib "io.circe::circe-co @@
       |package A
       |""".stripMargin,
    ""
  )

  check(
    "plugin".tag(
      IgnoreScalaVersion(version =>
        Set("2.12.16", "2.13.15")(version) ||
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
    "better-tostring"
  )

  checkEdit(
    "alternative-sorting",
    """|//> using lib "co.fs2::fs2-core:@@"
       |package A
       |""".stripMargin,
    """|//> using lib "co.fs2::fs2-core::3.4.0"
       |package A
       |""".stripMargin,
    filter = _.startsWith("3.4")
  )

  check(
    "dep",
    """|//> using dep "io.cir@@
       |package A
       |""".stripMargin,
    """|io.circe
       |io.circul""".stripMargin
  )

  check(
    "multiple-deps",
    """|//> using libs "io.circe::circe-core:0.14.0", "io.circe::circe-core_na@@"
       |package A
       |""".stripMargin,
    """|circe-core_native0.4
       |circe-core_native0.5""".stripMargin
  )

  check(
    "version-sort",
    """|//> using dep "com.lihaoyi::pprint:0.7@@"
       |package A
       |""".stripMargin,
    """|0.7.3
       |0.7.2
       |0.7.1
       |0.7.0
       |""".stripMargin
  )

  check(
    "version-double-colon",
    """|//> using lib "com.outr::scribe-cats::@@"
       |package A
       |""".stripMargin,
    """|3.7.1
       |3.7.0
       |""".stripMargin,
    filter = _.startsWith("3.7")
  )

  checkEdit(
    "version-double-colon-edit",
    """|//> using lib "com.outr::scribe-cats::@@"
       |package A
       |""".stripMargin,
    """|//> using lib "com.outr::scribe-cats::3.7.1"
       |package A
       |""".stripMargin,
    filter = _.startsWith("3.7.1")
  )

  check(
    "version-double-colon2",
    """|//> using lib "com.outr::scribe-cats::3.7@@"
       |package A
       |""".stripMargin,
    """|3.7.1
       |3.7.0
       |""".stripMargin
  )

  checkEdit(
    "version-double-colon-edit2",
    """|//> using lib "com.outr::scribe-cats::3.7@@"
       |package A
       |""".stripMargin,
    """|//> using lib "com.outr::scribe-cats::3.7.1"
       |package A
       |""".stripMargin,
    filter = _.startsWith("3.7.1")
  )

  check(
    "version-double-colon3",
    """|//> using lib "com.outr:scribe-cats_3::@@"
       |package A
       |""".stripMargin,
    ""
  )

  checkEdit(
    "version-double-colon-edit2",
    """|//> using lib "com.outr:scribe-cats_3:3.7@@"
       |package A
       |""".stripMargin,
    """|//> using lib "com.outr:scribe-cats_3:3.7.1"
       |package A
       |""".stripMargin,
    filter = _.startsWith("3.7.1")
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
