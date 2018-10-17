package example

case class User(
    name: String = {
      // assert default values have occurrences
      Map.toString
    }
)
object NamedArguments {
  val susan = "Susan"
  val user1 =
    User
      .apply(
        name = "John"
      )
  val user2: User =
    User(
      // FIXME: https://github.com/scalameta/scalameta/issues/1787
      name = susan
    ).copy(
      name = susan
    )

  // anonymous classes
  @deprecated(
    message = "a",
    since = susan
  ) def b = 1

  // vararg
  List(
    xs = 2
  )

}
