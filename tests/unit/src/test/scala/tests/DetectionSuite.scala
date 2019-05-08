package tests
import scala.meta.internal.builds.BuildTools
import scala.meta.io.AbsolutePath

object DetectionSuite extends BaseSuite {

  def check(
      layout: String,
      testFunction: AbsolutePath => Boolean,
      isTrue: Boolean = true
  ) = {
    val workspace = FileLayout.fromString(layout)
    workspace.toFile.deleteOnExit()
    val isSbt = testFunction(workspace)
    if (isTrue) assert(isSbt)
    else assert(!isSbt)
  }

  /**------------ SBT ------------**/
  def checkNotSbt(name: String, layout: String): Unit = {
    checkSbt(name, layout, isTrue = false)
  }
  def checkSbt(name: String, layout: String, isTrue: Boolean = true): Unit = {
    test(s"sbt-$name") {
      check(layout, new BuildTools(_, Nil).isSbt, isTrue)
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

  /**------------ Gradle ------------**/
  def checkNotGradle(name: String, layout: String): Unit = {
    checkGradle(name, layout, isTrue = false)
  }
  def checkGradle(
      name: String,
      layout: String,
      isTrue: Boolean = true
  ): Unit = {
    test(s"gradle-$name") {
      check(layout, new BuildTools(_, Nil).isGradle, isTrue)
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

  /**------------ Maven ------------**/
  def checkNotMaven(name: String, layout: String): Unit = {
    checkMaven(name, layout, isTrue = false)
  }
  def checkMaven(
      name: String,
      layout: String,
      isTrue: Boolean = true
  ): Unit = {
    test(s"maven-$name") {
      check(layout, new BuildTools(_, Nil).isMaven, isTrue)
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
}
