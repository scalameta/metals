/*example(Package):20*/package example

import reflect.Selectable.reflectiveSelectable

/*example.StructuralTypes(Module):19*/object StructuralTypes:
  /*example.StructuralTypes.User(TypeParameter):9*/type User = {
    def name: String
    def age: Int
  }

  /*example.StructuralTypes.user(Constant):11*/val user = null.asInstanceOf[User]
  user.name
  user.age

  /*example.StructuralTypes.V(Constant):18*/val V: Object {
    def scalameta: String
  } = /*example.StructuralTypes.V.new (anonymous)(Interface):18*/new:
    /*example.StructuralTypes.V.`new (anonymous)`#scalameta(Method):18*/def scalameta = "4.0"
  V.scalameta
end StructuralTypes
