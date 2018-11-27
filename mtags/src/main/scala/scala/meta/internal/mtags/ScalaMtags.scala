package scala.meta.internal.mtags

import scala.meta._
import scala.meta.inputs.Input
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.semanticdb.SymbolInformation.Property
import scala.meta.internal.tokenizers.PlatformTokenizerCache
import scala.meta.internal.semanticdb.Scala._

// TODO, emit correct method overload symbols https://github.com/scalameta/metals/issues/282
object ScalaMtags {
  def index(input: Input.VirtualFile): MtagsIndexer = {
    val root: Option[Source] = input.parse[Source].toOption
    val virtualFile = input
    new Traverser with MtagsIndexer {
      override def language: Language = Language.SCALA
      override def input: Input.VirtualFile = virtualFile
      override def indexRoot(): Unit = {
        root match {
          case Some(tree) => apply(tree)
          case _ => // do nothing in case of parse error
        }

        // :facepalm: https://github.com/scalameta/scalameta/issues/1068
        PlatformTokenizerCache.megaCache.clear()
      }
      override def apply(tree: Tree): Unit = withOwner() {
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
        tree match {
          case _: Source => continue()
          case t: Template =>
            val overloads = new OverloadDisambiguator()
            overloads.disambiguator("") // primary constructor
            def disambiguatedMethod(
                name: Name,
                tparams: List[Type.Param],
                paramss: List[List[Term.Param]],
                kind: Kind
            ): Unit = {
              val disambiguator = overloads.disambiguator(name.value)
              withOwner() {
                method(name, disambiguator, kind, 0)
                enterTypeParameters(tparams)
                enterTermParameters(paramss, isPrimaryCtor = false)
              }
            }
            t.stats.foreach {
              case t: Ctor.Secondary =>
                disambiguatedMethod(t.name, Nil, t.paramss, Kind.CONSTRUCTOR)
              case t: Defn.Def =>
                disambiguatedMethod(t.name, t.tparams, t.paramss, Kind.METHOD)
              case t: Decl.Def =>
                disambiguatedMethod(t.name, t.tparams, t.paramss, Kind.METHOD)
              case _ =>
            }
            continue()
          case t: Pkg => pkg(t.ref); continue()
          case t: Pkg.Object =>
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
          case t: Defn.Trait =>
            tpe(t.name, Kind.TRAIT, 0); continue()
            enterTypeParameters(t.tparams)
          case t: Defn.Object =>
            term(t.name, Kind.OBJECT, 0); continue()
          case t: Defn.Type =>
            tpe(t.name, Kind.TYPE, 0); stop()
            enterTypeParameters(t.tparams)
          case t: Decl.Type =>
            tpe(t.name, Kind.TYPE, 0); stop()
            enterTypeParameters(t.tparams)
          case t: Defn.Val =>
            enterPatterns(t.pats, Kind.METHOD, Property.VAL.value); stop()
          case t: Decl.Val =>
            enterPatterns(t.pats, Kind.METHOD, Property.VAL.value); stop()
          case t: Defn.Var =>
            enterPatterns(t.pats, Kind.METHOD, Property.VAR.value); stop()
          case t: Decl.Var =>
            enterPatterns(t.pats, Kind.METHOD, Property.VAR.value); stop()
          case _ => stop()
        }
      }
    }
  }

}
