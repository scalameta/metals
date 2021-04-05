package example

def foo/*ToplevelDefVal.scala*/(): Int/*Int.scala*/ = 42

val abc/*ToplevelDefVal.scala*/: String/*Predef.scala*/ = "sds"

// tests jar's indexing on Windows
type SourceToplevelTypeFromDepsRef/*ToplevelDefVal.scala*/ = EmptyTuple/*Tuple.scala*/
