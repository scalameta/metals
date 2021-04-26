package scala.meta.internal.pc

import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Definitions

import scala.util.control.NonFatal

object SemanticdbSymbols {

  def inverseSemanticdbSymbol(sym: String)(using ctx: Context): List[Symbol] = {
    import scala.meta.internal.semanticdb.Scala._

    val defns = ctx.definitions
    import defns._

    def loop(s: String): List[Symbol] = {
      if (s.isNone || s.isRootPackage) RootPackage :: Nil
      else if (s.isEmptyPackage) EmptyPackageVal :: Nil
      else if (s.isPackage) {
        try {
          requiredPackage(s.stripSuffix("/").replace("/", ".")) :: Nil
        } catch {
          case NonFatal(_) =>
            Nil
        }
      } else {
        val (desc, parent) = DescriptorParser(s)
        val parentSymbol = loop(parent)

        def tryMember(sym: Symbol): List[Symbol] =
          sym match {
            case NoSymbol =>
              Nil
            case owner =>
              desc match {
                case Descriptor.None =>
                  Nil
                case Descriptor.Type(value) =>
                  val member = owner.info.decl(typeName(value)).symbol :: Nil
                  if (sym.is(JavaDefined))
                    owner.info.decl(termName(value)).symbol :: member
                  else member
                case Descriptor.Term(value) =>
                  owner.info.decl(termName(value)).symbol :: Nil
                case Descriptor.Package(value) =>
                  owner.info.decl(termName(value)).symbol :: Nil
                case Descriptor.Parameter(value) =>
                  // TODO - need to check how to implement this properly
                  //owner.paramSymss.flatten.filter(_.name.containsName(value))
                  Nil
                case Descriptor.TypeParameter(value) =>
                  // TODO - need to check how to implement this properly
                  //owner.typeParams.filter(_.name.containsName(value))
                  Nil
                case Descriptor.Method(value, _) =>
                  // TODO - need to check how to implement this properly
                  // owner.info
                  //   .decl(termName(value))
                  //   .alternatives
                  //   .iterator
                  //   .filter(sym => semanticdbSymbol(sym) == s)
                  //   .toList
                  Nil
              }
          }

        parentSymbol.flatMap(tryMember)
      }
    }
    try loop(sym).filterNot(_ == NoSymbol)
    catch {
      case NonFatal(e) => Nil
    }
  }
}
