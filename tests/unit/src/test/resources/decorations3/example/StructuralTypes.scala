package example

import reflect.Selectable.reflectiveSelectable

object StructuralTypes:
  type User = {
    def name: String
    def age: Int
  }

  val user/*: User*/ = null.asInstanceOf[User]
  /*reflectiveSelectable(*/user/*)*/.name
  /*reflectiveSelectable(*/user/*)*/.age

  val V: Object {
    def scalameta: String
  } = new:
    def scalameta/*: String*/ = "4.0"
  /*reflectiveSelectable(*/V/*)*/.scalameta
end StructuralTypes