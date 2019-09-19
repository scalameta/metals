package tests

object ImplementationSuite extends BaseSlowSuite("implementation") {

  check(
    "basic",
    """|package a
       |trait Livin@@gBeing
       |abstract class <<Animal>> extends LivingBeing
       |class <<Dog>> extends Animal
       |class <<Cat>> extends Animal
       |""".stripMargin
  )

  check(
    "advanced",
    """|package a
       |trait Livin@@gBeing
       |trait <<MadeOfAtoms>> extends LivingBeing
       |abstract class <<Animal>> extends LivingBeing
       |class <<Dog>> extends Animal with MadeOfAtoms
       |class <<Cat>> extends Animal
       |""".stripMargin
  )

  check(
    "nested",
    """|package a
       |trait LivingBeing
       |abstract class Ani@@mal extends LivingBeing
       |object outer{
       |  object inner{  
       |    class <<Dog>> extends Animal
       |    class <<Cat>> extends Animal
       |    class Unrelated extends LivingBeing
       |  }
       |}
       |""".stripMargin
  )

  // TODO needs scalameta update
  // check(
  //   "anon",
  //   """|package a
  //      |trait Livin@@gObject
  //      |abstract class <<Animal>> extends LivingBeing
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
        _ <- server.verifyImplementation(
          "a/src/main/scala/a/Main.scala",
          edit,
          expected
        )
      } yield ()
    }
  }
}
