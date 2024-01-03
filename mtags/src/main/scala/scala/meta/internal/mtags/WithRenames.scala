package scala.meta.internal.mtags

case class WithRenames[+T](tpe: T, renames: Map[String, String] = Map.empty) {
  def map[R](f: T => R): WithRenames[R] = WithRenames(f(tpe), renames)
  def flatMap[R](f: T => WithRenames[R]): WithRenames[R] = {
    val WithRenames(tpeRes, renames2) = f(tpe)
    WithRenames(tpeRes, renames ++ renames2)
  }
}

object WithRenames {
  def sequence[A](in: List[WithRenames[A]]): WithRenames[List[A]] =
    in.iterator.foldRight(WithRenames(List.empty[A])) { case (curr, acc) =>
      WithRenames(curr.tpe :: acc.tpe, curr.renames ++ acc.renames)
    }
}
