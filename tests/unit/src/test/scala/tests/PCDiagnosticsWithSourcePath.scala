package tests

class PCDiagnosticsWithSourcePath
    extends BaseLspSuite("pc-diagnostics-source-path")
    with BaseSourcePathSuite {

  // this tests that the presentation compiler is able to load sources
  // that haven't been compiled yet, but are placed in the right directory structure
  test("cross-file-reference") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Person.scala
           |package a
           |
           |case class Person(name: String, age: Int)
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  val person = Person("Alice", 30)
           |  def greet(): String = s"Hello, ${person.name}!"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didFocus("a/src/main/scala/a/Main.scala")
      _ = assertNoDiagnostics()
    } yield ()
  }

  // this tests that the presentation compiler is able to load sources
  // that don't follow the standard directory structure (package != path)
  test("cross-file-reference-non-matching-path") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/Person.scala
           |package a
           |
           |case class Person(name: String, age: Int)
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  val person = Person("Alice", 30)
           |  def greet(): String = s"Hello, ${person.name}!"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didFocus("a/src/main/scala/a/Main.scala")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("cross-project-dependency") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {},
           |  "b": {
           |    "dependsOn": ["a"]
           |  }
           |}
           |/a/src/main/scala/a/Library.scala
           |package a
           |
           |case class Data(value: String)
           |
           |object Library {
           |  def process(data: Data): String = data.value.toUpperCase
           |}
           |/b/src/main/scala/b/Client.scala
           |package b
           |
           |import a.{Data, Library}
           |
           |object Client {
           |  val data = Data("hello")
           |  val result = Library.process(data)
           |  def display(): String = s"Result: $result"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/b/Client.scala")
      _ <- server.didFocus("b/src/main/scala/b/Client.scala")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("cross-project-dependency-with-changes") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {},
           |  "b": {
           |    "dependsOn": ["a"]
           |  }
           |}
           |/a/src/main/scala/a/Library.scala
           |package a
           |
           |case class Data(value: String)
           |
           |object Library {
           |  def process(data: Data): String = data.value.toUpperCase
           |}
           |/b/src/main/scala/b/Client.scala
           |package b
           |
           |import a.{Data, Library}
           |
           |object Client {
           |  val data = Data("hello")
           |  val result: String = Library.process(data)
           |  def display(): String = s"Result: $result"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/b/Client.scala")
      _ <- server.didFocus("b/src/main/scala/b/Client.scala")
      _ = assertNoDiagnostics()
      // Now open and modify the Library in project "a" to break the API
      _ <- server.didOpen("a/src/main/scala/a/Library.scala")
      _ <- server.didChange("a/src/main/scala/a/Library.scala")(
        _.replace(
          "def process(data: Data): String = data.value.toUpperCase",
          "def process(data: Data): Int = data.value.length",
        )
      )
      _ <- server.didSave("a/src/main/scala/a/Library.scala")
      // Focus back on project "b" file to trigger diagnostics
      _ <- server.didFocus("b/src/main/scala/b/Client.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/Client.scala:7:24: error: type mismatch;
           | found   : Int
           | required: String
           |  val result: String = Library.process(data)
           |                       ^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-project-dependency-with-changes-java") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {},
           |  "b": {
           |    "dependsOn": ["a"]
           |  }
           |}
           |/a/src/main/java/a/Library.java
           |package a;
           |
           |public class Library {
           |  public static String process(String data) {
           |    return data.toUpperCase();
           |  }
           |}
           |/b/src/main/scala/b/Client.scala
           |package b
           |
           |import a.Library
           |
           |object Client {
           |  val data = "hello"
           |  val result: String = Library.process(data)
           |  def display(): String = s"Result: $result"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/b/Client.scala")
      _ <- server.didFocus("b/src/main/scala/b/Client.scala")
      _ = assertNoDiagnostics()
      // Now open and modify the Library in project "a" to break the API
      _ <- server.didOpen("a/src/main/java/a/Library.java")
      _ <- server.didChange("a/src/main/java/a/Library.java")(
        _.replace(
          "public static String process(String data) {\n    return data.toUpperCase();\n  }",
          "public static int process(String data) {\n    return data.length();\n  }",
        )
      )
      _ <- server.didSave("a/src/main/java/a/Library.java")
      // Focus back on project "b" file to trigger diagnostics
      _ <- server.didFocus("b/src/main/scala/b/Client.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/Client.scala:7:24: error: type mismatch;
           | found   : Int
           | required: String
           |  val result: String = Library.process(data)
           |                       ^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("abstract-inherited-member") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Service.scala
           |package a
           |
           |trait Service {
           |  def execute(): String
           |}
           |
           |class ConcreteService extends Service
           |
           |object Main {
           |  val service = new ConcreteService()
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Service.scala")
      _ <- server.didFocus("a/src/main/scala/a/Service.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Service.scala:7:1: error: class ConcreteService needs to be abstract.
           |Missing implementation for member of trait Service:
           |  def execute(): String = ???
           |
           |class ConcreteService extends Service
           |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("bad-override") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Service.scala
           |package a
           |
           |trait Service {
           |  def execute(in: String): String = ???
           |}
           |
           |class ConcreteService extends Service {
           |  override def execute(in: Int): String = in.toString
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |object Main {
           |  val service = new ConcreteService()
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Service.scala")
      _ <- server.didFocus("a/src/main/scala/a/Service.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Service.scala:8:16: error: method execute overrides nothing.
           |Note: the super classes of class ConcreteService contain the following, non final members named execute:
           |def execute(in: String): String
           |  override def execute(in: Int): String = in.toString
           |               ^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("overrides-with-warnings") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {
           |    "scalacOptions": ["-Wunused"]
           |  }
           |}
           |/a/src/main/scala/a/Service.scala
           |package a
           |import scala.collection.mutable.ListBuffer
           |
           |trait Service {
           |  def execute(in: String): String
           |}
           |
           |class ConcreteService extends Service {
           |  def foo() {
           |    val unused = 1
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Service.scala")
      _ <- server.didFocus("a/src/main/scala/a/Service.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Service.scala:2:33: warning: Unused import
           |import scala.collection.mutable.ListBuffer
           |                                ^^^^^^^^^^
           |a/src/main/scala/a/Service.scala:8:1: error: class ConcreteService needs to be abstract.
           |Missing implementation for member of trait Service:
           |  def execute(in: String): String = ???
           |
           |> class ConcreteService extends Service {
           |>   def foo() {
           |>     val unused = 1
           |>   }
           |> }
           |a/src/main/scala/a/Service.scala:10:9: warning: local val unused in method foo is never used
           |    val unused = 1
           |        ^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
