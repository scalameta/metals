package tests.sbt

import scala.meta.internal.metals.{BuildInfo => V}

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
        |object JsMain { val y: String = 2 }
        |/native/src/main/scala/NativeMain.scala
        |object NativeMain { val z: String = 3 }
        |""".stripMargin

  test("js-not-compiled-by-default") {
    cleanWorkspace()
    for {
      _ <- initialize(crossProjectLayout)
      _ = assertNoDiff(client.workspaceDiagnostics, "")

      _ <- server.didOpen("js/src/main/scala/JsMain.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|js/src/main/scala/JsMain.scala:1:33: error: type mismatch;
           | found   : Int(2)
           | required: String
           |object JsMain { val y: String = 2 }
           |                                ^
           |""".stripMargin,
      )
    } yield ()
  }

  test("native-not-compiled-by-default") {
    cleanWorkspace()
    for {
      _ <- initialize(crossProjectLayout)
      _ = assertNoDiff(client.workspaceDiagnostics, "")

      _ <- server.didOpen("native/src/main/scala/NativeMain.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|native/src/main/scala/NativeMain.scala:1:37: error: type mismatch;
           | found   : Int(3)
           | required: String
           |object NativeMain { val z: String = 3 }
           |                                    ^
           |""".stripMargin,
      )
    } yield ()
  }
}
