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
      c/*local4*/,
      d/*local5*/
    ) <- List/*scala.collection.immutable.List.*/((a/*local0*/, b/*local2*/))
    if (
      a/*local0*/,
      b/*local2*/,
      c/*local6*/,
      d/*local7*/
    ) ==/*java.lang.Object#`==`().*/ (1, 2, 3, 4)
    e/*local11*/ = (
      a/*local0*/,
      b/*local2*/,
      c/*local9*/,
      d/*local10*/
    )
    if e/*local14*/ ==/*java.lang.Object#`==`().*/ (1, 2, 3, 4)
    f/*local18*/ <- List/*scala.collection.immutable.List.*/(e/*local17*/)
  } yield {
    (
      a/*local0*/,
      b/*local2*/,
      c/*local15*/,
      d/*local16*/,
      e/*local17*/,
      f/*local18*/
    )
  }

}
