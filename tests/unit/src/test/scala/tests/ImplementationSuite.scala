package tests

object ImplementationSuite extends BaseSlowSuite("implementation") {

  check(
    "basic",
    """|package a
       |trait Livin@@gObject
       |abstract class <<Animal>> extends LivingObject
       |class <<Dog>> extends Animal
       |class <<Cat>> extends Animal
       |""".stripMargin
  )

  check(
    "nested",
    """|package a
       |trait LivingObject
       |abstract class Ani@@mal extends LivingObject
       |object outer{
       |  object inner{  
       |    class <<Dog>> extends Animal
       |    class <<Cat>> extends Animal
       |    class Unrelated extends LivingObject
       |  }
       |}
       |""".stripMargin
  )

  // TODO needs scalameta update
  // check(
  //   "anon",
  //   """|package a
  //      |trait Livin@@gObject
  //      |abstract class <<Animal>> extends LivingObject
  //      |object Main{
  //      |  val animal = new <<Animal>>{ val field : Int = 123 }
  //      |}
  //      |""".stripMargin
  // )

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
        _ <- server.assertImplementation(
          "a/src/main/scala/a/Main.scala",
          edit,
          expected
        )
      } yield ()
    }
  }
}
