package tests.feature

import tests.BaseLspSuite
import tests.BaseSourcePathSuite

abstract class BaseScalaPcDefinitionSuite(scalaVersion: String)
    extends BaseLspSuite("scala-pc-definition")
    with BaseSourcePathSuite {

  test("use-sourcepath") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Example.scala"
    val b = "a/src/main/scala/a/UseExample.scala"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {"scalaVersion": "$scalaVersion"}
            |}
            |/$a
            |package a
            |
            |object Example {
            |  val message = "Hello, World!"
            |}
            |/$b
            |package a
            |
            |object UseExample {
            |  def main(): Unit = {
            |    println(Example.message)
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(b)
      _ <- server.didOpen(a)
      _ <- server.didChange(a)(_.replace("message", "message2"))
      _ <- server.didChange(b)(_.replace("Example.message", "Example.message2"))
      _ = assertNoDiagnostics()
      _ <- server.assertDefinition(
        b,
        "Example.messag@@e2",
        """|a/src/main/scala/a/Example.scala:4:7: definition
           |  val message2 = "Hello, World!"
           |      ^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-class-definition") {
    cleanWorkspace()
    val model = "a/src/main/scala/a/Model.scala"
    val service = "a/src/main/scala/a/Service.scala"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {"scalaVersion": "$scalaVersion"}
            |}
            |/$model
            |package a
            |
            |case class User(name: String, email: String)
            |/$service
            |package a
            |
            |class UserService {
            |  def createUser(): User = User("John", "john@example.com")
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(service)
      _ = assertNoDiagnostics()
      _ <- server.assertDefinition(
        service,
        "def createUser(): User = Us@@er(\"John\", \"john@example.com\")",
        """|a/src/main/scala/a/Model.scala:3:12: definition
           |case class User(name: String, email: String)
           |           ^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-method-definition") {
    cleanWorkspace()
    val utils = "a/src/main/scala/a/Utils.scala"
    val main = "a/src/main/scala/a/Main.scala"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {"scalaVersion": "$scalaVersion"}
            |}
            |/$utils
            |package a
            |
            |object Utils {
            |  def formatName(first: String, last: String): String =
            |    s"$$last, $$first"
            |}
            |/$main
            |package a
            |
            |object Main {
            |  def run(): Unit = {
            |    val formatted = Utils.formatName("John", "Doe")
            |    println(formatted)
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(main)
      _ = assertNoDiagnostics()
      _ <- server.assertDefinition(
        main,
        "val formatted = Utils.format@@Name(\"John\", \"Doe\")",
        """|a/src/main/scala/a/Utils.scala:4:7: definition
           |  def formatName(first: String, last: String): String =
           |      ^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-trait-implementation") {
    cleanWorkspace()
    val trait_ = "a/src/main/scala/a/Repository.scala"
    val impl = "a/src/main/scala/a/UserRepository.scala"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {"scalaVersion": "$scalaVersion"}
            |}
            |/$trait_
            |package a
            |
            |trait Repository[T] {
            |  def save(item: T): Unit
            |  def findById(id: String): Option[T]
            |}
            |/$impl
            |package a
            |
            |class UserRepository extends Repository[String] {
            |  def save(item: String): Unit = println(s"Saving: $$item")
            |  def findById(id: String): Option[String] = Some(id)
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(impl)
      _ = assertNoDiagnostics()
      _ <- server.assertDefinition(
        impl,
        "class UserRepository extends Reposito@@ry[String] {",
        """|a/src/main/scala/a/Repository.scala:3:7: definition
           |trait Repository[T] {
           |      ^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-import-definition") {
    cleanWorkspace()
    val constants = "a/src/main/scala/a/Constants.scala"
    val config = "a/src/main/scala/a/Config.scala"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {"scalaVersion": "$scalaVersion"}
            |}
            |/$constants
            |package a
            |
            |object Constants {
            |  val DEFAULT_TIMEOUT = 30
            |  val MAX_RETRIES = 3
            |}
            |/$config
            |package a
            |
            |import a.Constants.DEFAULT_TIMEOUT
            |
            |object Config {
            |  val timeout = DEFAULT_TIMEOUT
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(config)
      _ = assertNoDiagnostics()
      _ <- server.assertDefinition(
        config,
        "import a.Constants.DEFAULT_TIME@@OUT",
        """|a/src/main/scala/a/Constants.scala:4:7: definition
           |  val DEFAULT_TIMEOUT = 30
           |      ^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-package-object") {
    cleanWorkspace()
    val packageObj = "a/src/main/scala/a/package.scala"
    val user = "a/src/main/scala/a/User.scala"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {"scalaVersion": "$scalaVersion"}
            |}
            |/$packageObj
            |package object a {
            |  type UserId = String
            |  def validateEmail(email: String): Boolean = email.contains("@")
            |}
            |/$user
            |package a
            |
            |class User(val id: UserId, val email: String) {
            |  require(validateEmail(email), "Invalid email")
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(user)
      _ = assertNoDiagnostics()
      _ <- server.assertDefinition(
        user,
        "class User(val id: User@@Id, val email: String) {",
        """|a/src/main/scala/a/package.scala:2:8: definition
           |  type UserId = String
           |       ^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-var-assignment") {
    cleanWorkspace()
    val state = "a/src/main/scala/a/AppState.scala"
    val controller = "a/src/main/scala/a/Controller.scala"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {"scalaVersion": "$scalaVersion"}
            |}
            |/$state
            |package a
            |
            |class AppState(var param: Int) {
            |  var isRunning: Boolean = false
            |  var errorCount: Int = 0
            |  lazy val lzy: Int = 42
            |  val op_+ : Int = 42
            |  val `back tick`: Int = 42
            |}
            |/$controller
            |package a
            |
            |object Controller {
            |  def start(): Unit = {
            |    val app = new AppState(1)
            |    app.isRunning = true
            |    app.param = 2
            |    println(s"Application started $${app.param} $${app.isRunning}")
            |    app.lzy
            |    app.op_+
            |    app.`back tick`
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(controller)
      _ = assertNoDiagnostics()
      _ <- server.assertDefinition(
        controller,
        "app.isRunn@@ing = true",
        """|a/src/main/scala/a/AppState.scala:4:7: definition
           |  var isRunning: Boolean = false
           |      ^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        controller,
        "app.par@@am = 2",
        """|a/src/main/scala/a/AppState.scala:3:20: definition
           |class AppState(var param: Int) {
           |                   ^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        controller,
        "println(s\"Application started ${app.par@@am} ${app.isRunning}\")",
        """|a/src/main/scala/a/AppState.scala:3:20: definition
           |class AppState(var param: Int) {
           |                   ^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        controller,
        "println(s\"Application started ${app.param} ${app.isRu@@nning}\")",
        """|a/src/main/scala/a/AppState.scala:4:7: definition
           |  var isRunning: Boolean = false
           |      ^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        controller,
        "app.lz@@y",
        """|a/src/main/scala/a/AppState.scala:6:12: definition
           |  lazy val lzy: Int = 42
           |           ^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        controller,
        "app.op@@_+",
        """|a/src/main/scala/a/AppState.scala:7:7: definition
           |  val op_+ : Int = 42
           |      ^^^^
           |""".stripMargin,
      )
      _ <- server.assertDefinition(
        controller,
        "app.`bac@@k tick`",
        """|a/src/main/scala/a/AppState.scala:8:8: definition
           |  val `back tick`: Int = 42
           |       ^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}

class ScalaPcDefinition212Suite extends BaseScalaPcDefinitionSuite("2.12.20")
class ScalaPcDefinition213Suite extends BaseScalaPcDefinitionSuite("2.13.16")
