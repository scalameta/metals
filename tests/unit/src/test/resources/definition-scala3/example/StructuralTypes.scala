package example

import reflect.Selectable/*Selectable.scala*/.reflectiveSelectable/*Selectable.scala*/

object StructuralTypes/*StructuralTypes.scala*/:
  type User/*StructuralTypes.scala*/ = {
    def name/*StructuralTypes.semanticdb*/: String/*Predef.scala*/
    def age/*StructuralTypes.semanticdb*/: Int/*Int.scala*/
  }

  val user/*StructuralTypes.scala*/ = null.asInstanceOf[User/*StructuralTypes.scala*/]
  user/*StructuralTypes.scala*/./*unexpected: scala.reflect.Selectable#selectDynamic().*/name/*Selectable.scala*/
  user/*StructuralTypes.scala*/./*unexpected: scala.reflect.Selectable#selectDynamic().*/age/*Selectable.scala*/

  val V/*StructuralTypes.scala*/: Object/*Object.java*/ {
    def scalameta/*StructuralTypes.semanticdb*/: String/*Predef.scala*/
  } = new:
    def scalameta/*StructuralTypes.semanticdb*/ = "4.0"
  V/*StructuralTypes.scala*/./*unexpected: scala.reflect.Selectable#selectDynamic().*/scalameta/*Selectable.scala*/
end/*<no symbol>*/ StructuralTypes/*StructuralTypes.scala*/
