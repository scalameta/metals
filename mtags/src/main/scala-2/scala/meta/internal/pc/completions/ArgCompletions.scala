package scala.meta.internal.pc.completions

import scala.collection.immutable.Nil

import scala.meta.internal.pc.Identifier
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait ArgCompletions { this: MetalsGlobal =>

  case class ArgCompletion(
      ident: Ident,
      apply: Apply,
      pos: Position,
      text: String,
      completions: CompletionResult
  ) extends CompletionPosition {
    val editRange: l.Range =
      pos.withStart(ident.pos.start).withEnd(pos.start).toLsp
    val funPos = apply.fun.pos
    val method: Tree = typedTreeAt(funPos)
    val methodSym = method.symbol
    lazy val baseParamss: List[List[Symbol]] = {
      if (methodSym.isModule) getParamsFromObject(methodSym)
      else if (
        methodSym.isMethod && methodSym.decodedName == "apply" && methodSym.owner.isModuleClass
      ) getParamsFromObject(methodSym.owner)
      else if (methodSym.isClass) List(methodSym.constrParamAccessors)
      else if (method.tpe == null)
        method match {
          case Ident(name) =>
            metalsScopeMembers(funPos)
              .map {
                case m: Member
                    if m.symNameDropLocal == name && m.sym != NoSymbol && m.sym.paramss.nonEmpty && checkIfArgsMatch(
                      m.sym.paramss.head
                    ) =>
                  m.sym.paramss.head
                case _ => Nil
              }
          case _ => Nil
        }
      else {
        method.tpe match {
          case OverloadedType(_, alts) =>
            alts.flatMap(_.info.paramss.headOption)
          case _ =>
            List(
              method.tpe.paramss.headOption
                .getOrElse(methodSym.paramss.flatten)
            )
        }
      }
    }

    def checkIfArgsMatch(possibleMatch: List[Symbol]): Boolean = {
      apply.args.length <= possibleMatch.length &&
      !apply.args.zipWithIndex.exists {
        case (Ident(name), _) if name.decodedName.endsWith(CURSOR) => false
        case (AssignOrNamedArg(Ident(name), rhs), _) =>
          !possibleMatch.exists { param =>
            name.decodedName == param.name.decodedName &&
            (rhs.tpe == null || rhs.tpe <:< param.tpe)
          }
        case (rhs, i) =>
          rhs.tpe != null && !(rhs.tpe <:< possibleMatch(i).tpe)
      }
    }
    lazy val baseParamssWithisNamed: List[(List[Symbol], Set[Name])] =
      baseParamss.map { baseParams =>
        val isNamed =
          apply.args.iterator
            .filterNot(_ == ident)
            .zip(baseParams.iterator)
            .map {
              case (AssignOrNamedArg(Ident(name), _), _) =>
                name
              case (_, param) =>
                param.name
            }
            .toSet
        (baseParams, isNamed)
      }
    val prefix: String = ident.name.toString.stripSuffix(CURSOR)
    lazy val allParams: List[Symbol] = baseParamssWithisNamed.flatMap {
      case (baseParams, isNamed) =>
        baseParams.iterator.filterNot { param =>
          isNamed(param.name) ||
          param.isSynthetic
        }.toList
    }
    lazy val params: List[Symbol] =
      allParams
        .filter(param => param.name.startsWith(prefix))
        .foldLeft(List.empty[Symbol])((acc, curr) =>
          if (acc.exists(el => el.name == curr.name && el.tpe == curr.tpe)) acc
          else curr :: acc
        )
        .reverse
    lazy val isParamName: Set[String] = params.iterator
      .map(_.name)
      .map(_.toString().trim())
      .toSet

    def isName(m: Member): Boolean =
      isParamName(m.sym.nameString.trim())

    override def compare(o1: Member, o2: Member): Int = {
      val byName = -java.lang.Boolean.compare(isName(o1), isName(o2))
      if (byName != 0) byName
      else {
        java.lang.Boolean.compare(
          o1.isInstanceOf[NamedArgMember],
          o2.isInstanceOf[NamedArgMember]
        )
      }
    }

    override def isPrioritized(member: Member): Boolean = {
      member.isInstanceOf[NamedArgMember] ||
      isParamName(member.sym.name.toString().trim())
    }

    private def matchingTypesInScope(
        paramType: Type
    ): List[String] = {

      def notNothingOrNull(mem: ScopeMember): Boolean = {
        !(mem.sym.tpe =:= definitions.NothingTpe || mem.sym.tpe =:= definitions.NullTpe)
      }

      completions match {
        case members: CompletionResult.ScopeMembers =>
          members.results
            .collect {
              case mem
                  if mem.sym.tpe <:< paramType && notNothingOrNull(
                    mem
                  ) && mem.sym.isTerm =>
                mem.sym.name.toString().trim()
            }
            // None and Nil are always in scope
            .filter(name => name != "Nil" && name != "None")
        case _ =>
          Nil
      }
    }

    private def findDefaultValue(param: Symbol): String = {
      val matchingType = matchingTypesInScope(param.tpe)
      if (matchingType.size == 1) {
        s":${matchingType.head}"
      } else if (matchingType.size > 1) {
        s"|???,${matchingType.mkString(",")}|"
      } else {
        ":???"
      }
    }

    private def fillAllFields(): List[TextEditMember] = {
      val suffix = "autofill"
      val shouldShow =
        allParams.exists(param => param.name.startsWith(prefix))
      val isExplicitlyCalled = suffix.startsWith(prefix)
      val hasParamsToFill = allParams.count(!_.hasDefault) > 1
      if (
        baseParamss.length == 1 && (shouldShow || isExplicitlyCalled) && hasParamsToFill && clientSupportsSnippets
      ) {
        val editText = allParams.zipWithIndex
          .collect {
            case (param, index) if !param.hasDefault => {
              s"${Identifier.backtickWrap(param.name).replace("$", "$$")} = $${${index + 1}${findDefaultValue(param)}}"
            }
          }
          .mkString(", ")
        val edit = new l.TextEdit(editRange, editText)
        List(
          new TextEditMember(
            filterText = s"$prefix-$suffix",
            edit = edit,
            methodSym,
            label = Some("Autofill with default values")
          )
        )
      } else {
        List.empty
      }
    }

    private def findPossibleDefaults(): List[TextEditMember] = {
      params.flatMap { param =>
        val allMembers = matchingTypesInScope(param.tpe)
        allMembers.map { memberName =>
          val editText =
            Identifier.backtickWrap(param.name) + " = " + memberName
          val edit = new l.TextEdit(editRange, editText)
          new TextEditMember(
            filterText = param.name.toString(),
            edit = edit,
            completionsSymbol(s"$param=$memberName"),
            label = Some(editText),
            detail = Some(" : " + param.tpe)
          )
        }
      }
    }

    private def getParamsFromObject(objectSym: Symbol): List[List[Symbol]] = {
      objectSym.info.members.collect {
        case m
            if m.decodedName == "apply" && m.paramss.nonEmpty && checkIfArgsMatch(
              m.paramss.head
            ) =>
          m.paramss.head
      }.toList
    }

    override def contribute: List[Member] = {
      params.distinct.map(param =>
        new NamedArgMember(param)
      ) ::: findPossibleDefaults() ::: fillAllFields()
    }
  }
}
