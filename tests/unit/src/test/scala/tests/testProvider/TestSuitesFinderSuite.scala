package tests

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import munit.Location
import munit.TestOptions

class TestSuitesFinderSuite extends BaseLspSuite("testSuitesFinderSuite") {
  val gson: Gson =
    new GsonBuilder().setPrettyPrinting.disableHtmlEscaping().create()

  check(
    "flat-structure",
    s"""|/metals.json
        |{
        |  "app": {
        |    "libraryDependencies" : [ "org.scalameta::munit:0.7.29", "org.scalatest::scalatest:3.2.1" ],
        |    "scalaVersion": "2.13.6"
        |  }
        |}
        |
        |/app/src/main/scala/NoPackage.scala
        |class NoPackage extends munit.FunSuite {
        |  test("noPackage") {}
        |}
        |""".stripMargin,
    List(
      "app/src/main/scala/NoPackage.scala"
    ),
    () => {
      s"""|[
          |  {
          |    "targetName": "app",
          |    "targetUri": "$targetUri",
          |    "discovered": [
          |      {
          |        "fullyQualifiedName": "NoPackage",
          |        "className": "NoPackage",
          |        "location": {
          |          "uri": "${classUriFor("app/src/main/scala/NoPackage.scala")}",
          |          "range": {
          |            "start": {
          |              "line": 0,
          |              "character": 6
          |            },
          |            "end": {
          |              "line": 0,
          |              "character": 15
          |            }
          |          }
          |        },
          |        "kind": "suite"
          |      }
          |    ]
          |  }
          |]
          |""".stripMargin
    }
  )

  check(
    "package-hierarchy",
    s"""|/metals.json
        |{
        |  "app": {
        |    "libraryDependencies" : [ "org.scalameta::munit:0.7.29", "org.scalatest::scalatest:3.2.1" ],
        |    "scalaVersion": "2.13.6"
        |  }
        |}
        |
        |/app/src/main/scala/NoPackage.scala
        |class NoPackage extends munit.FunSuite {
        |  test("noPackage") {}
        |}
        |
        |/app/src/main/scala/foo/Foo.scala
        |package foo
        |
        |class Foo extends munit.FunSuite {
        |  test("Foo") {}
        |}
        |
        |/app/src/main/scala/foo/bar/FooBar.scala
        |package foo.bar
        |
        |class FooBar extends munit.FunSuite {
        |  test("FooBar") {}
        |}
        |
        |/app/src/main/scala/another/AnotherPackage.scala
        |package another
        |
        |class AnotherPackage extends munit.FunSuite {
        |  test("AnotherPackage") {}
        |}
        |
        |""".stripMargin,
    List(
      "app/src/main/scala/NoPackage.scala",
      "app/src/main/scala/foo/Foo.scala",
      "app/src/main/scala/foo/bar/FooBar.scala",
      "app/src/main/scala/another/AnotherPackage.scala"
    ),
    () => {

      s"""|[
          |  {
          |    "targetName": "app",
          |    "targetUri": "$targetUri",
          |    "discovered": [
          |      {
          |        "fullyQualifiedName": "NoPackage",
          |        "className": "NoPackage",
          |        "location": {
          |          "uri": "${classUriFor("app/src/main/scala/NoPackage.scala")}",
          |          "range": {
          |            "start": {
          |              "line": 0,
          |              "character": 6
          |            },
          |            "end": {
          |              "line": 0,
          |              "character": 15
          |            }
          |          }
          |        },
          |        "kind": "suite"
          |      },
          |      {
          |        "prefix": "foo",
          |        "children": [
          |          {
          |            "fullyQualifiedName": "foo.Foo",
          |            "className": "Foo",
          |            "location": {
          |              "uri": "${classUriFor(
        "app/src/main/scala/foo/Foo.scala"
      )}",
          |              "range": {
          |                "start": {
          |                  "line": 2,
          |                  "character": 6
          |                },
          |                "end": {
          |                  "line": 2,
          |                  "character": 9
          |                }
          |              }
          |            },
          |            "kind": "suite"
          |          },
          |          {
          |            "prefix": "bar",
          |            "children": [
          |              {
          |                "fullyQualifiedName": "foo.bar.FooBar",
          |                "className": "FooBar",
          |                "location": {
          |                  "uri": "${classUriFor(
        "app/src/main/scala/foo/bar/FooBar.scala"
      )}",
          |                  "range": {
          |                    "start": {
          |                      "line": 2,
          |                      "character": 6
          |                    },
          |                    "end": {
          |                      "line": 2,
          |                      "character": 12
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
          |      },
          |      {
          |        "prefix": "another",
          |        "children": [
          |          {
          |            "fullyQualifiedName": "another.AnotherPackage",
          |            "className": "AnotherPackage",
          |            "location": {
          |              "uri": "${classUriFor(
        "app/src/main/scala/another/AnotherPackage.scala"
      )}",
          |              "range": {
          |                "start": {
          |                  "line": 2,
          |                  "character": 6
          |                },
          |                "end": {
          |                  "line": 2,
          |                  "character": 20
          |                }
          |              }
          |            },
          |            "kind": "suite"
          |          }
          |        ],
          |        "kind": "package"
          |      }
          |    ]
          |  }
          |]
          |""".stripMargin
    }
  )

  def check(
      name: TestOptions,
      layout: String,
      files: List[String],
      expected: () => String
  )(implicit
      loc: Location
  ) {
    test(name) {
      for {
        _ <- Future.successful(cleanWorkspace())
        _ <- initialize(layout)
        discovered <- server.discoverTestSuites(files)
      } yield {
        val obtained = gson.toJson(discovered.asJava)
        assertNoDiff(obtained, expected())
      }
    }
  }

  private def targetUri =
    s"${workspace.toURI.toString}app/?id=app".replace("///", "/")

  private def classUriFor(relativePath: String) =
    workspace.resolve(relativePath).toURI.toString
}
