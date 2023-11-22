package example

import reflect.Selectable.reflectiveSelectable

object StructuralTypes:
  type User = {
    def name: String
    def age: Int
  }

  val user/*: User<<(5:7)>>*/ = null.asInstanceOf[User]
  /*reflectiveSelectable<<scala/reflect/Selectable.reflectiveSelectable().>>(*/user/*)*/.name
  /*reflectiveSelectable<<scala/reflect/Selectable.reflectiveSelectable().>>(*/user/*)*/.age

  val V: Object {
    def scalameta: String
  } = new:
    def scalameta/*: String<<java/lang/String#>>*/ = "4.0"
  /*reflectiveSelectable<<scala/reflect/Selectable.reflectiveSelectable().>>(*/V/*)*/.scalameta
end StructuralTypes