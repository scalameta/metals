package tests
import scala.concurrent.Future

object ImplementationSuite extends BaseSlowSuite("implementation") {

  check(
    "basic",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait Livin@@gBeing
       |abstract class <<Animal>> extends LivingBeing
       |class <<Dog>> extends Animal
       |class <<Cat>> extends Animal
       |""".stripMargin
  )

  check(
    "advanced",
    """|/a/src/main/scala/a/LivingBeing.scala
       |package a
       |trait Livin@@gBeing
       |/a/src/main/scala/a/MadeOfAtoms.scala
       |package a
       |trait <<MadeOfAtoms>> extends LivingBeing
       |/a/src/main/scala/a/Animal.scala
       |package a
       |abstract class <<Animal>> extends LivingBeing
       |/a/src/main/scala/a/Dog.scala
       |package a
       |class <<Dog>> extends Animal with MadeOfAtoms
       |/a/src/main/scala/a/Cat.scala
       |package a
       |class <<Cat>> extends Animal
       |""".stripMargin
  )

  check(
    "nested",
    """|/a/src/main/scala/a/Main.scala
       |package a
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
  //   """|/a/src/main/scala/a/Main.scala
  //      |package a
  //      |trait Livin@@gObject
  //      |abstract class <<Animal>> extends LivingBeing
  //      |object Main{
  //      |  val animal = new <<Animal>>{ val field : Int = 123 }
  //      |}
  //      |""".stripMargin
  // )

  def check(name: String, input: String): Unit = {
    val files = FileLayout.mapFromString(input)
    val (filename, edit) = files
      .find(_._2.contains("@@"))
      .map {
        case (fileName, code) =>
          (fileName, code.replaceAll("(<<|>>)", ""))
      }
      .getOrElse {
        throw new IllegalArgumentException(
          "No `@@` was defined that specifies cursor position"
        )
      }
    val expected = files.map {
      case (fileName, code) =>
        fileName -> code.replaceAll("@@", "")
    }
    val base = files.map {
      case (fileName, code) =>
        fileName -> code.replaceAll("(<<|>>|@@)", "")
    }

    testAsync(name) {
      cleanWorkspace()
      for {
        _ <- server.initialize(
          s"""/metals.json
             |{"a":{}}
             |${input
               .replaceAll("(<<|>>|@@)", "")}""".stripMargin
        )
        _ <- Future.sequence(
          files.map(file => server.didOpen(s"${file._1}"))
        )
        _ <- server.assertImplementation(
          filename,
          edit,
          expected.toMap,
          base.toMap
        )
      } yield ()
    }
  }
}
