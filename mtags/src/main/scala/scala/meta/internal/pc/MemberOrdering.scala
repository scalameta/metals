package scala.meta.internal.pc

object MemberOrdering {
  val IsWorkspaceSymbol: Int = 1 << 30
  val IsInheritedBaseMethod: Int = 1 << 29
  val IsImplicitConversion: Int = 1 << 28
  val IsInherited: Int = 1 << 27
  val IsNotLocalByBlock: Int = 1 << 26
  val IsNotDefinedInFile: Int = 1 << 25
  val IsNotGetter: Int = 1 << 24
  val IsPackage: Int = 1 << 23
  val IsNotCaseAccessor: Int = 1 << 22
  val IsNotPublic: Int = 1 << 21
  val IsSynthetic: Int = 1 << 20
  val IsDeprecated: Int = 1 << 19
  val IsEvilMethod: Int = 1 << 18 // example: clone() and finalize()

  // OverrideDefMember
  val IsNotAbstract: Int = 1 << 30
}
