package scala.meta.internal.metals

import scala.meta.internal.mtags.DefinitionAlternatives.GlobalSymbol
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.XtensionSemanticdbSymbolInformation

class SymbolAlternatives(symbol: String, name: String) {

  // Returns true if `info` is the companion object matching the occurrence class symbol.
  def isCompanionObject(info: SymbolInformation): Boolean =
    info.isObject &&
      info.displayName == name &&
      symbol == Symbols.Global(
        info.symbol.owner,
        Descriptor.Type(info.displayName),
      )

  // Returns true if `info` is the java constructor matching the occurrence class symbol.
  def isJavaConstructor(info: SymbolInformation): Boolean = {
    info.isConstructor &&
    (Symbol(info.symbol) match {
      case GlobalSymbol(clsSymbol, Descriptor.Method("<init>", _)) =>
        symbol == clsSymbol.value
      case _ =>
        false
    })
  }

  // Returns true if `info` is the companion class matching the occurrence object symbol.
  def isCompanionClass(info: SymbolInformation): Boolean = {
    info.isClass &&
    info.displayName == name &&
    symbol == Symbols.Global(
      info.symbol.owner,
      Descriptor.Term(info.displayName),
    )
  }

  // Returns true if `info` is a named parameter of the primary constructor
  def isContructorParam(info: SymbolInformation): Boolean = {
    info.isParameter &&
    info.displayName == name &&
    symbol == (Symbol(info.symbol) match {
      case GlobalSymbol(
            // This means it's the primary constructor
            GlobalSymbol(owner, Descriptor.Method("<init>", "()")),
            Descriptor.Parameter(_),
          ) =>
        Symbols.Global(owner.value, Descriptor.Term(name))
      case _ =>
        ""
    })
  }

  // Returns true if `info` is a field that corresponds to named parameter of the primary constructor
  def isFieldParam(info: SymbolInformation): Boolean = {
    (info.isVal || info.isVar) &&
    info.displayName == name &&
    symbol == (Symbol(info.symbol) match {
      case GlobalSymbol(owner, Descriptor.Term(name)) =>
        Symbols.Global(
          // This means it's the primary constructor
          Symbols.Global(owner.value, Descriptor.Method("<init>", "()")),
          Descriptor.Parameter(name),
        )
      case _ =>
        ""
    })
  }

  // Returns true if `info` is a parameter of a synthetic `copy` or `apply` matching the occurrence field symbol.
  def isCopyOrApplyParam(info: SymbolInformation): Boolean =
    info.isParameter &&
      info.displayName == name &&
      symbol == (Symbol(info.symbol) match {
        case GlobalSymbol(
              GlobalSymbol(
                GlobalSymbol(owner, Descriptor.Term(obj)),
                Descriptor.Method("apply", _),
              ),
              _,
            ) =>
          Symbols.Global(
            Symbols.Global(owner.value, Descriptor.Type(obj)),
            Descriptor.Term(name),
          )
        case GlobalSymbol(
              GlobalSymbol(
                GlobalSymbol(owner, Descriptor.Type(obj)),
                Descriptor.Method("copy", _),
              ),
              _,
            ) =>
          Symbols.Global(
            Symbols.Global(owner.value, Descriptor.Type(obj)),
            Descriptor.Term(name),
          )
        case _ =>
          ""
      })

  // Returns true if `info` is companion var setter method for occ.symbol var getter.
  def isVarSetter(info: SymbolInformation): Boolean =
    info.displayName.endsWith("_=") &&
      info.displayName.startsWith(name) &&
      symbol == (Symbol(info.symbol) match {
        case GlobalSymbol(owner, Descriptor.Method(setter, disambiguator)) =>
          Symbols.Global(
            owner.value,
            Descriptor.Method(setter.stripSuffix("_="), disambiguator),
          )
        case _ =>
          ""
      })
}

object SymbolAlternatives {

