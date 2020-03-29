package tests

import scala.meta.internal.metals.Messages

class MessagesSuite extends BaseSuite {

  test("deprecated-single") {
    assertDiffEqual(
      Messages.DeprecatedScalaVersion.message(Seq("2.11.12")),
      "You are using legacy Scala version 2.11.12, which might not be supported in future versions of Metals." +
        " Please upgrade to Scala version 2.12.11."
    )
  }

  test("future-single") {
    assertDiffEqual(
      Messages.FutureScalaVersion.message(Seq("2.13.50")),
      "You are using Scala version 2.13.50, which is not yet supported in this version of Metals." +
        " Please downgrade to Scala version 2.13.1 for the moment until the new Metals release."
    )
  }

  test("unspported-single") {
    assertDiffEqual(
      Messages.UnsupportedScalaVersion.message(Seq("2.12.4")),
      "You are using Scala version 2.12.4, which is not supported in this version of Metals." +
        " Please upgrade to Scala version 2.12.11."
    )
  }

  test("unspported-multiple") {
    assertDiffEqual(
      Messages.UnsupportedScalaVersion.message(Seq("2.12.4", "2.12.8")),
      "You are using Scala versions 2.12.4, 2.12.8, which are not supported in this version of Metals." +
        " Please upgrade to Scala version 2.12.11."
    )
  }

  test("unspported-multiple2") {
    assertDiffEqual(
      Messages.UnsupportedScalaVersion
        .message(Seq("2.11.9", "2.12.2", "2.12.1")),
      "You are using Scala versions 2.11.9, 2.12.1, 2.12.2, which are not supported in this version of Metals." +
        " Please upgrade to Scala version 2.12.11 or alternatively to legacy Scala 2.11.12."
    )
  }
}
