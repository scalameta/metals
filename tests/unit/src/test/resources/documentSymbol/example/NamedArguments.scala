/*example:34*/package example

/*User:7*/case class User(
    name: String = {
      // assert default values have occurrences
      Map.toString
    }
)
/*NamedArguments:34*/object NamedArguments {
  /*susan:9*/val susan = "Susan"
  /*user1:14*/val user1 =
    User
      .apply(
        name = "John"
      )
  /*user2:21*/val user2: User =
    User(
      // FIXME: https://github.com/scalameta/scalameta/issues/1787
      name = susan
    ).copy(
      name = susan
    )

  // anonymous classes
  /*b:27*/@deprecated(
    message = "a",
    since = susan
  ) def b = 1

  // vararg
  List(
    xs = 2
  )

}
