package example

trait Cancelable/*AndOrType.scala*/ 
trait Movable/*AndOrType.scala*/ 

type Y/*AndOrType.scala*/ = (Cancelable/*AndOrType.scala*/ & Movable/*AndOrType.scala*/)

type X/*AndOrType.scala*/ = String/*Predef.scala*/ | Int/*Int.scala*/
