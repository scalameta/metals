package example

class ForComprehensions/*example.ForComprehensions#*/ {
  for {
    a/*local0*/ <- List/*scala.package.List.*/(1)
    b/*local1*/ <- List/*scala.package.List.*/(a/*local0*/)
    if (
      a/*local0*/,
      b/*local1*/,
    ) ==/*scala.Any#`==`().*/ (1, 2)
    (
      c/*local3*/,
      d/*local4*/,
    ) <- List/*scala.package.List.*/((a/*local0*/, b/*local1*/))
    if (
      a/*local0*/,
      b/*local1*/,
      c/*local3*/,
      d/*local4*/,
    ) ==/*scala.Any#`==`().*/ (1, 2, 3, 4)
    e/*local5*//*local5*/ = (
      a/*local0*/,
      b/*local1*/,
      c/*local3*/,
      d/*local4*/,
    )
    if e/*local5*/ ==/*scala.Any#`==`().*/ (1, 2, 3, 4)
    f/*local6*/ <- List/*scala.package.List.*/(e/*local5*/)
  } yield {
    (
      a/*local0*/,
      b/*local1*/,
      c/*local3*/,
      d/*local4*/,
      e/*local5*/,
      f/*local6*/,
    )
  }

}
