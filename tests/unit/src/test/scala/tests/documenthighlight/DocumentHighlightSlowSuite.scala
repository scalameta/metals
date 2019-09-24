package tests
package documenthighlight

object DocumentHighlightSlowSuite extends BaseSlowSuite("documentHighlight") {

  check(
    "single",
    """
      |object Main {
      |  Option(1).<<he@@ad>>
      |}""".stripMargin
  )

  check(
    "multiple",
    """
      |object Main {
      |  val <<abc>> = 123
      |  <<abc>>.toInt
      |  println(<<ab@@c>>)
      |}""".stripMargin
  )

  check(
    "different-symbols",
    """
      |object Main {
      |  val abc = 123
      |  abc.<<toInt@@>>
      |  134l.toInt
      |}""".stripMargin
  )

  check(
    "scopes",
    """
      |object Main {
      |  val <<@@a>> = 123
      |  val f = (a: Int) => a + 1
      |  println(<<a>>)
      |}""".stripMargin
  )

  check(
    "params",
    """
      |case class User(<<name>>: String)
      |object Main {
      |  val user = User(<<na@@me>> = "Susan")
      |  println(user.<<name>>)
      |  user.copy(<<name>> = "John")
      |}""".stripMargin
  )

  check(
    "object",
    """
      |case class <<User>>(name: String)
      |object <<User>>
      |object Main {
      |  val user = <<U@@ser>>(name = "Susan")
      |  println(user.name)
      |  user.copy(name = "John")
      |}""".stripMargin
  )

  check(
    "case-class-var",
    """
      |case class User(var <<name>>: String)
      |object Main {
      |  val user = User(<<na@@me>> = "Susan")
      |  println(user.<<name>>)
      |  user.<<name>> = ""
      |  user.copy(<<name>> = "John")
      |}""".stripMargin
  )

  check(
    "var",
    """
      |object Main {
      |  var <<abd>> = 123
      |  <<ab@@d>> = 344
      |  <<abd>> +=1
      |  println(<<abd>>)
      |}""".stripMargin
  )

  def check(name: String, testCase: String): Unit = {
    val edit = testCase.replaceAll("(<<|>>)", "")
    val expected = testCase.replaceAll("@@", "")
    val base = testCase.replaceAll("(<<|>>|@@)", "")
    testAsync(name) {
      for {
        _ <- server.initialize(
          s"""/metals.json
             |{"a":{}}
             |/a/src/main/scala/a/Main.scala
             |$base
      """.stripMargin
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        _ <- server.assertHighlight(
          "a/src/main/scala/a/Main.scala",
          edit,
          expected
        )
      } yield ()
    }
  }

}
