package tests

import scala.meta.internal.builds.BuildTools
import scala.meta.io.AbsolutePath

import munit.Location

class DetectionSuite extends BaseSuite {

  def check(
      layout: String,
      testFunction: AbsolutePath => Boolean,
      isTrue: Boolean = true
  )(implicit loc: Location): Unit = {
    val workspace = FileLayout.fromString(layout)
    workspace.toFile.deleteOnExit()
    val isSbt = testFunction(workspace)
    if (isTrue) assert(isSbt)
    else assert(!isSbt)
  }

  /**
   * ------------ SBT ------------* */
  def checkNotSbt(name: String, layout: String)(implicit
      loc: Location
  ): Unit = {
    checkSbt(name, layout, isTrue = false)
  }

  def checkSbt(name: String, layout: String, isTrue: Boolean = true)(implicit
      loc: Location
  ): Unit = {
    test(s"sbt-$name") {
      check(
        layout,
        p => BuildTools.default(p).isSbt,
        isTrue
      )
    }
  }

  def checkPants(name: String, layout: String, isTrue: Boolean = true)(implicit
      loc: Location
  ): Unit = {
    test(s"pants-$name") {
      check(
        layout,
        p => BuildTools.default(p).isPants,
        isTrue
      )
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

  checkPants(
    "pants.ini",
    """|/pants.ini
       |[scala]
       |version: custom
       |suffix_version: 2.12
       |""".stripMargin
  )

  checkNotSbt(
    "pants.ini",
    """|/pants.ini
       |[scala]
       |version: custom
       |suffix_version: 2.12
       |""".stripMargin
  )

  /**
   * ------------ Gradle ------------* */
  def checkNotGradle(name: String, layout: String)(implicit
      loc: Location
  ): Unit = {
    checkGradle(name, layout, isTrue = false)
  }
  def checkGradle(
      name: String,
      layout: String,
      isTrue: Boolean = true
  )(implicit loc: Location): Unit = {
    test(s"gradle-$name") {
      check(
        layout,
        p => BuildTools.default(p).isGradle,
        isTrue
      )
    }
  }

  checkNotGradle(
    "build.sbt",
    """|/build.sbt
       |lazy val a = project
       |""".stripMargin
  )

  checkNotGradle(
    "mill",
    """|/mill.sc
       |import mill._
       |""".stripMargin
  )

  checkGradle(
    "build.gradle",
    """|/build.gradle
       |project.name = 'test'
       |""".stripMargin
  )

  checkGradle(
    "build.gradle.kts",
    """|/build.gradle.kts
       |project.ext['version'] = '123'
       |""".stripMargin
  )

  /**
   * ------------ Maven ------------* */
  def checkNotMaven(name: String, layout: String)(implicit
      loc: Location
  ): Unit = {
    checkMaven(name, layout, isTrue = false)
  }
  def checkMaven(
      name: String,
      layout: String,
      isTrue: Boolean = true
  )(implicit loc: Location): Unit = {
    test(s"maven-$name") {
      check(
        layout,
        p => BuildTools.default(p).isMaven,
        isTrue
      )
    }
  }

  checkNotMaven(
    "build.sbt",
    """|/build.sbt
       |lazy val a = project
       |""".stripMargin
  )

  checkNotMaven(
    "mill",
    """|/mill.sc
       |import mill._
       |""".stripMargin
  )

  checkMaven(
    "pom.xml",
    """|/pom.xml
       |<project>
       |  <modelVersion>4.0.0</modelVersion>
       |  <groupId>com.mycompany.app</groupId>
       |  <artifactId>my-app</artifactId>
       |  <version>1</version>
       |</project>
       |""".stripMargin
  )

  /**
   * ------------ Multiple Build Files ------------* */
  def checkMulti(name: String, layout: String, isTrue: Boolean = true)(implicit
      loc: Location
  ): Unit = {
    test(s"sbt-$name") {
      check(
        layout,
        p => {
          val bt = BuildTools.default(p)
          bt.isSbt && bt.isMill
        },
        isTrue
      )
    }
  }

  checkMulti(
    "sbt-and-mill",
    """|/build.sbt
       |lazy val a = project
       |/build.sc
       |import mill._
       |""".stripMargin
  )
}
