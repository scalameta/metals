package tests

import scala.meta.internal.metals.BuildTools

object SbtDetectionSuite extends BaseSuite {
  def checkNotSbt(name: String, layout: String): Unit = {
    checkSbt(name, layout, isTrue = false)
  }
  def checkSbt(name: String, layout: String, isTrue: Boolean = true): Unit = {
    test(name) {
      val workspace = FileLayout.fromString(layout)
      workspace.toFile.deleteOnExit()
      val isSbt = new BuildTools(workspace, Nil).isSbt
      if (isTrue) assert(isSbt)
      else assert(!isSbt)
    }
  }

  checkSbt(
    "build.sbt",
    """|/build.sbt
       |lazy val a = project
       |""".stripMargin
  )

  checkSbt(
    "build.scala",
    """|/project/build.properties
       |sbt.version = 0.13
       |/project/build.scala
       |import sbt._
       |""".stripMargin
  )

  checkSbt(
    "sbt.version",
    """|/project/build.properties
       |sbt.version = 0.13
       |""".stripMargin
  )

  checkNotSbt(
    "no-properties",
    """|/project/build.scala
       |import sbt._
       |/project/plugins.sbt
       |addSbtPlugin(plugin)
       |""".stripMargin
  )

  checkNotSbt(
    "mill",
    """|/mill.sc
       |import mill._
       |""".stripMargin
  )

  checkNotSbt(
    "gradle",
    """|/build.gradle
       |import gradle._
       |""".stripMargin
  )

}
