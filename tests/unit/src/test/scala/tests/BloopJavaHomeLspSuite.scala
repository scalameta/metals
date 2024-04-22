package tests

import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import coursierapi.JvmManager

class BloopJavaHomeLspSuite extends BaseLspSuite("java-home") {

  val jsonFilePath: Option[AbsolutePath] =
    BloopServers.getBloopFilePath("bloop.json")
  val jsonFile: Option[AbsolutePath] = jsonFilePath.filter(_.exists)
  val contents: Option[String] = jsonFile.map(_.readText)

  val javaHome: String = sys.props.get("java.home").getOrElse("")
  val pathToJava21: String = JvmManager.create().get("21").toString()

  def stashBloopJson(): Unit = {
    jsonFile.foreach(_.delete())
  }

  def unStashBloopJson(): Unit = {
    contents.zip(jsonFile).foreach { case (content, file) =>
      file.delete()
      file.touch()
      file.writeText(content)
    }
  }

  override def afterAll(): Unit = {
    unStashBloopJson()
    super.afterAll()
  }

  override def beforeEach(context: BeforeEach): Unit = {
    stashBloopJson()
    super.beforeEach(context)
  }

  test("incorrect-home") {
    cleanWorkspace()
    jsonFilePath.foreach(
      _.writeText(
        ujson.Obj
          .apply("javaHome" -> "not-java")
          .toString()
      )
    )
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A { val x : String = 1 }
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:2:29: error: type mismatch;
           | found   : Int(1)
           | required: String
           |object A { val x : String = 1 }
           |                            ^
           |""".stripMargin,
      )
      bloopJsonText = jsonFilePath.map(_.readText).getOrElse("")
      _ = assert(
        server.client.messageRequests.isEmpty(),
        "No message should show up",
      )
      _ = assertNoDiff(
        bloopJsonText,
        ujson.Obj
          .apply("javaHome" -> javaHome)
          .toString(),
      )
    } yield ()
  }

  test("correct-home") {
    cleanWorkspace()
    val bloopJsonContent =
      ujson.Obj
        .apply("javaHome" -> pathToJava21)
        .toString()

    jsonFilePath.foreach(_.writeText(bloopJsonContent))
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A { val x : String = 1 }
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:2:29: error: type mismatch;
           | found   : Int(1)
           | required: String
           |object A { val x : String = 1 }
           |                            ^
           |""".stripMargin,
      )
      bloopJsonText = jsonFilePath.map(_.readText).getOrElse("")
      _ = assert(
        server.client.messageRequests.isEmpty(),
        "No message should show up",
      )
      _ = assertNoDiff(
        bloopJsonText,
        bloopJsonContent,
      )
    } yield ()
  }

  test("no-home") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A { val x : String = 1 }
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:2:29: error: type mismatch;
           | found   : Int(1)
           | required: String
           |object A { val x : String = 1 }
           |                            ^
           |""".stripMargin,
      )
      _ = assert(
        server.client.messageRequests.isEmpty(),
        "No message should show up",
      )
      bloopJsonText = jsonFilePath.map(_.readText).getOrElse("")
      _ = assertNoDiff(
        bloopJsonText,
        ujson.Obj
          .apply("javaHome" -> javaHome)
          .toString(),
      )
    } yield ()
  }

}
