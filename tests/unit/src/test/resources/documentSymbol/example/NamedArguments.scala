/*example(Package):35*/package example

/*example.User(Class):8*/case class User(
    name: String = {
      // assert default values have occurrences
      Map.toString
    }
)
/*example.NamedArguments(Module):35*/object NamedArguments {
  /*example.NamedArguments.susan(Constant):10*/val susan = "Susan"
  /*example.NamedArguments.user1(Constant):15*/val user1 =
    User
      .apply(
        name = "John"
      )
  /*example.NamedArguments.user2(Constant):22*/val user2: User =
    User(
      // FIXME: https://github.com/scalameta/scalameta/issues/1787
      name = susan
    ).copy(
      name = susan
    )

  // anonymous classes
  /*example.NamedArguments.b(Method):28*/@deprecated(
    message = "a",
    since = susan
  ) def b = 1

  // vararg
  List(
    xs = 2
  )

}
