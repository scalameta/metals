package example

import util.{Failure/*scala.util.Failure.*//*scala.util.Failure#*/ => NotGood}
import math.{floor/*scala.math.package.floor().*/ => _, _}

class Imports/*example.Imports#*/ {
  // rename reference
  NotGood/*scala.util.Failure.*/(null)
  max/*scala.math.package.max().*/(1, 2)
}
