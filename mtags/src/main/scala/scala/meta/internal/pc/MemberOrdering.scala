package scala.meta.internal.pc

object MemberOrdering {
  val IsWorkspaceSymbol = 1 << 30
  val IsInheritedBaseMethod = 1 << 29
  val IsImplicitConversion = 1 << 28
  val IsInherited = 1 << 27
  val IsNotLocalByBlock = 1 << 26
  val IsNotGetter = 1 << 25
  val IsPackage = 1 << 24
  val IsNotCaseAccessor = 1 << 23
  val IsNotPublic = 1 << 22
  val IsSynthetic = 1 << 21
  val IsDeprecated = 1 << 20
  val IsEvilMethod = 1 << 19 // example: clone() and finalize()

  // OverrideDefMember
  val IsNotAbstract = 1 << 30
}
