package example

import util.{Failure/*Try.scala*/ => NotGood/*<no symbol>*/}
import math.{floor/*package.scala*/ => _, _}

class Imports/*Imports.scala*/ {
  // rename reference
  NotGood/*Try.scala*/(null)
  max/*package.scala*/(1, 2)
}
