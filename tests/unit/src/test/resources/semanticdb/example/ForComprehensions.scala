package example

class ForComprehensions/*example.ForComprehensions#*/ {
  for {
    a/*local0*/ <- List/*scala.collection.immutable.List.*/(1)
    b/*local1*/ <- List/*scala.collection.immutable.List.*/(a/*local0*/)
    if (
      a/*local0*/,
      b/*local1*/
    ) ==/*java.lang.Object#`==`().*/ (1, 2)
    (
      c/*local7*/,
      d/*local8*/
    ) <- List/*scala.collection.immutable.List.*/((a/*local0*/, b/*local1*/))
    if (
      a/*local0*/,
      b/*local1*/,
      c/*local7*/,
      d/*local8*/
    ) ==/*java.lang.Object#`==`().*/ (1, 2, 3, 4)
    e/*local10*/ = (
        a/*local0*/,
        b/*local1*/,
        c/*local7*/,
        d/*local8*/
    )
    if e/*local10*/ ==/*java.lang.Object#`==`().*/ (1, 2, 3, 4)
    f/*local11*/ <- List/*scala.collection.immutable.List.*/(e/*local10*/)
  } yield {
    (
      a/*local0*/,
      b/*local1*/,
      c/*local7*/,
      d/*local8*/,
      e/*local10*/,
      f/*local11*/
    )
  }

}
