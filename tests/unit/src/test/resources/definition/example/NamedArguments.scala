package example

case class User/*NamedArguments.semanticdb*/(
    name/*NamedArguments.semanticdb*/: String/*Predef.scala*/ = {
      // assert default values have occurrences
      Map/*Predef.scala*/.toString/*Object.java*/
    }
)
object NamedArguments/*NamedArguments.semanticdb*/ {
  val susan/*NamedArguments.semanticdb*/ = "Susan"
  val user1/*NamedArguments.semanticdb*/ =
    User/*NamedArguments.semanticdb*/
      .apply/*NamedArguments.semanticdb*/(
        name/*NamedArguments.semanticdb*/ = "John"
      )
  val user2/*NamedArguments.semanticdb*/: User/*NamedArguments.semanticdb*/ =
    User/*NamedArguments.semanticdb*/(
      // FIXME: https://github.com/scalameta/scalameta/issues/1787
      name/*<no symbol>*/ = susan/*NamedArguments.semanticdb*/
    ).copy/*NamedArguments.scala*/(
      name/*NamedArguments.scala*/ = susan/*NamedArguments.scala*/
    )

  // anonymous classes
  @deprecated/*deprecated.scala*/(
    message/*<no symbol>*/ = "a",
    since/*<no symbol>*/ = susan/*NamedArguments.scala*/
  ) def b/*NamedArguments.scala*/ = 1

  // vararg
  List/*List.scala*/(
    xs/*<no symbol>*/ = 2
  )

}
