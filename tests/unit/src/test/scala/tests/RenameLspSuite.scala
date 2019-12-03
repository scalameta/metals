package tests
import scala.concurrent.Future

object RenameLspSuite extends BaseLspSuite("rename") {

  renamed(
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

  renamed(
    "across-targets",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<toRename>> = 123
       |}
       |/b/src/main/scala/b/Main2.scala
       |package b
       |import a.Main
       |object Main2{
       |  val toRename = Main.<<toR@@ename>>
       |}
       |""".stripMargin,
    newName = "otherRename"
  )

  renamed(
    "unapply",
    """|/a/src/main/scala/a/Main.scala
       |object <<F@@oo>> {
       |  def unapply(s: String): Option[String] = Some("")
       |}
       |
       |object Main{
       |  "foo" match {
       |    case <<Foo>>(s) => ()
       |  }
       |}
       |""".stripMargin,
    newName = "Bar"
  )

  renamed(
    "unapply-param",
    """|/a/src/main/scala/a/Main.scala
       |object Foo {
       |  def unapply(<<nam@@e>>: String): Option[String] = Some(<<name>>)
       |}
       |
       |object Main{
       |  "foo" match {
       |    case Foo(name) => ()
       |  }
       |}
       |""".stripMargin,
    newName = "str"
  )

  renamed(
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

  renamed(
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

  renamed(
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

  renamed(
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

  renamed(
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

  renamed(
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

  renamed(
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

  same(
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
       |""".stripMargin
  )

