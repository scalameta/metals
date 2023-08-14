package example

object StructuralTypes {
  type User = {
    def name: String
    def age: Int
  }

  val user/*: StructuralTypes.User*/ = null.asInstanceOf[User]
  user.name
  user.age

  val V: Object {
    def scalameta: String
  } = new {
    def scalameta/*: String<<java/lang/String#>>*/ = "4.0"
  }
  V.scalameta
}