package tests

import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}

import ch.epfl.scala.bsp4j.StatusCode

class CancelCompileSuite
    extends BaseLspSuite("compile-cancel", SbtServerInitializer) {

  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(loglevel = "debug")

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/project/build.properties
           |sbt.version=${V.sbtVersion}
           |/build.sbt
           |${SbtBuildLayout.commonSbtSettings}
           |ThisBuild / scalaVersion := "${BuildInfo.scalaVersion}"
           |val a = project.in(file("a")).settings(
           |    libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value
           |  )
           |val b = project.in(file("b")).dependsOn(a)
           |val c = project.in(file("c")).dependsOn(b)
           |/a/src/main/scala/a/A.scala
           |package a
           |
           |import scala.reflect.macros.blackbox.Context
           |import scala.language.experimental.macros
           |
           |object A {
           |  val x = 123
           |  def sleep(): Unit = macro sleepImpl
           |  def sleepImpl(c: Context)(): c.Expr[Unit] = {
           |    import c.universe._
           |    // Sleep for 3 seconds
           |    Thread.sleep(3000)
           |    reify { () }
           |  }
           |}
           |
           |/b/src/main/scala/b/B.scala
           |package b
           |object B {
           |  a.A.sleep()
           |  val x = a.A.x 
           |}
           |/c/src/main/scala/c/C.scala
           |package c
           |object C { val x: String = b.B.x }
           |""".stripMargin
      )
      _ <- server.server.buildServerPromise.future
      (compileReport, _) <- server.server.compilations
        .compileFile(workspace.resolve("c/src/main/scala/c/C.scala"))
        .zip {
          // wait until the compilation start
          Thread.sleep(1000)
          server.executeCommand(ServerCommands.CancelCompile)
        }
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertEquals(compileReport.getStatusCode(), StatusCode.CANCELLED)
      _ <- server.server.compilations.compileFile(
        workspace.resolve("c/src/main/scala/c/C.scala")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|c/src/main/scala/c/C.scala:2:28: error: type mismatch;
           | found   : Int
           | required: String
           |object C { val x: String = b.B.x }
           |                           ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
