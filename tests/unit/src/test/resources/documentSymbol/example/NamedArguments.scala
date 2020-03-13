/*example(Package):34*/package example

/*example.User(Class):8*/case class User(
    name: String = {
      // assert default values have occurrences
      Map.toString
    }
)
/*example.NamedArguments(Module):34*/object NamedArguments {
  /*example.NamedArguments.susan(Constant):10*/val susan = "Susan"
  /*example.NamedArguments.user1(Constant):15*/val user1 =
    User
      .apply(
        name = "John"
      )
  /*example.NamedArguments.user2(Constant):21*/val user2: User =
    User(
      name = susan
    ).copy(
      name = susan
    )

  // anonymous classes
  /*example.NamedArguments.b(Method):27*/@deprecated(
    message = "a",
    since = susan
  ) def b = 1

  // vararg
  List(
    xs = 2
  )

}
