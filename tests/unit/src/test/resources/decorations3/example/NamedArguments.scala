package example

case class User(
    name: String = {
      // assert default values have occurrences
      Map.toString
    }
)
object NamedArguments {
  final val susan/*: "Susan"*/ = "Susan"
  val user1/*: User*/ =
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
  ) def b/*: Int*/ = 1

  // vararg
  List/*[Int]*/(
    elems = 2
  )

}