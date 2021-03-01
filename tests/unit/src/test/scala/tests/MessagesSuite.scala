package tests

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.{BuildInfo => V}

class MessagesSuite extends BaseSuite {

  test("deprecated-single") {
    assertDiffEqual(
      Messages.DeprecatedScalaVersion.message(Set("2.13.5")),
      "You are using legacy Scala version 2.13.5, which might not be supported in future versions of Metals." +
        s" Please upgrade to Scala version ${V.scala212}."
    )
  }

  test("future-single") {
    assertDiffEqual(
      Messages.FutureScalaVersion.message(Set("2.13.50")),
      "You are using Scala version 2.13.50, which is not yet supported in this version of Metals." +
        s" Please downgrade to Scala version ${V.scala213} for the moment until the new Metals release."
    )
  }

  test("unspported-single") {
    assertDiffEqual(
      Messages.UnsupportedScalaVersion.message(Set("2.12.4")),
      "You are using Scala version 2.12.4, which is not supported in this version of Metals." +
        s" Please upgrade to Scala version ${V.scala212}."
    )
  }

  test("unspported-multiple") {
    assertDiffEqual(
      Messages.UnsupportedScalaVersion.message(Set("2.12.4", "2.12.8")),
      "You are using Scala versions 2.12.4, 2.12.8, which are not supported in this version of Metals." +
        s" Please upgrade to Scala version ${V.scala212}."
    )
  }

  test("unspported-multiple2") {
    assertDiffEqual(
      Messages.UnsupportedScalaVersion
        .message(Set("2.11.9", "2.12.2", "2.12.1")),
      "You are using Scala versions 2.11.9, 2.12.1, 2.12.2, which are not supported in this version of Metals." +
        s" Please upgrade to Scala version ${V.scala212} or alternatively to legacy Scala ${V.scala211}."
    )
  }
}
