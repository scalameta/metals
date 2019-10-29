package tests
import scala.concurrent.Future

object RenameSuite extends BaseLspSuite("rename") {

  check(
    "basic",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<toRename>> = 123
       |}
       |/a/src/main/scala/a/Main2.scala
       |package a
       |object Main2{
       |  val toRename = Main.<<toR@@ename>>
       |}
       |""".stripMargin,
    newName = "otherRename"
  )

  check(
    "local",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  def hello() = {
       |    val <<toRen@@ame>> = 123
       |    <<toRename>>
       |  }
       |}
       |""".stripMargin,
    newName = "otherRename"
  )

  check(
    "method",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  def <<met@@hod>>(abc : String) = true
       |
       |  if(<<method>>("")) println("Is true!")
       |}
       |""".stripMargin,
    newName = "truth"
  )

  check(
    "self-type",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait <<A@@BC>>
       |trait Alphabet{
       |  this: <<ABC>> =>
       |}
       |object Main{
       |  val a = new Alphabet with <<ABC>>
       |}
       |""".stripMargin,
    newName = "Animal"
  )

  check(
    "method-inheritance",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait Hello{
       |  def <<method>>(abc : String) : Boolean
       |}
       |
       |class GoodMorning extends Hello {
       |  def <<met@@hod>>(abc : String) = true
       |}
       |""".stripMargin,
    newName = "truth"
  )

  check(
    "long-inheritance",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait A[T, S] {
       |  def <<method>>(abc : T) : S
       |}
       |
       |abstract class B[T] extends A[T, Boolean] {
       |  def <<method>>(abc : T) : Boolean
       |}
       |
       |abstract class C extends B[String] {
       |  def <<meth@@od>>(abc : String) : Boolean = false
       |}
       |""".stripMargin,
    newName = "truth"
  )

  check(
    "multiple-inheritance",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait A {
       |  def <<method>>(abc : String) : Boolean
       |}
       |
       |trait B {
       |  def <<method>>(abc : String) : Boolean = true
       |}
       |
       |abstract class C extends B with A {
       |  override def <<meth@@od>>(abc : String) : Boolean = false
       |}
       |""".stripMargin,
    newName = "truth"
  )

  check(
    "apply",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object User{
       |  def <<ap@@ply>>(name : String) = name
       |}
       |object Main{
       |  val toRename = User##.##<<>>("abc")
       |}
       |""".stripMargin,
    newName = "name"
  )

  check(
    "colon-bad",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class User{
       |  def <<:@@:>>(name : String) = name
       |}
       |object Main{
       |  val user = new User()
       |  "" <<::>> user
       |}
       |""".stripMargin,
    newName = "SHOULD_NOT_BE_RENAMED",
    notRenamed = true
  )

  check(
    "colon-good",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class User{
       |  def <<:@@:>>(name : String) = name
       |}
       |object Main{
       |  val user = new User()
       |  "" <<::>> user
       |}
       |""".stripMargin,
    newName = "method:"
  )

  check(
    "unary-bad",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class User{
       |  def <<unary_!>> = false
       |}
       |object Main{
       |  val user = new User()
       |  "" <<@@!>> user
       |}
       |""".stripMargin,
    newName = "SHOULD_NOT_BE_RENAMED",
    notRenamed = true
  )

  check(
    "unary-bad2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class User{
       |  def <<u@@nary_!>> = false
       |}
       |object Main{
       |  val user = new User()
       |  "" <<!>> user
       |}
       |""".stripMargin,
    newName = "SHOULD_NOT_BE_RENAMED",
    notRenamed = true
  )

  check(
    "java-classes",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class MyException extends Exce@@ption
       |class NewException extends RuntimeException
       |class NewException2 extends RuntimeException
       |""".stripMargin,
    newName = "SHOULD_NOT_BE_RENAMED",
    notRenamed = true
  )

  check(
    "inheritance",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |abstract class <<An@@imal>>
       |class Dog extends <<Animal>>
       |class Cat extends <<Animal>>
       |""".stripMargin,
    newName = "Tree"
  )

  check(
    "companion",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class <<Main>>{}
       |object <<M@@ain>>
       |""".stripMargin,
    newName = "Tree"
  )

  check(
    "companion2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class <<Ma@@in>>{}
       |object <<Main>>
       |""".stripMargin,
    newName = "Tree"
  )

  check(
    "many-files",
    """|/a/src/main/scala/a/A.scala
       |package a
       |object A {
       |  def <<ren@@ameIt>>(a : String) = ""
       |}
       |/a/src/main/scala/a/B.scala
       |package a
       |object B {
       |  val str = A.<<renameIt>>("")
       |}
       |/a/src/main/scala/a/C.scala
       |package a
       |object C {
       |  val str = A.<<renameIt>>("")
       |}
       |/a/src/main/scala/a/D.scala
       |package a
       |object D {
       |  val str = A.<<renameIt>>("")
       |}
       |/a/src/main/scala/a/E.scala
       |package a
       |object E {
       |  val str = A.<<renameIt>>("")
       |}
       |""".stripMargin,
    newName = "iAmRenamed",
    nonOpened = Set(
      "a/src/main/scala/a/C.scala",
      "a/src/main/scala/a/D.scala",
      "a/src/main/scala/a/E.scala"
    )
  )

  check(
    "anon",
    """|/a/src/main/scala/a/Main.scala
       |trait Methodable[T] {
       |  def <<method>>(asf: T): Int
       |}
       |
       |trait Alphabet extends Methodable[String] {
       |  def <<method>>(adf: String) = 123
       |}
       |
       |object Main {
       |  val a = new Alphabet {
       |    override def <<me@@thod>>(adf: String): Int = 321
       |  }
       |}
       |""".stripMargin,
    newName = "renamed"
  )

  // currently not working due to issues in SemanticDB
  // check(
  //   "macro-annotation",
  //   """|/a/src/main/scala/a/Main.scala
  //      |package a
  //      |import io.circe.generic.JsonCodec
  //      |trait LivingBeing
  //      |@JsonCodec sealed trait <<An@@imal>> extends LivingBeing
  //      |object Animal {
  //      |  case object Dog extends <<Animal>>
  //      |  case object Cat extends <<Animal>>
  //      |}
  //      |""".stripMargin,
  //   newName = "Tree"
  // )

  // check(
  //   "classof",
  //   """|/a/src/main/scala/a/Main.scala
  //      |package a
  //      |trait <<A@@BC>>
  //      |object Main{
  //      |  val a = classOf[<<A@@BC>>]
  //      |}
  //      |""".stripMargin,
  //   newName = "Animal"
  // )

  def check(
      name: String,
      input: String,
      newName: String,
      notRenamed: Boolean = false,
      nonOpened: Set[String] = Set.empty
  ): Unit = {
    val allMarkersRegex = "(<<|>>|@@|##.*##)"
    val files = FileLayout.mapFromString(input)
    val expectedFiles = files.map {
      case (file, code) =>
        file -> {
          if (!notRenamed) {
            code.replaceAll("<<.*>>", newName).replaceAll("##", "")
          } else {
            code.replaceAll(allMarkersRegex, "")
          }
        }
    }
    val base = files.map {
      case (fileName, code) =>
        fileName -> code.replaceAll(allMarkersRegex, "")
    }

    val (filename, edit) = files
      .find(_._2.contains("@@"))
      .getOrElse {
        throw new IllegalArgumentException(
          "No `@@` was defined that specifies cursor position"
        )
      }

    testAsync(name) {
      cleanWorkspace()
      for {
        _ <- server.initialize(
          s"""/metals.json
             |{"a":
             |  {
             |    "compilerPlugins": [
             |      "org.scalamacros:::paradise:2.1.1"
             |    ],
             |    "libraryDependencies": [
             |      "org.scalatest::scalatest:3.0.5",
             |      "io.circe::circe-generic:0.12.0"
             |    ]
             |  }
             |}
             |${input.replaceAll(allMarkersRegex, "")}""".stripMargin
        )
        _ <- Future.sequence(
          files
            .filterNot(file => nonOpened.contains(file._1))
            .map { file =>
              server.didOpen(s"${file._1}")
            }
        )
        _ <- server.assertRename(
          filename,
          edit.replaceAll("(<<|>>)", ""),
          expectedFiles,
          base.toMap,
          newName
        )
      } yield ()
    }
  }
}
