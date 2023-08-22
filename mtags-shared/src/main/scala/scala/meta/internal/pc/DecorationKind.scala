package scala.meta.internal.pc

/**
 * Represents the kind of a decoration.
 * Order is important, if multiple decorations must be inserted in the same place,
 * the one with the lowest kind will be inserted first.
 *
 * "abc".sorted<<[Char]>><<(Ordering.Char)>>
 *
 * Type parameter must come before implicit parameter
 */
object DecorationKind {
  val InferredType = 1
  val TypeParameter = 2
  val ImplicitConversion = 3
  val ImplicitParameter = 4
}
