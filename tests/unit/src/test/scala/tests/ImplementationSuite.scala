package tests
import scala.concurrent.Future

object ImplementationSuite extends BaseSlowSuite("implementation") {

  check(
    "basic",
    "src/main/scala/a/Main.scala" ->
      """|package a
         |trait Livin@@gBeing
         |abstract class <<Animal>> extends LivingBeing
         |class <<Dog>> extends Animal
         |class <<Cat>> extends Animal
         |""".stripMargin
  )

  check(
    "advanced",
    "src/main/scala/a/LivingBeing.scala" ->
      """|package a
         |trait Livin@@gBeing
         |""".stripMargin,
    "src/main/scala/a/MadeOfAtoms.scala" ->
      """|package a
         |trait <<MadeOfAtoms>> extends LivingBeing
         |""".stripMargin,
    "src/main/scala/a/Animal.scala" ->
      """|package a
         |abstract class <<Animal>> extends LivingBeing
         |""".stripMargin,
    "src/main/scala/a/Dog.scala" ->
      """|package a
         |class <<Dog>> extends Animal with MadeOfAtoms
         |""".stripMargin,
    "src/main/scala/a/Cat.scala" ->
      """|package a
         |class <<Cat>> extends Animal
         |""".stripMargin
  )

  check(
    "nested",
    "src/main/scala/a/Main.scala" ->
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

  def check(name: String, files: (String, String)*): Unit = {
    val projectName = name
    val Some((filename, edit)) = files.find(_._2.contains("@@")).map {
      case (fileName, code) =>
        (s"$projectName/$fileName", code.replaceAll("(<<|>>)", ""))
    }
    val expected = files.map {
      case (fileName, code) =>
        s"/$projectName/$fileName" -> code.replaceAll("@@", "")
    }
    val base = files.map {
      case (fileName, code) =>
        s"/$projectName/$fileName" -> code.replaceAll("(<<|>>|@@)", "")
    }
    testAsync(name) {
      for {
        _ <- server.initialize(
          s"""|/metals.json
              |{"$projectName":{}}\n""".stripMargin +
            base
              .map {
                case (fileName, code) =>
                  s"$fileName\n$code\n"
              }
              .mkString("\n")
        )
        _ <- Future.sequence(
          files.map(file => server.didOpen(s"$projectName/${file._1}"))
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
