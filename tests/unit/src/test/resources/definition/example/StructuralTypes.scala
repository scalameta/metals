package example

object StructuralTypes/*StructuralTypes.scala*/ {
  type User/*StructuralTypes.scala*/ = {
    def name/*StructuralTypes.semanticdb*/: String/*Predef.scala*/
    def age/*StructuralTypes.semanticdb*/: Int/*Int.scala*/
  }

  val user/*StructuralTypes.scala*/ = null.asInstanceOf[User/*StructuralTypes.scala*/]
  user/*StructuralTypes.scala*/.name/*<no symbol>*/
  user/*StructuralTypes.scala*/.age/*<no symbol>*/

  val V/*StructuralTypes.scala*/: Object/*Object.java*/ {
    def scalameta/*StructuralTypes.semanticdb*/: String/*Predef.scala*/
  } = new {
    def scalameta/*StructuralTypes.semanticdb*/ = "4.0"
  }
  V/*StructuralTypes.scala*/.scalameta/*<no symbol>*/
}
