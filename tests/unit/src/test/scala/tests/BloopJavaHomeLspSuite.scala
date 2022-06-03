package tests

import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

class BloopJavaHomeLspSuite extends BaseLspSuite("java-home") {

  val jsonFilePath: Option[AbsolutePath] =
    BloopServers.getBloopFilePath("bloop.json")
  val jsonFile: Option[AbsolutePath] = jsonFilePath.filter(_.exists)
  val lockFile: Option[AbsolutePath] =
    BloopServers.getBloopFilePath("created_by_metals.lock").filter(_.exists)
  val contents: Option[String] = jsonFile.map(_.readText)

  val javaHome: String =
    sys.env.get("JAVA_HOME").orElse(sys.props.get("java.home")).getOrElse("")

  def stashBloopJson(): Unit = {
    jsonFile.foreach(_.delete())
    lockFile.foreach(_.delete())
  }

  def unStashBloopJson(): Unit = {
    contents.zip(jsonFile).foreach { case (content, file) =>
      file.delete()
      file.touch()
      file.writeText(content)
    }
    lockFile.zip(jsonFile).foreach { case (lockfile, jsonFile) =>
      lockfile.delete()
      lockfile.touch()
      lockfile.writeText(jsonFile.toNIO.toFile().lastModified().toString())
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

  test("broken-home") {
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
        """|a/src/main/scala/a/A.scala:2:29: error: Found:    (1 : Int)
           |Required: String
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
        """|a/src/main/scala/a/A.scala:2:29: error: Found:    (1 : Int)
           |Required: String
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