  /**
   * Expands a symbol to include alternatives for Java matching.
   * This includes:
   * - For type symbols: constructors
   * - For object member symbols: class member equivalents (Java sees Scala objects as classes)
   */
  def expand(symbol: Symbol, maxConstructors: Int = 10): Seq[String] = {
    if (symbol.isType) {
      val constructors = 0.until(maxConstructors).map { i =>
        Symbols.Global(
          symbol.value,
          Descriptor.Method("<init>", if (i == 0) "()" else s"(+$i)"),
        )
      }
      symbol.value +: constructors
    } else {
      // For members of Scala objects, Java sees them as members of a class.
      // Scala uses `.` for object (term) members, Java uses `#` for class (type) members.
      val javaAlternative = objectMemberToClassMember(symbol)
      javaAlternative.toList :+ symbol.value
    }
  }

  /**
   * Converts a Scala object member symbol to its Java class member equivalent.
   * For example:
   * - `a/ScalaUtils.hello().` (method in object) → `a/ScalaUtils#hello().`
   * - `a/ScalaUtils.count.` (val in object) → `a/ScalaUtils#count().` (Java accesses vals as methods)
   */
  private def objectMemberToClassMember(symbol: Symbol): Option[String] = {
    val owner = symbol.owner
    // Check if the owner is an object (term descriptor)
    if (owner.isTerm && !owner.isPackage) {
      val ownerName = owner.displayName
      val ownerOwner = owner.owner
      // Convert the term owner to a type owner
      val classOwner =
        Symbols.Global(ownerOwner.value, Descriptor.Type(ownerName))
      symbol.value.desc match {
        case Descriptor.Method(name, disambiguator) =>
          // Method in object → method in class
          Some(
            Symbols.Global(classOwner, Descriptor.Method(name, disambiguator))
          )
        case Descriptor.Term(name) =>
          // Val in object → getter method in class (Java accesses vals as methods)
          Some(Symbols.Global(classOwner, Descriptor.Method(name, "()")))
        case _ =>
          None
      }
    } else {
      None
    }
  }

  /**
   * Returns alternative symbols that should be considered as references
   * to the given symbol. This includes:
   * - Companion objects/classes
   * - Apply/copy method parameters for case classes
   * - Constructor parameters
   * - Field parameters
   * - Var setters
   *
   * @param symbol The symbol to find alternatives for
   * @param doc The SemanticDB document containing symbol information
   * @param isJava Whether the source file is Java
   * @return A set of alternative symbol strings
   */
  def referenceAlternatives(
      symbol: String,
      doc: TextDocument,
      isJava: Boolean = false,
  ): Set[String] = {
    val name = symbol.desc.name.value
    val alternatives = new SymbolAlternatives(symbol, name)

    def candidates(check: SymbolInformation => Boolean): Seq[String] = for {
      info <- doc.symbols
      if check(info)
    } yield info.symbol

    val isCandidate =
      if (isJava)
        candidates(alternatives.isJavaConstructor).toSet
      else
        candidates { info =>
          alternatives.isVarSetter(info) ||
          alternatives.isCompanionObject(info) ||
          alternatives.isCompanionClass(info) ||
          alternatives.isCopyOrApplyParam(info) ||
          alternatives.isContructorParam(info)
        }.toSet

    val nonSyntheticSymbols = for {
      occ <- doc.occurrences
      if isCandidate(occ.symbol) || occ.symbol == symbol
      if occ.role.isDefinition
    } yield occ.symbol

    val isSyntheticSymbol = !nonSyntheticSymbols.contains(symbol)

    val additionalAlternativesForSynthetic =
      if (!isJava && isSyntheticSymbol) {
        for {
          info <- doc.symbols
          if info.symbol != name
          if alternatives.isCompanionClass(info) ||
            alternatives.isFieldParam(info)
        } yield info.symbol
      } else {
        Seq.empty
      }

    if (isJava)
      isCandidate
    else if (isSyntheticSymbol)
      isCandidate ++ additionalAlternativesForSynthetic
    else
      isCandidate
  }
}
