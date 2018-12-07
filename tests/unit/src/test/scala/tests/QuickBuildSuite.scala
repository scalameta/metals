package tests

object QuickBuildSuite extends BaseSlowSuite("quick-build") {
  testAsync("basic") {
    cleanCompileCache("b")
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {
          |    "libraryDependencies": [
          |      "com.geirsson::coursier-small:1.2.0"
          |    ]
          |  },
          |  "b": {
          |    "scalacOptions": [ "-Ywarn-unused-import" ],
          |    "libraryDependencies": [
          |      "org.scalatest::scalatest:3.0.5"
          |    ],
          |    "dependsOn": [ "a" ]
          |  }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |import com.geirsson.coursiersmall._
          |import scala.util.Success
          |object A {
          |  val settings = new Settings()
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |import a._
          |import scala.util.Success
          |import org.scalatest._
          |class B extends FunSuite {
          |  test("") {
          |    println(A.settings)
          |  }
          |}
        """.stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |b/src/main/scala/b/B.scala:3:19: warning: Unused import
          |import scala.util.Success
          |                  ^^^^^^^
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/A.scala
           |package a
           |import com.geirsson.coursiersmall._
           |import scala.util.Success/*Try.scala*/
           |object A/*L3*/ {
           |  val settings/*L4*/ = new Settings/*Settings.scala*/()
           |}
           |/b/src/main/scala/b/B.scala
           |package b
           |import a._
           |import scala.util.Success/*Try.scala*/
           |import org.scalatest._
           |class B/*L4*/ extends FunSuite/*FunSuite.scala*/ {
           |  test/*FunSuiteLike.scala*/("") {
           |    println/*Predef.scala*/(A/*A.scala:3*/.settings/*A.scala:4*/)
           |  }
           |}
           |""".stripMargin
      )
    } yield ()
  }
}
