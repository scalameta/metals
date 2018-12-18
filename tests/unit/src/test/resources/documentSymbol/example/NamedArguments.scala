/*example*/package example

/*User*/case class User(
    name: String = {
      // assert default values have occurrences
      Map.toString
    }
)
/*NamedArguments*/object NamedArguments {
  /*susan*/val susan = "Susan"
  /*user1*/val user1 =
    User
      .apply(
        name = "John"
      )
  /*user2*/val user2: User =
    User(
      // FIXME: https://github.com/scalameta/scalameta/issues/1787
      name = susan
    ).copy(
      name = susan
    )

  // anonymous classes
  /*b*/@deprecated(
    message = "a",
    since = susan
  ) def b = 1

  // vararg
  List(
    xs = 2
  )

}
