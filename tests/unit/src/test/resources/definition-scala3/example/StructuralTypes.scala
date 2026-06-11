package example

import reflect.Selectable/*Selectable.scala*/.reflectiveSelectable/*Selectable.scala*/

object StructuralTypes/*StructuralTypes.scala*/:
  type User/*StructuralTypes.scala*/ = {
    def name/*StructuralTypes.scala fallback to example.StructuralTypes.User#*/: String/*Predef.scala*/
    def age/*StructuralTypes.scala fallback to example.StructuralTypes.User#*/: Int/*Int.scala*/
  }

  val user/*StructuralTypes.scala*/ = null.asInstanceOf/*Any.scala*/[User/*StructuralTypes.scala*/]
  user/*StructuralTypes.scala*/.name/*StructuralTypes.scala fallback to example.StructuralTypes.User#*/
  user/*StructuralTypes.scala*/.age/*StructuralTypes.scala fallback to example.StructuralTypes.User#*/

  val V/*StructuralTypes.scala*/: Object/*Object.java*/ {
    def scalameta/*StructuralTypes.semanticdb*/: String/*Predef.scala*/
  } = new:
    def scalameta/*StructuralTypes.semanticdb*/ = "4.0"
  V/*StructuralTypes.scala*/.scalameta/*no local definition*/
end/*<no symbol>*/ StructuralTypes/*StructuralTypes.scala*/
