package scala.meta.internal.parsing.finder

/**
 * Mangled class name, like BinarySearch$Smaller$ for objects or ClassFinder$ClassKind for classes or traits.
 * a.Bar$Bar2$Bar3$VeryInnerTrait for a very nested trait.
 */
final case class MangledClassName(value: String) extends AnyVal {
  def stripSuffix(suffix: String): MangledClassName =
    MangledClassName(value.stripSuffix(suffix))
}

/**
 * Just class name which is visible in code. Using examples provided in [[MangledClassName]]: Smaller, ClassKind, VeryInnerTrait
 */
final case class ShortClassName(value: String) extends AnyVal
