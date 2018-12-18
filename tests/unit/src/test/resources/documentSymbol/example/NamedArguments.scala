/*example:35*/package example

/*User:8*/case class User(
    name: String = {
      // assert default values have occurrences
      Map.toString
    }
)
/*NamedArguments:35*/object NamedArguments {
  /*susan:10*/val susan = "Susan"
  /*user1:15*/val user1 =
    User
      .apply(
        name = "John"
      )
  /*user2:22*/val user2: User =
    User(
      // FIXME: https://github.com/scalameta/scalameta/issues/1787
      name = susan
    ).copy(
      name = susan
    )

  // anonymous classes
  /*b:28*/@deprecated(
    message = "a",
    since = susan
  ) def b = 1

  // vararg
  List(
    xs = 2
  )

}
