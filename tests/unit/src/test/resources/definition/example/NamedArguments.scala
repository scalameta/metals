package example

case class User/*NamedArguments.scala*/(
    name/*NamedArguments.scala*/: String/*Predef.scala*/ = {
      // assert default values have occurrences
      Map/*Predef.scala*/.toString/*Object.java*/
    }
)
object NamedArguments/*NamedArguments.scala*/ {
  final val susan/*NamedArguments.scala*/ = "Susan"
  val user1/*NamedArguments.scala*/ =
    User/*NamedArguments.scala*/
      .apply/*NamedArguments.scala fallback to example.User#*/(
        name/*NamedArguments.scala fallback to example.User#name.*/ = "John"
      )
  val user2/*NamedArguments.scala*/: User/*NamedArguments.scala*/ =
    User/*NamedArguments.scala*/(
      name/*NamedArguments.scala fallback to example.User#name.*/ = susan/*NamedArguments.scala*/
    ).copy/*NamedArguments.scala fallback to example.User#*/(
      name/*NamedArguments.scala fallback to example.User#name.*/ = susan/*NamedArguments.scala*/
    )

  // anonymous classes
  @deprecated/*deprecated.scala*/(
    message/*deprecated.scala fallback to scala.deprecated#*/ = "a",
    since/*deprecated.scala fallback to scala.deprecated#*/ = susan/*NamedArguments.scala*/
  ) def b/*NamedArguments.scala*/ = 1

  // vararg
  List/*package.scala*/(
    elems/*Factory.scala*/ = 2
  )

}
