package example

object StructuralTypes/*example.StructuralTypes.*/ {
  type User/*example.StructuralTypes.User#*/ = {
    def name/*local0*/: String/*scala.Predef.String#*/
    def age/*local1*/: Int/*scala.Int#*/
  }

  val user/*example.StructuralTypes.user.*/ = null.asInstanceOf/*scala.Any#asInstanceOf().*/[User/*example.StructuralTypes.User#*/]
  user/*example.StructuralTypes.user.*/.name
  user/*example.StructuralTypes.user.*/.age

  val V/*example.StructuralTypes.V.*/: Object/*java.lang.Object#*/ {
    def scalameta/*local2*/: String/*scala.Predef.String#*/
  } = new {
    def scalameta/*local3*/ = "4.0"
  }
  V/*example.StructuralTypes.V.*/.scalameta
}
