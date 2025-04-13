package example

import util.{Failure => NotGood}
import math.{floor => _, _}

class Imports {
  // rename reference
  NotGood/*[Nothing<<scala/Nothing#>>]*/(/*exception = */null)
  max(/*x = */1, /*y = */2)
}