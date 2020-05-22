package example

class ForComprehensions {
  for {
    a <- List(1)
    b <- List(a)
    if (
      a,
      b
    ) == (1, 2)
    (
      c,
      d
    ) <- List((a, b))
    if (
      a,
      b,
      c,
      d
    ) == (1, 2, 3, 4)
    e = (
        a,
        b,
        c,
        d
    )
    if e == (1, 2, 3, 4)
    f <- List(e)
  } yield {
    (
      a,
      b,
      c,
      d,
      e,
      f
    )
  }

}
