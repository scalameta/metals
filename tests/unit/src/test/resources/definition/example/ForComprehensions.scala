package example

class ForComprehensions/*ForComprehensions.scala*/ {
  for {
    a/*ForComprehensions.semanticdb*/ <- List/*List.scala*/(1)
    b/*ForComprehensions.semanticdb*/ <- List/*List.scala*/(a/*ForComprehensions.semanticdb*/)
    if (
      a/*ForComprehensions.semanticdb*/,
      b/*ForComprehensions.semanticdb*/
    ) ==/*Object.java*/ (1, 2)
    (
      c/*ForComprehensions.semanticdb*/,
      d/*ForComprehensions.semanticdb*/
    ) <- List/*List.scala*/((a/*ForComprehensions.semanticdb*/, b/*no local definition*/))
    if (
      a/*ForComprehensions.semanticdb*/,
      b/*no local definition*/,
      c/*no local definition*/,
      d/*no local definition*/
    ) ==/*Object.java*/ (1, 2, 3, 4)
    e/*ForComprehensions.semanticdb*/ = (
      a/*ForComprehensions.semanticdb*/,
      b/*no local definition*/,
      c/*no local definition*/,
      d/*no local definition*/
    )
    if e/*no local definition*/ ==/*Object.java*/ (1, 2, 3, 4)
    f/*ForComprehensions.semanticdb*/ <- List/*List.scala*/(e/*no local definition*/)
  } yield {
    (
      a/*ForComprehensions.semanticdb*/,
      b/*no local definition*/,
      c/*no local definition*/,
      d/*no local definition*/,
      e/*no local definition*/,
      f/*ForComprehensions.semanticdb*/
    )
  }

}
