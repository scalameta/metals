/*example:18*/package example

/*StructuralTypes:18*/object StructuralTypes {
  /*User:6*/type User = {
    def name: String
    def age: Int
  }

  /*user:8*/val user = null.asInstanceOf[User]
  user.name
  user.age

  /*V:16*/val V: Object {
    def scalameta: String
  } = new {
    def scalameta = "4.0"
  }
  V.scalameta
}
