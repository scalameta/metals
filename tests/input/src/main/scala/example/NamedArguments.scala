package example

case class User(
    name: String = {
      // assert default values have occurrences
      Map.toString
    }
)
object NamedArguments {
  final val susan = "Susan"
  val user1 =
    User
      .apply(
        name = "John"
      )
  val user2: User =
    User(
      name = susan
    ).copy(
      name = susan
    )

  // anonymous classes
  @deprecated(
    message = "a",
    since = susan,
  ) def b = 1

  // vararg
  List(
    elems = 2
  )

}
