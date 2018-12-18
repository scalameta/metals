/*example*/package example

/*StructuralTypes*/object StructuralTypes {
  /*User*/type User = {
    def name: String
    def age: Int
  }

  /*user*/val user = null.asInstanceOf[User]
  user.name
  user.age

  /*V*/val V: Object {
    def scalameta: String
  } = new {
    def scalameta = "4.0"
  }
  V.scalameta
}
