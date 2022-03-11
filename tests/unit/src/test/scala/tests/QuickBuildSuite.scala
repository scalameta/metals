package tests

import scala.meta.internal.metals.InitializationOptions

class QuickBuildSuite extends BaseLspSuite(s"quick-build") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  test("basic", withoutVirtualDocs = true) {
    cleanCompileCache("b")
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {
          |    "libraryDependencies": [
          |      "io.get-coursier:interface:1.0.6"
          |    ]
          |  },
          |  "b": {
          |    "scalacOptions": [ "-Wunused" ],
          |    "libraryDependencies": [
          |      "org.scalatest::scalatest:3.2.4"
          |    ],
          |    "dependsOn": [ "a" ]
          |  }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |import scala.util.Success
          |object A {
          |  val creds = coursierapi.Credentials.of("foo", "bar")
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |import a._
          |import scala.util.Success
          |import org.scalatest.funsuite._
          |class B extends AnyFunSuite {
          |  test("") {
          |    println(A.creds)
          |  }
          |}
        """.stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/B.scala:3:1: warning: Unused import
           |import scala.util.Success
           |^^^^^^^^^^^^^^^^^^^^^^^^^
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/A.scala
           |package a
           |import scala.util.Success/*Try.scala*/
           |object A/*L2*/ {
           |  val creds/*L3*/ = coursierapi.Credentials/*Credentials.java*/.of/*Credentials.java*/("foo", "bar")
           |}
           |/b/src/main/scala/b/B.scala
           |package b
           |import a._
           |import scala.util.Success/*Try.scala*/
           |import org.scalatest.funsuite._
           |class B/*L4*/ extends AnyFunSuite/*AnyFunSuite.scala*/ {
           |  test/*AnyFunSuiteLike.scala*/("") {
           |    println/*Predef.scala*/(A/*A.scala:2*/.creds/*A.scala:3*/)
           |  }
           |}
           |""".stripMargin
      )
    } yield ()
  }
}
