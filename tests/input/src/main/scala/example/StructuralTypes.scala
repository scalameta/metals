package example

object StructuralTypes {
  type User = {
    def name: String
    def age: Int
  }

  val user = null.asInstanceOf[User]
  user.name
  user.age

  val V: Object {
    def scalameta: String
  } = new {
    def scalameta = "4.0"
  }
  V.scalameta
}
