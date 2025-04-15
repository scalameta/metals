package tests.rangeFormatting

import tests.BaseRangeFormatterSuite

class ScalaCliDependencyRangeFormatterPastingSuite
    extends BaseRangeFormatterSuite("MillifyRangeFormatting") {

  check(
    "change-dep-format-on-paste",
    s"""
       |//> using dep @@
       |object Main {
       |  println("hello")
       |}""".stripMargin,
    s"""|"org.scalameta" %% "munit" % "0.7.26"""".stripMargin,
    s"""
       |//> using dep org.scalameta::munit:0.7.26
       |object Main {
       |  println("hello")
       |}""".stripMargin,
  )

  check(
    "change-test-dep-format-on-paste",
    s"""
       |//> using test.dep @@
       |object Main {
       |  println("hello")
       |}""".stripMargin,
    s"""|"org.scalameta" %% "munit" % "0.7.26"""".stripMargin,
    s"""
       |//> using test.dep org.scalameta::munit:0.7.26
       |object Main {
       |  println("hello")
       |}""".stripMargin,
  )

  check(
    "change-no-dep-format-test-configuration",
    s"""
       |//> using @@
       |object Main {
       |  println("hello")
       |}""".stripMargin,
    s"""|"org.scalameta" %% "munit" % "1.0.4" % "test""""".stripMargin,
    s"""
       |//> using test.dep org.scalameta::munit:1.0.4
       |object Main {
       |  println("hello")
       |}""".stripMargin,
  )

  check(
    "change-no-dep-format-test-configuration-configuration",
    s"""
       |//> using @@
       |object Main {
       |  println("hello")
       |}""".stripMargin,
    s"""|"org.scalameta" %% "munit" % "1.0.4" % Test""".stripMargin,
    s"""
       |//> using test.dep org.scalameta::munit:1.0.4
       |object Main {
       |  println("hello")
       |}""".stripMargin,
  )

  check(
    "change-no-dep-format-within-existing-deps",
    s"""
       |//> using dep com.lihaoyi::utest::0.7.10
       |//> using @@
       |//> using dep com.lihaoyi::pprint::0.6.6
       |object Main {
       |  println("hello")
       |}""".stripMargin,
    s"""|"org.scalameta" %% "munit" % "0.7.26"""".stripMargin,
    s"""
       |//> using dep com.lihaoyi::utest::0.7.10
       |//> using dep org.scalameta::munit:0.7.26
       |//> using dep com.lihaoyi::pprint::0.6.6
       |object Main {
       |  println("hello")
       |}""".stripMargin,
  )

  check(
    "change-dep-format-within-existing-deps",
    s"""
       |//> using dep com.lihaoyi::utest::0.7.10
       |//> using dep @@
       |//> using dep com.lihaoyi::pprint::0.6.6
       |object Main {
       |  println("hello")
       |}""".stripMargin,
    s"""|"org.scalameta" %% "munit" % "0.7.26"""".stripMargin,
    s"""
       |//> using dep com.lihaoyi::utest::0.7.10
       |//> using dep org.scalameta::munit:0.7.26
       |//> using dep com.lihaoyi::pprint::0.6.6
       |object Main {
       |  println("hello")
       |}""".stripMargin,
  )

  check(
    "not-change-format-outside-using-directive",
    s"""
       |//> using dep com.lihaoyi::utest::0.7.10
       |//> using dep com.lihaoyi::pprint::0.6.6
       |// TODO: @@
       |object Main {
       |  println("hello")
       |}""".stripMargin,
    s"""|"org.scalameta" %% "munit" % "0.7.26"""".stripMargin,
    s"""
       |//> using dep com.lihaoyi::utest::0.7.10
       |//> using dep com.lihaoyi::pprint::0.6.6
       |// TODO: "org.scalameta" %% "munit" % "0.7.26"
       |object Main {
       |  println("hello")
       |}""".stripMargin,
  )

}
