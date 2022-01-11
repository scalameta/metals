package scala.meta.internal.parsing

/**
 * A pair of tokens that align with each other across two different files
 */
class MatchingToken[A](val original: A, val revised: A) {
  def show(implicit ops: TokenOps[A]): String = {
    s"${ops.show(original)} <-> ${ops.show(revised)}"
  }

}
