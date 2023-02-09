package example

object Vars/*Vars.scala*/ {
  var a/*Vars.scala*/ = 2

  a/*Vars.scala fallback to example.Vars.a().*/ = 2

  Vars/*Vars.scala*/.a/*Vars.scala fallback to example.Vars.a().*/ = 3
}
