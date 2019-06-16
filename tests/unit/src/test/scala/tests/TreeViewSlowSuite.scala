package tests

object TreeViewSlowSuite extends BaseSlowSuite("tree-view") {
  testAsync("projects") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": {}
          |}
          |/a/src/main/scala/a/First.scala
          |package a
          |class First {
          |  def a = 1
          |  val b = 2
          |}
          |object First
          |/a/src/main/scala/a/Second.scala
          |package a
          |class Second {
          |  def a = 1
          |  val b = 2
          |  var c = 2
          |}
          |object Second
          |/b/src/main/scala/b/Third.scala
          |package b
          |class Third
          |object Third
          |/b/src/main/scala/b/Fourth.scala
          |package b
          |class Fourth
          |object Fourth
          |""".stripMargin
      )
      _ = server.assertTreeViewChildren(
        "build",
        s"projects:${server.buildTarget("a")}",
        ""
      )
      _ <- server.didOpen("a/src/main/scala/a/First.scala")
      _ <- server.didOpen("b/src/main/scala/b/Third.scala")
      _ = server.assertTreeViewChildren(
        "build",
        s"projects:${server.buildTarget("a")}",
        "a/ +"
      )
      _ = server.assertTreeViewChildren(
        "build",
        s"projects:${server.buildTarget("a")}!/a/",
        """|First class -
           |First object
           |Second class -
           |Second object
           |""".stripMargin
      )
      _ = server.assertTreeViewChildren(
        "build",
        s"projects:${server.buildTarget("a")}!/a/First#",
        """|a() method
           |b val
           |""".stripMargin
      )
      _ = server.assertTreeViewChildren(
        "build",
        s"projects:${server.buildTarget("a")}!/a/Second#",
        """|a() method
           |b val
           |c var
           |""".stripMargin
      )
    } yield ()
  }

  testAsync("libraries") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {
          |    "libraryDependencies": [
          |      "io.circe::circe-core:0.11.1",
          |      "org.eclipse.lsp4j:org.eclipse.lsp4j:0.5.0",
          |      "com.lihaoyi::sourcecode:0.1.7"
          |    ]
          |  }
          |}
          |""".stripMargin
      )
      _ = {
        server.assertTreeViewChildren(
          "build",
          s"libraries:${server.jar("sourcecode")}",
          "sourcecode/ +"
        )
        server.assertTreeViewChildren(
          "build",
          s"libraries:${server.jar("scala-library")}!/scala/Some#",
          """|value val
             |isEmpty() method
             |get() method
             |x() method
             |""".stripMargin
        )
        server.assertTreeViewChildren(
          "build",
          s"libraries:${server.jar("lsp4j")}!/org/eclipse/lsp4j/FileChangeType#",
          """|Created enum
             |Changed enum
             |Deleted enum
             |values() method
             |valueOf() method
             |getValue() method
             |forValue() method
             |""".stripMargin
        )
        server.assertTreeViewChildren(
          "build",
          s"libraries:${server.jar("circe-core")}!/_root_/",
          """|io/ +
             |""".stripMargin
        )
        server.assertTreeViewChildren(
          "build",
          s"libraries:${server.jar("cats-core")}!/cats/instances/symbol/",
          """|package object
             |""".stripMargin
        )
      }
    } yield ()
  }
}
