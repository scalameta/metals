package example

object StructuralTypes {
  type User = {
    def name: String
    def age: Int
  }

  val user/*: User*/ = null.asInstanceOf[User]
  user.name
  user.age

  val V: Object {
    def scalameta: String
  } = new {
    def scalameta/*: String*/ = "4.0"
  }
  V.scalameta
}