package scala.meta.internal.metals.callHierarchy

/**
 * Data that is preserved between a call hierarchy prepare and incoming calls or outgoing calls request.
 *
 * @param symbols The set of symbols concerned by the request.
 * @param visited Symbols that are already visited to handle recursive cases.
 * @param isLocal Do we have to search for symbols in the whole workspace.
 * @param searchLocal Do we have to search for symbols locally.
 */
private[callHierarchy] final case class CallHierarchyItemInfo(
    symbols: Array[String],
    visited: Array[String],
    isLocal: Boolean,
    searchLocal: Boolean,
) {
  def withVisitedSymbols(
      additionalSymbols: Array[String]
  ): CallHierarchyItemInfo =
    new CallHierarchyItemInfo(
      symbols ++ additionalSymbols,
      visited ++ additionalSymbols,
      isLocal,
      searchLocal,
    )
}
