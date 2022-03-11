package example

case class User/*example.User#*/(
    name/*example.User#name.*/: String = {
      // assert default values have occurrences
      Map.toString
    }
)
object NamedArguments/*example.NamedArguments.*/ {
  final val susan/*example.NamedArguments.susan.*/ = "Susan"
  val user1/*example.NamedArguments.user1.*/ =
    User
      .apply(
        name = "John"
      )
  val user2/*example.NamedArguments.user2.*/: User =
    User(
      name = susan
    ).copy(
      name = susan
    )

  // anonymous classes
  @deprecated(
    message = "a",
    since = susan
  ) def b/*example.NamedArguments.b().*/ = 1

  // vararg
  List(
    elems = 2
  )

}
