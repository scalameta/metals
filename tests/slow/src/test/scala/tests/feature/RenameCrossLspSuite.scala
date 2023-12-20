package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseRenameLspSuite

class RenameCrossLspSuite extends BaseRenameLspSuite("rename-cross") {

  renamed(
    "macro",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |import io.circe.generic.JsonCodec
       |trait LivingBeing
       |@JsonCodec sealed trait <<An@@imal>> extends LivingBeing
       |object <<Animal>> {
       |  case object Dog extends <<Animal>>
       |  case object Cat extends <<Animal>>
       |}
       |""".stripMargin,
    "Tree",
    scalaVersion = Some(V.scala213),
  )

  renamed(
    "macro1",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |import io.circe.generic.JsonCodec
       |trait <<LivingBeing>>
       |@JsonCodec sealed trait Animal extends <<Livi@@ngBeing>>
       |object Animal {
       |  case object Dog extends Animal
       |  case object Cat extends Animal
       |}
       |""".stripMargin,
    "Tree",
    scalaVersion = Some(V.scala213),
  )

  renamed(
    "macro2",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |import io.circe.generic.JsonCodec
       |@JsonCodec
       |final case class <<Ma@@in2>>(name: String)
       |""".stripMargin,
    "Tree",
    scalaVersion = Some(V.scala213),
  )

  renamed(
    "macro3",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |import io.circe.generic.JsonCodec
       |trait LivingBeing
       |@JsonCodec sealed trait <<Animal>> extends LivingBeing
       |object <<Animal>>{
       |  case object Dog extends <<Animal>>
       |  case object Cat extends <<Animal>>
       |}
       |/a/src/main/scala/a/Use.scala
       |package a
       |object Use {
       |  val dog : <<An@@imal>> = <<Animal>>.Dog
       |}
       |""".stripMargin,
    "Tree",
    scalaVersion = Some(V.scala213),
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
    newName = "+++:",
  )

  renamed(
    "apply",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object User{
       |  def <<ap@@ply>>(name : String) = name
       |  def apply(name : String, age: Int) = name
       |}
       |object Main{
       |  val toRename = User##.##<<>>("abc")
       |}
       |""".stripMargin,
    newName = "name",
  )

  override protected def libraryDependencies: List[String] =
    List("org.scalatest::scalatest:3.2.12", "io.circe::circe-generic:0.14.1")

  override def scalacOptions: List[String] = List("-Ymacro-annotations")

}