  renamed(
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

  same(
    "unary-bad",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class User{
       |  def <<unary_!>> = false
       |}
       |object Main{
       |  val user = new User()
       |  <<@@!>>user
       |}
       |""".stripMargin
  )

  same(
    "unary-bad2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class User{
       |  def <<u@@nary_!>> = false
       |}
       |object Main{
       |  val user = new User()
       |  <<!>>user
       |}
       |""".stripMargin
  )

  same(
    "java-classes",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class MyException extends Exce@@ption
       |class NewException extends RuntimeException
       |class NewException2 extends RuntimeException
       |""".stripMargin
  )

  renamed(
    "inheritance",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |abstract class <<An@@imal>>
       |class Dog extends <<Animal>>
       |class Cat extends <<Animal>>
       |""".stripMargin,
    newName = "Tree"
  )

  renamed(
    "companion",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class <<Main>>{}
       |object <<M@@ain>>
       |""".stripMargin,
    newName = "Tree",
    fileRenames =
      Map("a/src/main/scala/a/Main.scala" -> "a/src/main/scala/a/Tree.scala")
  )

  renamed(
    "companion2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |class <<Ma@@in>>{}
       |object <<Main>>
       |""".stripMargin,
    newName = "Tree",
    fileRenames =
      Map("a/src/main/scala/a/Main.scala" -> "a/src/main/scala/a/Tree.scala")
  )

  renamed(
    "filename-exact-match",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object <<Ma@@in>>
       |object TheMain
       |""".stripMargin,
    newName = "Tree",
    fileRenames =
      Map("a/src/main/scala/a/Main.scala" -> "a/src/main/scala/a/Tree.scala")
  )

  renamed(
    "filename-exact-match-2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main
       |object <<The@@Main>>
       |""".stripMargin,
    newName = "Tree",
    fileRenames = Map.empty
  )

  renamed(
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

  renamed(
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

  renamed(
    "java-unchanged",
    """|/a/src/main/java/a/Other.java
       |package a;
       |public class Other{
       |
       |}
       |/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val other = new <<Oth@@er>>()
       |}
       |""".stripMargin,
    newName = "Renamed"
  )

  renamed(
    "compile-error",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<toRename>> : Int = 123
       |}
       |/a/src/main/scala/a/Main2.scala
       |package a
       |object Main2{
       |  val toRename = Main.<<toR@@ename>>
       |}
       |""".stripMargin,
    newName = "otherRename",
    breakingChange = (str: String) => str.replaceAll("Int", "String")
  )

  // tests currently not working correctly due to issues in SemanticDB
  // issue https://github.com/scalameta/scalameta/issues/1636
  renamed(
    "params",
    """|/a/src/main/scala/a/Main.scala
       |case class Name(<<va@@lue>>: String)
       |
       |object Main {
       |  val name1 = Name(value = "42")
       |   .copy(<<value>> = "43")
       |   .<<value>>
       |  val name2 = new Name(value = "44")
       |}
       |""".stripMargin,
    newName = "name"
  )

  same(
    "macro-annotation",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |import io.circe.generic.JsonCodec
       |trait LivingBeing
       |@JsonCodec sealed trait <<An@@imal>> extends LivingBeing
       |object Animal {
       |  case object Dog extends <<Animal>>
       |  case object Cat extends <<Animal>>
       |}
       |""".stripMargin
  )

  renamed(
    "type-params",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |trait <<A@@BC>>
       |class CBD[T <: <<AB@@C>>]
       |object Main{
       |  val a = classOf[ABC]
       |  val b = new CBD[<<ABC>>]
       |}
       |""".stripMargin,
    newName = "Animal"
  )

  def renamed(
      name: String,
      input: String,
      newName: String,
      nonOpened: Set[String] = Set.empty,
      breakingChange: String => String = identity[String],
      fileRenames: Map[String, String] = Map.empty
  ): Unit =
    check(
      name,
      input,
      newName,
      notRenamed = false,
      nonOpened = nonOpened,
      breakingChange,
      fileRenames
    )

  def same(
      name: String,
      input: String
  ): Unit =
    check(
      name,
      input,
      "SHOULD_NOT_BE_RENAMED",
      notRenamed = true
    )

  def check(
      name: String,
      input: String,
      newName: String,
      notRenamed: Boolean = false,
      nonOpened: Set[String] = Set.empty,
      breakingChange: String => String = identity[String],
      fileRenames: Map[String, String] = Map.empty
  ): Unit = {
    testAsync(name) {
      cleanWorkspace()
      val allMarkersRegex = "(<<|>>|@@|##.*##)"
      val files = FileLayout.mapFromString(input)
      val expectedFiles = files.map {
        case (file, code) =>
          fileRenames.getOrElse(file, file) -> {
            val expected = if (!notRenamed) {
              code
                .replaceAll("\\<\\<\\S*\\>\\>", newName)
                .replaceAll("##", "")
            } else {
              code.replaceAll(allMarkersRegex, "")
            }
            "\n" + breakingChange(expected)
          }
      }

      val (filename, edit) = files
        .find(_._2.contains("@@"))
        .getOrElse {
          throw new IllegalArgumentException(
            "No `@@` was defined that specifies cursor position"
          )
        }

      val openedFiles = files.keySet
        .filterNot(file => nonOpened.contains(file))
      val fullInput = input.replaceAll(allMarkersRegex, "")
      for {
        _ <- server.initialize(
          s"""/metals.json
             |{
             |  "a" : {
             |    "compilerPlugins": [
             |      "org.scalamacros:::paradise:2.1.1"
             |    ],
             |    "libraryDependencies": [
             |      "org.scalatest::scalatest:3.0.5",
             |      "io.circe::circe-generic:0.12.0"
             |    ]
             |  },
             |  "b" : {
             |    dependsOn: [ "a" ]
             |  }
             |}
             |$fullInput""".stripMargin
        )
        _ <- Future.sequence {
          openedFiles.map { file =>
            server.didOpen(file)
          }
        }
        // possible breaking changes for testing
        _ <- Future.sequence {
          openedFiles.map { file =>
            server.didSave(file) { code =>
              breakingChange(code)
            }
          }
        }
        // chnage the code to make sure edit distance is being used
        _ <- Future.sequence {
          openedFiles.map { file =>
            server.didChange(file) { code =>
              "\n" + code
            }
          }
        }
        _ <- server.assertRename(
          filename,
          edit.replaceAll("(<<|>>|##.*##)", ""),
          expectedFiles,
          files.toMap.keySet,
          newName
        )
      } yield ()
    }
  }
}
