package example

case class User(
    name: String = {
      // assert default values have occurrences
      Map.toString
    }
)
object NamedArguments {
  final val susan/*: String<<java/lang/String#>>*/ = "Susan"
  val user1/*: User<<(2:11)>>*/ =
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
  ) def b/*: Int<<scala/Int#>>*/ = 1

  // vararg
  List/*[Int<<scala/Int#>>]*/(
    elems = 2
  )

}