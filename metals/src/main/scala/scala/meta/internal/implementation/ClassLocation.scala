package scala.meta.internal.implementation

import java.nio.file.Path

case class ClassLocation(
    symbol: String,
    file: Option[Path],
    // position of the reference to the overridden symbol for lazy resolution, e.g.
    // class AClass extends @@BClass
    pos: Option[Int] = None,
)
