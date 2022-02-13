package example

case class User/*example.User#*/(
    name/*example.User#name.*/: String/*scala.Predef.String#*/ = {
      // assert default values have occurrences
      Map/*scala.Predef.Map.*/.toString/*java.lang.Object#toString().*/
    }
)
object NamedArguments/*example.NamedArguments.*/ {
  final val susan/*example.NamedArguments.susan.*/ = "Susan"
  val user1/*example.NamedArguments.user1.*/ =
    User/*example.User.*/
      .apply/*example.User.apply().*/(
        name/*example.User.apply().(name)*/ = "John"
      )
  val user2/*example.NamedArguments.user2.*/: User/*example.User#*/ =
    User/*example.User.*/(
      name/*example.User.apply().(name)*/ = susan/*example.NamedArguments.susan.*/
    ).copy/*example.User#copy().*/(
      name/*example.User#copy().(name)*/ = susan/*example.NamedArguments.susan.*/
    )

  // anonymous classes
  @deprecated/*scala.deprecated#*//*scala.deprecated#`<init>`().*/(
    message/*scala.deprecated#`<init>`().(message)*/ = "a",
    since/*scala.deprecated#`<init>`().(since)*/ = susan/*example.NamedArguments.susan.*/
  ) def b/*example.NamedArguments.b().*/ = 1

  // vararg
  List/*scala.package.List.*/(
    elems/*scala.collection.IterableFactory#apply().(elems)*/ = 2
  )

}
