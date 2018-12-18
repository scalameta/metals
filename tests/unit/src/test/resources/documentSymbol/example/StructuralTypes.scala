/*example:19*/package example

/*StructuralTypes:19*/object StructuralTypes {
  /*User:7*/type User = {
    def name: String
    def age: Int
  }

  /*user:9*/val user = null.asInstanceOf[User]
  user.name
  user.age

  /*V:17*/val V: Object {
    def scalameta: String
  } = new {
    def scalameta = "4.0"
  }
  V.scalameta
}
