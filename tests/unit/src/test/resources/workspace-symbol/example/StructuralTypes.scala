package example

object StructuralTypes/*example.StructuralTypes.*/ {
  type User/*example.StructuralTypes.User#*/ = {
    def name: String
    def age: Int
  }

  val user/*example.StructuralTypes.user.*/ = null.asInstanceOf[User]
  user.name
  user.age

  val V/*example.StructuralTypes.V.*/: Object {
    def scalameta: String
  } = new {
    def scalameta = "4.0"
  }
  V.scalameta
}
