package tests

import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._

import com.google.gson.Gson
import com.google.gson.GsonBuilder

class TestSuitesFinderSuite extends BaseLspSuite("testSuitesFinderSuite") {
  val gson: Gson =
    new GsonBuilder().setPrettyPrinting.disableHtmlEscaping().create()

  test("munit-test-suite") {
    for {
      _ <- Future.successful(cleanWorkspace())
      _ <- initialize(s"""|/metals.json
                          |{
                          |  "app": {
                          |    "libraryDependencies" : [ "org.scalameta::munit:0.7.29", "org.scalatest::scalatest:3.2.1" ],
                          |    "scalaVersion": "2.13.6"
                          |  }
                          |}
                          |/app/src/main/scala/foo/bar/MyTestSuite.scala
                          |package foo.bar
                          |abstract class AbstractTestSuite1 extends munit.FunSuite 
                          |
                          |class MunitTestSuite extends AbstractTestSuite1 {
                          |  test("foo") {}
                          |}
                          |""".stripMargin)
      discovered <- server.discoverTestSuites(
        "app/src/main/scala/foo/bar/MyTestSuite.scala"
      )
    } yield {
      val obtained = gson.toJson(discovered.asJava)
      val classUri = getUriFor("app/src/main/scala/foo/bar/MyTestSuite.scala")
      // build target identifier uri has format file:/ instead of file:///
      val targetUri =
        s"${workspace.toURI.toString}app/?id=app".replace("///", "/")
      val expected =
        s"""|[
            |  {
            |    "targetName": "app",
            |    "targetUri": "$targetUri",
            |    "discovered": [
            |      {
            |        "prefix": "foo",
            |        "children": [
            |          {
            |            "prefix": "bar",
            |            "children": [
            |              {
            |                "fullyQualifiedName": "foo.bar.MunitTestSuite",
            |                "className": "MunitTestSuite",
            |                "location": {
            |                  "uri": "$classUri",
            |                  "range": {
            |                    "start": {
            |                      "line": 3,
            |                      "character": 6
            |                    },
            |                    "end": {
            |                      "line": 3,
            |                      "character": 20
            |                    }
            |                  }
            |                },
            |                "kind": "suite"
            |              }
            |            ],
            |            "kind": "package"
            |          }
            |        ],
            |        "kind": "package"
            |      }
            |    ]
            |  }
            |]
            |""".stripMargin
      assertNoDiff(obtained, expected)
    }
  }

  private def getUriFor(relativePath: String) =
    workspace.resolve(relativePath).toURI.toString
}
