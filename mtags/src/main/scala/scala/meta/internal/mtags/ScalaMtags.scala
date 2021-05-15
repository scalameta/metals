package scala.meta.internal.mtags

import scala.meta._
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.semanticdb.SymbolInformation.Property
import scala.meta.internal.trees._
import scala.meta.transversers.SimpleTraverser

object ScalaMtags {
  def index(input: Input.VirtualFile, dialect: Dialect): MtagsIndexer = {
    new ScalaMtags(input, dialect)
  }
}
class ScalaMtags(val input: Input.VirtualFile, dialect: Dialect)
    extends SimpleTraverser
    with MtagsIndexer {

  private val root: Parsed[Source] =
    dialect(input).parse[Source]
  def source: Source = root.get
  override def language: Language = Language.SCALA
  override def indexRoot(): Unit = {
    root match {
      case Parsed.Success(tree) => apply(tree)
      case _ => // do nothing in case of parse error
    }
  }

  private var _toplevelSourceRef: Option[(String, OverloadDisambiguator)] = None
  private def topleveSourceData: (String, OverloadDisambiguator) = {
    _toplevelSourceRef match {
      case Some(v) => v
      case None =>
        val srcName = input.filename.stripSuffix(".scala")
        val name = s"$srcName$$package"
        val value = (s"$name.", new OverloadDisambiguator())
        _toplevelSourceRef = Some(value)
        withOwner(currentOwner) {
          val pos = Position.Range(input, 0, 0)
          term(name, pos, Kind.OBJECT, 0)
        }
        value
    }
  }
  private def toplevelSourceOwner: String = topleveSourceData._1
  private def toplevelOverloads: OverloadDisambiguator = topleveSourceData._2

  def currentTree: Tree = myCurrentTree
  private var myCurrentTree: Tree = Source(Nil)
  override def apply(tree: Tree): Unit =
    withOwner() {
      def continue(): Unit = super.apply(tree)
      def stop(): Unit = ()
      def enterTermParameters(
          paramss: List[List[Term.Param]],
          isPrimaryCtor: Boolean
      ): Unit = {
        for {
          params <- paramss
          param <- params
        } {
          withOwner() {
            if (isPrimaryCtor) {
              param.name match {
                case name: Term.Name =>
                  term(name, Kind.METHOD, Property.VAL.value)
                case _ =>
              }
            } else {
              super.param(param.name, Kind.PARAMETER, 0)
            }
          }
        }

      }
      def enterTypeParameters(tparams: List[Type.Param]): Unit = {
        for {
          tparam <- tparams
        } {
          withOwner() {
            super.tparam(tparam.name, Kind.TYPE_PARAMETER, 0)
          }
        }
      }
      def enterPatterns(ps: List[Pat], kind: Kind, properties: Int): Unit = {
        ps.foreach { pat =>
          pat.traverse {
            case Pat.Var(name) =>
              withOwner() {
                if (kind.isMethod && properties == Property.VAL.value) {
                  term(name, kind, properties)
                } else {
                  method(name, "()", kind, properties)
                }
              }
            case _ =>
          }
        }
      }
      def disambiguatedMethod(
          member: Member,
          name: Name,
          tparams: List[Type.Param],
          paramss: List[List[Term.Param]],
          kind: Kind,
          overloads: OverloadDisambiguator
      ): Unit = {
        val old = myCurrentTree
        myCurrentTree = member
        val disambiguator = overloads.disambiguator(name.value)
        withOwner() {
          method(name, disambiguator, kind, 0)
          enterTypeParameters(tparams)
          enterTermParameters(paramss, isPrimaryCtor = false)
        }
        myCurrentTree = old
      }
      myCurrentTree = tree
      tree match {
        case _: Source => continue()
        case t: Template =>
          val overloads = new OverloadDisambiguator()
          overloads.disambiguator("") // primary constructor
          t.stats.foreach {
            case t: Ctor.Secondary =>
              disambiguatedMethod(
                t,
                t.name,
                Nil,
                t.paramss,
                Kind.CONSTRUCTOR,
                overloads
              )
            case t: Defn.Def =>
              disambiguatedMethod(
                t,
                t.name,
                t.tparams,
                t.paramss,
                Kind.METHOD,
                overloads
              )
            case t: Decl.Def =>
              disambiguatedMethod(
                t,
                t.name,
                t.tparams,
                t.paramss,
                Kind.METHOD,
                overloads
              )
            case t: Decl.Given =>
              disambiguatedMethod(
                t,
                t.name,
                t.tparams,
                List.empty,
                Kind.METHOD,
                overloads
              )
            case _ =>
          }
          continue()
        case t: Pkg => pkg(t.ref); continue()
        case t: Pkg.Object =>
          if (currentOwner eq Symbols.EmptyPackage) {
            currentOwner = Symbols.RootPackage
          }
          currentOwner =
            Symbols.Global(currentOwner, Descriptor.Package(t.name.value))
          term("package", t.name.pos, Kind.OBJECT, 0)
          continue()
        case t: Defn.Class =>
          val isImplicit = t.mods.has[Mod.Implicit]
          if (isImplicit) {
            // emit symbol for implicit conversion
            withOwner() {
              method(t.name, "()", Kind.METHOD, Property.IMPLICIT.value)
            }
          }
          tpe(t.name, Kind.CLASS, 0)
          enterTypeParameters(t.tparams)
          enterTermParameters(t.ctor.paramss, isPrimaryCtor = true)
          continue()
        case t: Defn.Enum =>
          withOwner(currentOwner) {
            tpe(t.name, Kind.CLASS, 0)
            enterTypeParameters(t.tparams)
            enterTermParameters(t.ctor.paramss, isPrimaryCtor = true)
          }
          term(t.name.value, t.name.pos, Kind.OBJECT, 0)
          continue()
        case t: Defn.RepeatedEnumCase =>
          t.cases.foreach(c => withOwner(currentOwner)(term(c, Kind.OBJECT, 0)))
        case t: Defn.EnumCase =>
          term(t.name, Kind.OBJECT, 0)
        case t: Defn.Trait =>
          tpe(t.name, Kind.TRAIT, 0); continue()
          enterTypeParameters(t.tparams)
        case t: Defn.Object =>
          term(t.name, Kind.OBJECT, 0); continue()
        case t: Defn.Type =>
          withFileOwner {
            tpe(t.name, Kind.TYPE, 0); stop()
            enterTypeParameters(t.tparams)
          }
        case t: Decl.Type =>
          tpe(t.name, Kind.TYPE, 0); stop()
          enterTypeParameters(t.tparams)
        case t: Defn.Val =>
          withFileOwner {
            enterPatterns(t.pats, Kind.METHOD, Property.VAL.value)
            stop()
          }
        case t: Decl.Val =>
          enterPatterns(t.pats, Kind.METHOD, Property.VAL.value); stop()
        case t: Defn.Var =>
          withFileOwner {
            enterPatterns(t.pats, Kind.METHOD, Property.VAR.value); stop()
          }
        case t: Decl.Var =>
          withFileOwner {
            enterPatterns(t.pats, Kind.METHOD, Property.VAR.value); stop()
          }
        case t: Defn.Def =>
          if (isPackageOwner) {
            withOwner(fileOwner) {
              val disambiguator = toplevelOverloads.disambiguator(t.name.value)
              method(t.name, disambiguator, Kind.METHOD, 0)
              enterTypeParameters(t.tparams)
              enterTermParameters(t.paramss, isPrimaryCtor = false)
            }
          }
        case t: Defn.ExtensionGroup =>
          // t.params are ignored - don't know which symbol/owner they should have
          // need to wait for https://github.com/lampepfl/dotty/issues/11690
          val (owner, overloads) =
            if (isPackageOwner)
              topleveSourceData
            else
              (currentOwner, new OverloadDisambiguator())

          def addDefnDef(t: Defn.Def): Unit =
            withOwner(owner) {
              disambiguatedMethod(
                t,
                t.name,
                Nil,
                t.paramss,
                Kind.CONSTRUCTOR,
                overloads
              )
            }
          def addDeclDef(t: Decl.Def): Unit =
            withOwner(owner) {
              disambiguatedMethod(
                t,
                t.name,
                Nil,
                t.paramss,
                Kind.CONSTRUCTOR,
                overloads
              )
            }

          t.body match {
            case block: Term.Block =>
              block.stats.foreach {
                case d: Defn.Def => addDefnDef(d)
                case d: Decl.Def => addDeclDef(d)
                case _ =>
              }
            case d: Defn.Def => addDefnDef(d)
            case d: Decl.Def => addDeclDef(d)
            case _ =>
          }
        case t: Defn.GivenAlias =>
          val nameOpt =
            t.name match {
              case Name.Anonymous() =>
                givenTpeName(t.decltpe).map(n => s"given_$n")
              case _ => Some(t.name.value)
            }
          nameOpt.foreach { name =>
            withFileOwner {
              term(name, t.name.pos, Kind.METHOD, Property.IMPLICIT.value)
              continue()
            }
          }
        case t: Defn.Given =>
          val namePos =
            t.name match {
              case Name.Anonymous() =>
                for {
                  init <- t.templ.inits.headOption
                  tpeName <- givenTpeName(init.tpe)
                } yield (s"given_$tpeName", init.pos)
              case _ =>
                Some((t.name.value, t.name.pos))
            }

          namePos.foreach { case (name, pos) =>
            withFileOwner {
              withOwner(owner) {
                term(name, pos, Kind.METHOD, Property.IMPLICIT.value)
                continue()
              }
            }
          }
        case _ => stop()
      }
    }

  /**
   * Use source level package object as an owner for toplevel defition
   */
  private def withFileOwner[A](f: => A): A =
    withOwner(fileOwner)(f)

  private def fileOwner: String =
    if (isPackageOwner)
      s"$currentOwner$toplevelSourceOwner"
    else currentOwner

  private def isPackageOwner: Boolean = currentOwner.endsWith("/")

  private def givenTpeName(t: Type): Option[String] = {
    t match {
      case t: Type.Name => Some(t.value)
      case t: Type.Apply =>
        val out = List(t.tpe, t.args.head).flatMap(givenTpeName).mkString("_")
        Some(out)
      case _ => None
    }
  }
}
