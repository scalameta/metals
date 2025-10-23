package scala.meta.internal.mtags

import scala.meta._
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.semanticdb.SymbolInformation.Property
import scala.meta.internal.trees._
import scala.meta.parsers.Parsed
import scala.meta.transversers.SimpleTraverser

object ScalaMtags {
  def index(input: Input.VirtualFile, dialect: Dialect): MtagsIndexer = {
    new ScalaMtags(input, dialect)
  }
}
class ScalaMtags(
    val input: Input.VirtualFile,
    dialect: Dialect,
    parsedTree: Option[Source] = None
) extends SimpleTraverser
    with MtagsIndexer {

  private val root: Parsed[Source] =
    parsedTree
      .map(Parsed.Success(_))
      .getOrElse(input.safeParse[Source](dialect))

  def source: Source = root.get
  override def language: Language = Language.SCALA
  override def indexRoot(): Unit = {
    if (input.path.contains("circe") || input.path.contains("syntax")) {
      System.err.println(s"[ScalaMtags.indexRoot] Indexing Circe file: ${input.path}")
    }
    root match {
      case Parsed.Success(tree) => apply(tree)
      case _ => // do nothing in case of parse error
    }
  }

  private val implicitClassMembersBuilder =
    List.newBuilder[ImplicitClassMember]
  override def implicitClassMembers(): List[ImplicitClassMember] =
    implicitClassMembersBuilder.result()

  private var _toplevelSourceRef: Option[(String, OverloadDisambiguator)] = None
  private def toplevelSourceData: (String, OverloadDisambiguator) = {
    _toplevelSourceRef match {
      case Some(v) => v
      case None =>
        val srcName = input.filename.stripSuffix(".scala")
        val name = s"$srcName$$package"
        val value = (name, new OverloadDisambiguator())
        _toplevelSourceRef = Some(value)
        withOwner(currentOwner) {
          val pos = Position.Range(input, 0, 0)
          term(name, pos, Kind.OBJECT, 0)
        }
        value
    }
  }
  private def toplevelSourceOwner: String = toplevelSourceData._1
  private def toplevelOverloads: OverloadDisambiguator = toplevelSourceData._2

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
        val old = myCurrentTree
        for {
          params <- paramss
          param <- params
        } {
          myCurrentTree = param
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

        myCurrentTree = old
      }
      def enterTypeParameters(tparams: List[Type.Param]): Unit = {
        val old = myCurrentTree
        for {
          tparam <- tparams
        } {
          myCurrentTree = tparam
          withOwner() {
            super.tparam(tparam.name, Kind.TYPE_PARAMETER, 0)
          }
        }
        myCurrentTree = old
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
          overloads: OverloadDisambiguator,
          properties: Int = 0
      ): Unit = {
        val old = myCurrentTree
        myCurrentTree = member
        val disambiguator = overloads.disambiguator(name.value)
        withOwner() {
          method(name, disambiguator, kind, properties)
          enterTypeParameters(tparams)
          enterTermParameters(paramss, isPrimaryCtor = false)
        }
        myCurrentTree = old
      }
      def enterGivenAlias(
          name: String,
          pos: Position,
          tparams: List[Type.Param],
          paramss: List[List[Term.Param]]
      ): Unit = {
        withFileOwner {
          if (tparams.nonEmpty || paramss.nonEmpty) {
            method(name, "()", pos, Property.IMPLICIT.value)
            enterTypeParameters(tparams)
            enterTermParameters(paramss, isPrimaryCtor = false)

          } else {
            term(
              name,
              pos,
              Kind.METHOD,
              Property.IMPLICIT.value | Property.GIVEN.value
            )
          }
        }
      }
      def enterGiven(
          name: String,
          pos: Position,
          tparams: List[Type.Param],
          paramss: List[List[Term.Param]]
      ): Unit = {
        withFileOwner {
          val ownerKind: String =
            if (tparams.nonEmpty || paramss.nonEmpty) {
              withOwner(owner)(
                method(
                  name,
                  "()",
                  pos,
                  Property.IMPLICIT.value | Property.GIVEN.value
                )
              )

              "#"
            } else {
              withOwner(owner)(
                term(
                  name,
                  pos,
                  Kind.METHOD,
                  Property.IMPLICIT.value | Property.GIVEN.value
                )
              )

              "."
            }

          val nextOwner = s"${currentOwner}${name}${ownerKind}"
          withOwner(nextOwner) {
            enterTypeParameters(tparams)
            enterTermParameters(paramss, isPrimaryCtor = false)
            continue()
          }
        }
      }
      myCurrentTree = tree
      tree match {
        case _: Source => continue()
        case t: Template =>
          val overloads = new OverloadDisambiguator()
          overloads.disambiguator("this") // primary constructor
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
          System.err.println(
            s"[ScalaMtags.loop] Processing class: ${t.name.value}, isImplicit=$isImplicit"
          )
          if (isImplicit) {
            System.err.println(
              s"[ScalaMtags.loop] Found implicit class: ${t.name.value}, calling collectImplicitClassMembers"
            )
            // emit symbol for implicit conversion
            withOwner() {
              method(t.name, "()", Kind.METHOD, Property.IMPLICIT.value)
            }
            // collect implicit class information for indexing
            collectImplicitClassMembers(t)
          }
          val properties = if (t.mods.has[Mod.Case]) Property.CASE.value else 0
          tpe(t.name, Kind.CLASS, properties)
          enterTypeParameters(t.tparams)
          enterTermParameters(t.ctor.paramss, isPrimaryCtor = true)
          continue()
        case t: Defn.Enum =>
          tpe(t.name, Kind.CLASS, Property.ENUM.value)
          enterTypeParameters(t.tparams)
          enterTermParameters(t.ctor.paramss, isPrimaryCtor = true)
          continue()
        case t: Defn.RepeatedEnumCase =>
          t.cases.foreach(c =>
            withOwner(ownerCompanion)(term(c, Kind.OBJECT, 0))
          )
        case t: Defn.EnumCase =>
          t.ctor match {
            case Ctor.Primary(_, _, _ :: _) =>
              withOwner(ownerCompanion) {
                tpe(t.name, Kind.CLASS, 0)
                enterTypeParameters(t.tparams)
                enterTermParameters(t.ctor.paramss, isPrimaryCtor = true)
              }
            case _ => withOwner(ownerCompanion)(term(t.name, Kind.OBJECT, 0))
          }
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
          withFileOwner {
            tpe(t.name, Kind.TYPE, 0); stop()
            enterTypeParameters(t.tparams)
          }
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
          // Register extension parameters for each extension methods (in addDeclDef and addDefnDef)
          // For example, `s` has two symbols
          // - ...package.asInt().(s) and
          // - ...package.double().(s)
          // ```
          // package example
          // extension (s: String) {
          //   def asInt: Int = s.toInt
          //   def double: String = s * 2
          // end extension
          // ```
          // see: https://github.com/scalameta/scalameta/issues/2443
          //      https://github.com/lampepfl/dotty/issues/11690
          val extensionParamss = t.paramss
          val overloads =
            if (isPackageOwner)
              toplevelOverloads
            else
              new OverloadDisambiguator()

          def addDefnDef(t: Defn.Def): Unit =
            withOwner(fileOwner) {
              disambiguatedMethod(
                t,
                t.name,
                Nil,
                t.paramss ++ extensionParamss,
                Kind.CONSTRUCTOR,
                overloads,
                EXTENSION
              )
            }
          def addDeclDef(t: Decl.Def): Unit =
            withOwner(fileOwner) {
              disambiguatedMethod(
                t,
                t.name,
                Nil,
                t.paramss ++ extensionParamss,
                Kind.CONSTRUCTOR,
                overloads,
                EXTENSION
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
            enterGivenAlias(name, t.name.pos, t.tparams, t.sparams)
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
            enterGiven(name, pos, t.tparams, t.sparams)
          }
        case _: Pkg.Body | _: Template.Body =>
          continue()
        case _ =>
          stop()
      }
    }

  /**
   * Use source level package object as an owner for toplevel defition
   */
  private def withFileOwner[A](f: => A): A =
    withOwner(fileOwner)(f)

  private def fileOwner: String =
    if (isPackageOwner)
      symbol(Descriptor.Term(toplevelSourceOwner))
    else currentOwner

  private def isPackageOwner: Boolean = currentOwner.endsWith("/")

  private def givenTpeName(t: Type): Option[String] = {

    def extract(t: Type, level: Int): Option[String] = {
      if (level > 1) None
      else {
        t match {
          case t: Type.Name => Some(t.value)
          case t: Type.Apply =>
            val out =
              (t.tpe :: t.args)
                .flatMap(extract(_, level + 1))
                .mkString("_")
            Some(out)
          case _ => None
        }
      }
    }
    extract(t, 0)
  }

  private def collectImplicitClassMembers(cls: Defn.Class): Unit = {
    cls.ctor.paramss match {
      case List(List(param)) =>
        param.decltpe match {
          case Some(tpe) =>
            val paramTypeSymbol = typeToSymbol(tpe)
            val classSymbol = symbol(Descriptor.Type(cls.name.value))

            System.err.println(
              s"[ScalaMtags] Detected implicit class: ${cls.name.value}, " +
                s"classSymbol=$classSymbol, paramType=$paramTypeSymbol"
            )

            var methodCount = 0
            cls.templ.stats.foreach {
              case defn: Defn.Def if !defn.mods.exists {
                    case Mod.Private(_) => true
                    case Mod.Protected(_) => true
                    case _ => false
                  } =>
                val methodSymbol = Symbols.Global(
                  classSymbol,
                  Descriptor.Method(defn.name.value, "()")
                )
                implicitClassMembersBuilder += ImplicitClassMember(
                  classSymbol = classSymbol,
                  paramType = paramTypeSymbol,
                  methodSymbol = methodSymbol,
                  methodName = defn.name.value,
                  range = cls.pos.toSemanticdb
                )
                methodCount += 1
                System.err.println(
                  s"[ScalaMtags]   Method: ${defn.name.value}, methodSymbol=$methodSymbol"
                )
              case _ =>
            }

            if (methodCount > 0) {
              System.err.println(
                s"[ScalaMtags] Indexed implicit class ${cls.name.value} " +
                  s"with $methodCount methods for type $paramTypeSymbol"
              )
            }
          case None =>
        }
      case _ =>
    }
  }

  private def typeToSymbol(tpe: Type): String = {
    tpe match {
      case Type.Name(value) =>
        s"${value}#"
      case Type.Select(qual, Type.Name(name)) =>
        // Qualified type like scala.Int - preserve the full path
        qualToSymbol(qual) + Descriptor.Type(name).value
      case Type.Apply(base, _) =>
        // Generic type, use base type
        typeToSymbol(base)
      case _ =>
        "Any#"
    }
  }

  private def qualToSymbol(qual: Term.Ref): String = {
    qual match {
      case Term.Name(value) => value + "/"
      case Term.Select(q: Term.Ref, Term.Name(name)) =>
        qualToSymbol(q) + name + "/"
      case _ => ""
    }
  }
}
