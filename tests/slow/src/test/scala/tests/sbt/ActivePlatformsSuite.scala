package tests.sbt

import scala.meta.internal.metals.{BuildInfo => V}

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import tests.BaseLspSuite
import tests.BloopImportInitializer
import tests.SbtBuildLayout

class ActivePlatformsSuite
    extends BaseLspSuite("active-platforms", BloopImportInitializer) {

  private val crossProjectLayout: String =
    s"""|/project/build.properties
        |sbt.version=${V.sbtVersion}
        |/project/plugins.sbt
        |addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.20.2")
        |addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.9")
        |/build.sbt
        |${SbtBuildLayout.commonSbtSettings}
        |ThisBuild / scalaVersion := "2.13.17"
        |
        |lazy val jvmProject = project.in(file("jvm"))
        |lazy val jsProject = project.in(file("js")).enablePlugins(ScalaJSPlugin)
        |lazy val nativeProject = project.in(file("native")).enablePlugins(ScalaNativePlugin)
        |
        |/jvm/src/main/scala/JvmMain.scala
        |object JvmMain { val x = 1 }
        |/js/src/main/scala/JsMain.scala
        |object JsMain { val y = 2 }
        |/native/src/main/scala/NativeMain.scala
        |object NativeMain { val z = 3 }
        |""".stripMargin

  private def getPlatformStatus: String = {
    val buildTargets = server.server.buildTargets
    def platforms(ids: Seq[_]): String =
      ids
        .flatMap {
          case id: BuildTargetIdentifier =>
            buildTargets.scalaTarget(id).map(_.scalaPlatform.toString)
          case _ => None
        }
        .toSet
        .toList
        .sorted
        .mkString(", ")
    val all = platforms(buildTargets.allBuildTargetIds)
    val active = platforms(buildTargets.activeBuildTargetIds)
    s"all: $all; active: $active"
  }

  test("active-platforms") {
    cleanWorkspace()
    for {
      _ <- initialize(crossProjectLayout)
      _ = assertNoDiff(getPlatformStatus, "all: JS, JVM, NATIVE; active: JVM")

      _ <- server.didOpen("js/src/main/scala/JsMain.scala")
      _ = assertNoDiff(
        getPlatformStatus,
        "all: JS, JVM, NATIVE; active: JS, JVM",
      )

      _ = server.server.buildTargets.resetActivePlatforms()
      _ = assertNoDiff(getPlatformStatus, "all: JS, JVM, NATIVE; active: JVM")

      _ <- server.didOpen("native/src/main/scala/NativeMain.scala")
      _ = assertNoDiff(
        getPlatformStatus,
        "all: JS, JVM, NATIVE; active: JVM, NATIVE",
      )
    } yield ()
  }
}
