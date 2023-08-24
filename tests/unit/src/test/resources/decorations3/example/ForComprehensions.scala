package example

class ForComprehensions {
  for {
    a/*: Int*/ <- List/*[Int]*/(1)
    b/*: Int*/ <- List/*[Int]*/(a)
    if (
      a,
      b,
    ) == (1, 2)
    (
      c/*: Int*/,
      d/*: Int*/,
    ) <- List/*[(Int, Int)]*/((a, b))
    if (
      a,
      b,
      c,
      d,
    ) == (1, 2, 3, 4)
    e/*: (Int, Int, Int, Int)*/ = (
      a,
      b,
      c,
      d,
    )
    if e == (1, 2, 3, 4)
    f/*: (Int, Int, Int, Int)*/ <- List/*[(Int, Int, Int, Int)]*/(e)
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