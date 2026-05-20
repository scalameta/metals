package scala.meta.internal.metals.typeHierarchy

/**
 * Data that is preserved between a type hierarchy prepare and supertypes or subtypes request.
 *
 * @param symbol The symbol concerned by the request.
 */
private[typeHierarchy] final case class TypeHierarchyItemInfo(
    symbol: String
)
