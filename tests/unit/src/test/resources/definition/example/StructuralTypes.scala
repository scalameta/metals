package example

object StructuralTypes/*StructuralTypes.semanticdb*/ {
  type User/*StructuralTypes.semanticdb*/ = {
    def name/*StructuralTypes.semanticdb*/: String/*Predef.scala*/
    def age/*StructuralTypes.semanticdb*/: Int/*Int.scala*/
  }

  val user/*StructuralTypes.semanticdb*/ = null.asInstanceOf[User/*StructuralTypes.semanticdb*/]
  user/*StructuralTypes.semanticdb*/.name/*<no symbol>*/
  user/*StructuralTypes.semanticdb*/.age/*<no symbol>*/

  val V/*StructuralTypes.semanticdb*/: Object/*Object.java*/ {
    def scalameta/*StructuralTypes.semanticdb*/: String/*Predef.scala*/
  } = new {
    def scalameta/*StructuralTypes.semanticdb*/ = "4.0"
  }
  V/*StructuralTypes.semanticdb*/.scalameta/*<no symbol>*/
}
