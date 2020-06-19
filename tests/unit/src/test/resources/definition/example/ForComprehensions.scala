package example

class ForComprehensions/*ForComprehensions.scala*/ {
  for {
    a/*ForComprehensions.semanticdb*/ <- List/*List.scala*/(1)
    b/*ForComprehensions.semanticdb*/ <- List/*List.scala*/(a/*ForComprehensions.semanticdb*/)
    if (
      a/*ForComprehensions.semanticdb*/,
      b/*ForComprehensions.semanticdb*/
    ) ==/*Object.java fallback to java.lang.Object#*/ (1, 2)
    (
      c/*ForComprehensions.semanticdb*/,
      d/*ForComprehensions.semanticdb*/
    ) <- List/*List.scala*/((a/*ForComprehensions.semanticdb*/, b/*ForComprehensions.semanticdb*/))
    if (
      a/*ForComprehensions.semanticdb*/,
      b/*ForComprehensions.semanticdb*/,
      c/*ForComprehensions.semanticdb*/,
      d/*ForComprehensions.semanticdb*/
    ) ==/*Object.java fallback to java.lang.Object#*/ (1, 2, 3, 4)
    e/*ForComprehensions.semanticdb*/ = (
        a/*ForComprehensions.semanticdb*/,
        b/*ForComprehensions.semanticdb*/,
        c/*ForComprehensions.semanticdb*/,
        d/*ForComprehensions.semanticdb*/
    )
    if e/*ForComprehensions.semanticdb*/ ==/*Object.java fallback to java.lang.Object#*/ (1, 2, 3, 4)
    f/*ForComprehensions.semanticdb*/ <- List/*List.scala*/(e/*ForComprehensions.semanticdb*/)
  } yield {
    (
      a/*ForComprehensions.semanticdb*/,
      b/*ForComprehensions.semanticdb*/,
      c/*ForComprehensions.semanticdb*/,
      d/*ForComprehensions.semanticdb*/,
      e/*ForComprehensions.semanticdb*/,
      f/*ForComprehensions.semanticdb*/
    )
  }

}
