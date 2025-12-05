package scala.meta.internal.builds.tests

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.metals.UserConfiguration

import tests._

class MillVersionSuite extends BaseSuite {

  def check(
      layout: String,
      expected: String,
  ): Unit = {
    test(expected) {
      val root = FileLayout.fromString(layout)
      val obtained =
        MillBuildTool(() => UserConfiguration(), root).getMillVersion(root)
      assertEquals(obtained, expected)
    }
  }

  check(
    """|.mill-version
       |0.10.2
       |""".stripMargin,
    "0.10.2",
  )

  check(
    """|.mill-version
       |0.12.6-native
       |""".stripMargin,
    "0.12.6",
  )

  check(
    """|.config/mill-version
       |0.11.1
       |""".stripMargin,
    "0.11.1",
  )

  check(
    """|build.mill
       |//| mill-version: 0.13.0-M1-jvm
       |""".stripMargin,
    "0.13.0-M1",
  )

  check(
    """|build.mill.yaml
       |mill-version: 1.1.0-RC2
       |""".stripMargin,
    "1.1.0-RC2",
  )

  check(
    """|mill
       |#!/usr/bin/env sh
       |
       |# This is a wrapper script, that automatically download mill from GitHub release pages
       |# You can give the required mill version with --mill-version parameter
       |# If no version is given, it falls back to the value of DEFAULT_MILL_VERSION
       |#
       |# Project page: https://github.com/lefou/millw
       |# Script Version: 0.4.0
       |#
       |# If you want to improve this script, please also contribute your changes back!
       |#
       |# Licensed under the Apache License, Version 2.0
       |
       |
       |DEFAULT_MILL_VERSION=0.9.12
       |""".stripMargin,
    "0.9.12",
  )

}
