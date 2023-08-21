package example

class ForComprehensions {
  for {
    a/*: Int<<scala/Int#>>*/ <- List/*[Int<<scala/Int#>>]*/(1)/*[(Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>), (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>))]*/
    b/*: Int<<scala/Int#>>*/ <- List/*[Int<<scala/Int#>>]*/(a)
    if (
      a,
      b,
    ) == (1, 2)/*[(Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>), (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>))]*/
    (
      c/*: Int<<scala/Int#>>*/,
      d/*: Int<<scala/Int#>>*/,
    ) <- List/*[(Int<<scala/Int#>>, Int<<scala/Int#>>)]*/((a, b))
    if (
      a,
      b,
      c,
      d,
    ) == (1, 2, 3, 4)/*[((Int<<scala/Int#>>, Int<<scala/Int#>>), (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>))]*/
    e/*: (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>)*/ = (
      a,
      b,
      c,
      d,
    )
    if e == (1, 2, 3, 4)/*[(Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>), (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>))]*/
    f/*: (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>)*/ <- List/*[(Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>)]*/(e)/*[(Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>), (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>))]*/
  } yield {
    (
      a,
      b,
      c,
      d,
      e,
      f,
    )
  }

}