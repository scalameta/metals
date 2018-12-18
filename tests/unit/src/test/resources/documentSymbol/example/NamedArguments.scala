/*example(Package):35*/package example

/*User(Class):8*/case class User(
    name: String = {
      // assert default values have occurrences
      Map.toString
    }
)
/*NamedArguments(Module):35*/object NamedArguments {
  /*susan(Constant):10*/val susan = "Susan"
  /*user1(Constant):15*/val user1 =
    User
      .apply(
        name = "John"
      )
  /*user2(Constant):22*/val user2: User =
    User(
      // FIXME: https://github.com/scalameta/scalameta/issues/1787
      name = susan
    ).copy(
      name = susan
    )

  // anonymous classes
  /*b(Method):28*/@deprecated(
    message = "a",
    since = susan
  ) def b = 1

  // vararg
  List(
    xs = 2
  )

}
