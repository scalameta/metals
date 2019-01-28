package scala.meta.internal.docstrings

import scala.collection._

/** A type. Note that types and templates contain the same information only for the simplest types. For example, a type
 * defines how a template's type parameters are instantiated (as in `List[Cow]`), what the template's prefix is
 * (as in `johnsFarm.Cow`), and supports compound or structural types. */
abstract class TypeEntity {

  /** The human-readable representation of this type. */
  def name: String

  /** Maps which parts of this type's name reference entities. The map is indexed by the position of the first
   * character that reference some entity, and contains the entity and the position of the last referenced
   * character. The referenced character ranges do not to overlap or nest. The map is sorted by position. */
  def refEntity: SortedMap[Int, (LinkTo, Int)]

  /** The human-readable representation of this type. */
  override def toString = name
}

/** An entity in a Scaladoc universe. Entities are declarations in the program and correspond to symbols in the
 * compiler. Entities model the following Scala concepts:
 *  - classes and traits;
 *  - objects and package;
 *  - constructors;
 *  - methods;
 *  - values, lazy values, and variables;
 *  - abstract type members and type aliases;
 *  - type and value parameters;
 *  - annotations. */
trait Entity {

  /** The name of the entity. Note that the name does not qualify this entity uniquely; use its `qualifiedName`
   * instead. */
  def name: String

  /** The qualified name of the entity. This is this entity's name preceded by the qualified name of the template
   * of which this entity is a member. The qualified name is unique to this entity. */
  def qualifiedName: String

  /** The template of which this entity is a member. */
  def inTemplate: TemplateEntity

  /** The list of entities such that each is a member of the entity that follows it; the first entity is always this
   * entity, the last the root package entity. */
  def toRoot: List[Entity]

  /** The qualified name of this entity. */
  override def toString = qualifiedName

//  /** The Scaladoc universe of which this entity is a member. */
//  def universe: Universe

  /** The annotations attached to this entity, if any. */
  def annotations: List[Annotation]

  /** The kind of the entity */
  def kind: String

  /** Whether or not the template was defined in a package object */
  def inPackageObject: Boolean

  /** Indicates whether this entity lives in the types namespace (classes, traits, abstract/alias types) */
  def isType: Boolean
}

object Entity {
  private def isDeprecated(x: Entity) = x match {
    case x: MemberEntity => x.deprecation.isDefined
    case _ => false
  }

  private def isObject(x: Entity) = x match {
    case x: TemplateEntity => x.isObject
    case _ => false
  }

  /** Ordering deprecated things last. */
  implicit lazy val EntityOrdering: Ordering[Entity] =
    Ordering[(Boolean, String, Boolean)] on (
        x => (isDeprecated(x), x.qualifiedName, isObject(x))
    )
}

/** A template, which is either a class, trait, object or package. Depending on whether documentation is available
 * or not, the template will be modeled as a [scala.tools.nsc.doc.model.NoDocTemplate] or a
 * [scala.tools.nsc.doc.model.DocTemplateEntity]. */
trait TemplateEntity extends Entity {

  /** Whether this template is a package (including the root package). */
  def isPackage: Boolean

  /** Whether this template is the root package. */
  def isRootPackage: Boolean

  /** Whether this template is a trait. */
  def isTrait: Boolean

  /** Whether this template is a class. */
  def isClass: Boolean

  /** Whether this template is an object. */
  def isObject: Boolean

  /** Whether documentation is available for this template. */
  def isDocTemplate: Boolean

  /** Whether this template is a case class. */
  def isCaseClass: Boolean

  /** The self-type of this template, if it differs from the template type. */
  def selfType: Option[TypeEntity]
}

/** An entity that is a member of a template. All entities, including templates, are member of another entity
 * except for parameters and annotations. Note that all members of a template are modelled, including those that are
 * inherited and not declared locally. */
trait MemberEntity extends Entity {

  /** The comment attached to this member, if any. */
  def comment: Option[Comment]

  /** The group this member is from */
  def group: String

  /** The template of which this entity is a member. */
  def inTemplate: DocTemplateEntity

  /** The list of entities such that each is a member of the entity that follows it; the first entity is always this
   * member, the last the root package entity. */
  def toRoot: List[MemberEntity]

  /** The templates in which this member has been declared. The first element of the list is the template that contains
   * the currently active declaration of this member, subsequent elements are declarations that have been overridden. If
   * the first element is equal to `inTemplate`, the member is declared locally, if not, it has been inherited. All
   * elements of this list are in the linearization of `inTemplate`. */
  def inDefinitionTemplates: List[TemplateEntity]

  /** The qualified name of the member in its currently active declaration template. */
  def definitionName: String

//  /** The visibility of this member. Note that members with restricted visibility may not be modeled in some
//   * universes. */
//  def visibility: Visibility

  /** The flags that have been set for this entity. The following flags are supported: `implicit`, `sealed`, `abstract`,
   * and `final`. */
  def flags: List[Paragraph]

  /** Some deprecation message if this member is deprecated, or none otherwise. */
  def deprecation: Option[Body]

  /** Some migration warning if this member has a migration annotation, or none otherwise. */
  def migration: Option[Body]

  /** For members representing values: the type of the value returned by this member; for members
   * representing types: the type itself. */
  def resultType: TypeEntity

  /** Whether this member is a method. */
  def isDef: Boolean

  /** Whether this member is a value (this excludes lazy values). */
  def isVal: Boolean

  /** Whether this member is a lazy value. */
  def isLazyVal: Boolean

  /** Whether this member is a variable. */
  def isVar: Boolean

  /** Whether this member is a constructor. */
  def isConstructor: Boolean

  /** Whether this member is an alias type. */
  def isAliasType: Boolean

  /** Whether this member is an abstract type. */
  def isAbstractType: Boolean

  /** Whether this member is abstract. */
  def isAbstract: Boolean

  /** If this symbol is a use case, the useCaseOf will contain the member it was derived from, containing the full
   * signature and the complete parameter descriptions. */
  def useCaseOf: Option[MemberEntity]

  /** If this member originates from an implicit conversion, we set the implicit information to the correct origin */
  def byConversion: Option[ImplicitConversion]

  /** The identity of this member, used for linking */
  def signature: String

  /** Compatibility signature, will be removed from future versions */
  def signatureCompat: String

  /** Indicates whether the member is inherited by implicit conversion */
  def isImplicitlyInherited: Boolean

  /** Indicates whether there is another member with the same name in the template that will take precedence */
  def isShadowedImplicit: Boolean

  /** Indicates whether there are other implicitly inherited members that have similar signatures (and thus they all
   *  become ambiguous) */
  def isAmbiguousImplicit: Boolean

  /** Indicates whether the implicitly inherited member is shadowed or ambiguous in its template */
  def isShadowedOrAmbiguousImplicit: Boolean
}

object MemberEntity {
  // Oh contravariance, contravariance, wherefore art thou contravariance?
  // Note: the above works for both the commonly misunderstood meaning of the line and the real one.
  implicit lazy val MemberEntityOrdering
    : Ordering[MemberEntity] = Entity.EntityOrdering on (x => x)
}

/** An entity that is parameterized by types */
trait HigherKinded {

  /** The type parameters of this entity. */
  def typeParams: List[TypeParam]
}

/** A template (class, trait, object or package) which is referenced in the universe, but for which no further
 * documentation is available. Only templates for which a source file is given are documented by Scaladoc. */
trait NoDocTemplate extends TemplateEntity {
  def kind =
    if (isClass) "class"
    else if (isTrait) "trait"
    else if (isObject) "object"
    else ""
}

/** An inherited template that was not documented in its original owner - example:
 *  in classpath:  trait T { class C } -- T (and implicitly C) are not documented
 *  in the source: trait U extends T -- C appears in U as a MemberTemplateImpl
 *    -- that is, U has a member for it but C doesn't get its own page */
trait MemberTemplateEntity
    extends TemplateEntity
    with MemberEntity
    with HigherKinded {

  /** The value parameters of this case class, or an empty list if this class is not a case class. As case class value
   * parameters cannot be curried, the outer list has exactly one element. */
  def valueParams: List[List[ValueParam]]

  /** The direct super-type of this template
      e.g: {{{class A extends B[C[Int]] with D[E]}}} will have two direct parents: class B and D
      NOTE: we are dropping the refinement here! */
  def parentTypes: List[(TemplateEntity, TypeEntity)]
}

/** A template (class, trait, object or package) for which documentation is available. Only templates for which
 * a source file is given are documented by Scaladoc. */
trait DocTemplateEntity extends MemberTemplateEntity {

  /** The list of templates such that each is a member of the template that follows it; the first template is always
   * this template, the last the root package entity. */
  def toRoot: List[DocTemplateEntity]

//  /** The source file in which the current template is defined and the line where the definition starts, if they exist.
//   * A source file exists for all templates, except for those that are generated synthetically by Scaladoc. */
//  def inSource: Option[(io.AbstractFile, Int)]

  /** An HTTP address at which the source of this template is available, if it is available. An address is available
   * only if the `docsourceurl` setting has been set. */
  def sourceUrl: Option[java.net.URL]

  /** All class, trait and object templates which are part of this template's linearization, in linearization order.
   * This template's linearization contains all of its direct and indirect super-classes and super-traits. */
  def linearizationTemplates: List[TemplateEntity]

  /** All instantiated types which are part of this template's linearization, in linearization order.
   * This template's linearization contains all of its direct and indirect super-types. */
  def linearizationTypes: List[TypeEntity]

  /** All class, trait and object templates for which this template is a *direct* super-class or super-trait.
   *  Only templates for which documentation is available in the universe (`DocTemplateEntity`) are listed. */
  def directSubClasses: List[DocTemplateEntity]

  /** All members of this template. If this template is a package, only templates for which documentation is available
   * in the universe (`DocTemplateEntity`) are listed. */
  def members: List[MemberEntity]

  /** All templates that are members of this template. If this template is a package, only templates for which
   * documentation is available  in the universe (`DocTemplateEntity`) are listed. */
  def templates: List[TemplateEntity with MemberEntity]

  /** All methods that are members of this template. */
  def methods: List[Def]

  /** All values, lazy values and variables that are members of this template. */
  def values: List[Val]

  /** All abstract types that are members of this template. */
  def abstractTypes: List[AbstractType]

  /** All type aliases that are members of this template. */
  def aliasTypes: List[AliasType]

  /** The primary constructor of this class, if it has been defined. */
  def primaryConstructor: Option[Constructor]

  /** All constructors of this class, including the primary constructor. */
  def constructors: List[Constructor]

  /** The companion of this template, or none. If a class and an object are defined as a pair of the same name, the
   * other entity of the pair is the companion. */
  def companion: Option[DocTemplateEntity]

  /** The implicit conversions this template (class or trait, objects and packages are not affected) */
  def conversions: List[ImplicitConversion]

  /** The shadowing information for the implicitly added members */
  def implicitsShadowing: Map[MemberEntity, ImplicitMemberShadowing]

  /** Classes that can be implicitly converted to this class */
  def incomingImplicitlyConvertedClasses
    : List[(DocTemplateEntity, ImplicitConversion)]

  /** Classes to which this class can be implicitly converted to
      NOTE: Some classes might not be included in the scaladoc run so they will be NoDocTemplateEntities */
  def outgoingImplicitlyConvertedClasses
    : List[(TemplateEntity, TypeEntity, ImplicitConversion)]

//  /** If this template takes place in inheritance and implicit conversion relations, it will be shown in this diagram */
//  def inheritanceDiagram: Option[Diagram]
//
//  /** If this template contains other templates, such as classes and traits, they will be shown in this diagram */
//  def contentDiagram: Option[Diagram]

  /** Returns the group description taken either from this template or its linearizationTypes */
  def groupDescription(group: String): Option[Body]

  /** Returns the group description taken either from this template or its linearizationTypes */
  def groupPriority(group: String): Int

  /** Returns the group description taken either from this template or its linearizationTypes */
  def groupName(group: String): String
}

/** A trait template. */
trait Trait extends MemberTemplateEntity {
  def kind = "trait"
}

/** A class template. */
trait Class extends MemberTemplateEntity {
  override def kind = "class"
}

/** An object template. */
trait Object extends MemberTemplateEntity {
  def kind = "object"
}

/** A package template. A package is in the universe if it is declared as a package object, or if it
 * contains at least one template. */
trait Package extends DocTemplateEntity {

  /** The package of which this package is a member. */
  def inTemplate: Package

  /** The package such that each is a member of the package that follows it; the first package is always this
   * package, the last the root package. */
  def toRoot: List[Package]

  /** All packages that are member of this package. */
  def packages: List[Package]

  override def kind = "package"
}

/** The root package, which contains directly or indirectly all members in the universe. A universe
 * contains exactly one root package. */
trait RootPackage extends Package

/** A non-template member (method, value, lazy value, variable, constructor, alias type, and abstract type). */
trait NonTemplateMemberEntity extends MemberEntity {

  /** Whether this member is a use case. A use case is a member which does not exist in the documented code.
   * It corresponds to a real member, and provides a simplified, yet compatible signature for that member. */
  def isUseCase: Boolean
}

/** A method (`def`) of a template. */
trait Def extends NonTemplateMemberEntity with HigherKinded {

  /** The value parameters of this method. Each parameter block of a curried method is an element of the list.
   * Each parameter block is a list of value parameters. */
  def valueParams: List[List[ValueParam]]

  def kind = "method"
}

/** A constructor of a class. */
trait Constructor extends NonTemplateMemberEntity {

  /** Whether this is the primary constructor of a class. The primary constructor is defined syntactically as part of
   * the declaration of the class. */
  def isPrimary: Boolean

  /** The value parameters of this constructor. As constructors cannot be curried, the outer list has exactly one
   * element. */
  def valueParams: List[List[ValueParam]]

  def kind = "constructor"
}

/** A value (`val`), lazy val (`lazy val`) or variable (`var`) of a template. */
trait Val extends NonTemplateMemberEntity {
  def kind = "[lazy] value/variable"
}

/** An abstract type member of a template. */
trait AbstractType extends MemberTemplateEntity with HigherKinded {

  /** The lower bound for this abstract type, if it has been defined. */
  def lo: Option[TypeEntity]

  /** The upper bound for this abstract type, if it has been defined. */
  def hi: Option[TypeEntity]

  def kind = "abstract type"
}

/** An type alias of a template. */
trait AliasType extends MemberTemplateEntity with HigherKinded {

  /** The type aliased by this type alias. */
  def alias: TypeEntity

  def kind = "type alias"
}

/** A parameter to an entity. */
trait ParameterEntity {

  def name: String
}

/** A type parameter to a class, trait, or method. */
trait TypeParam extends ParameterEntity with HigherKinded {

  /** The variance of this type parameter. Valid values are "+", "-", and the empty string. */
  def variance: String

  /** The lower bound for this type parameter, if it has been defined. */
  def lo: Option[TypeEntity]

  /** The upper bound for this type parameter, if it has been defined. */
  def hi: Option[TypeEntity]
}

/** A value parameter to a constructor or method. */
trait ValueParam extends ParameterEntity {

  /** The type of this value parameter. */
  def resultType: TypeEntity

//  /** The default value of this value parameter, if it has been defined. */
//  def defaultValue: Option[TreeEntity]

  /** Whether this value parameter is implicit. */
  def isImplicit: Boolean
}

/** An annotation to an entity. */
trait Annotation extends Entity {

  /** The class of this annotation. */
  def annotationClass: TemplateEntity

//  /** The arguments passed to the constructor of the annotation class. */
//  def arguments: List[ValueArgument]

  def kind = "annotation"
}

/** A trait that signals the member results from an implicit conversion */
trait ImplicitConversion {

  /** The source of the implicit conversion*/
  def source: DocTemplateEntity

  /** The result type after the conversion */
  def targetType: TypeEntity

  /** The components of the implicit conversion type parents */
  def targetTypeComponents: List[(TemplateEntity, TypeEntity)]

  /** The entity for the method that performed the conversion, if it's documented (or just its name, otherwise) */
  def convertorMethod: Either[MemberEntity, String]

  /** A short name of the conversion */
  def conversionShortName: String

  /** A qualified name uniquely identifying the conversion (currently: the conversion method's qualified name) */
  def conversionQualifiedName: String

  /** The entity that performed the conversion */
  def convertorOwner: TemplateEntity

  /** The constraints that the transformations puts on the type parameters */
  def constraints: List[Constraint]

  /** The members inherited by this implicit conversion */
  def members: List[MemberEntity]

  /** Is this a hidden implicit conversion (as specified in the settings) */
  def isHiddenConversion: Boolean
}

/** Shadowing captures the information that the member is shadowed by some other members
 *  There are two cases of implicitly added member shadowing:
 *  1) shadowing from an original class member (the class already has that member)
 *     in this case, it won't be possible to call the member directly, the type checker will fail attempting to adapt
 *     the call arguments (or if they fit it will call the original class method)
 *  2) shadowing from other possible implicit conversions ()
 *     this will result in an ambiguous implicit conversion error
 */
trait ImplicitMemberShadowing {

  /** The members that shadow the current entry use .inTemplate to get to the template name */
  def shadowingMembers: List[MemberEntity]

  /** The members that ambiguate this implicit conversion
      Note: for ambiguatingMembers you have the following invariant:
      assert(ambiguatingMembers.foreach(_.byConversion.isDefined) */
  def ambiguatingMembers: List[MemberEntity]

  def isShadowed: Boolean = shadowingMembers.nonEmpty
  def isAmbiguous: Boolean = ambiguatingMembers.nonEmpty
}

/** A trait that encapsulates a constraint necessary for implicit conversion */
trait Constraint

/** A constraint involving a type parameter which must be in scope */
trait ImplicitInScopeConstraint extends Constraint {

  /** The type of the implicit value required */
  def implicitType: TypeEntity

  /** toString for debugging */
  override def toString =
    "an implicit _: " + implicitType.name + " must be in scope"
}

trait TypeClassConstraint
    extends ImplicitInScopeConstraint
    with TypeParamConstraint {

  /** Type class name */
  def typeClassEntity: TemplateEntity

  /** toString for debugging */
  override def toString =
    typeParamName + " is a class of type " + typeClassEntity.qualifiedName + " (" +
      typeParamName + ": " + typeClassEntity.name + ")"
}

trait KnownTypeClassConstraint extends TypeClassConstraint {

  /** Type explanation, takes the type parameter name and generates the explanation */
  def typeExplanation: (String) => String

  /** toString for debugging */
  override def toString =
    typeExplanation(typeParamName) + " (" + typeParamName + ": " + typeClassEntity.name + ")"
}

/** A constraint involving a type parameter */
trait TypeParamConstraint extends Constraint {

  /** The type parameter involved */
  def typeParamName: String
}

trait EqualTypeParamConstraint extends TypeParamConstraint {

  /** The rhs */
  def rhs: TypeEntity

  /** toString for debugging */
  override def toString =
    typeParamName + " is " + rhs.name + " (" + typeParamName + " =:= " + rhs.name + ")"
}

trait BoundedTypeParamConstraint extends TypeParamConstraint {

  /** The lower bound */
  def lowerBound: TypeEntity

  /** The upper bound */
  def upperBound: TypeEntity

  /** toString for debugging */
  override def toString =
    typeParamName + " is a superclass of " + lowerBound.name + " and a subclass of " +
      upperBound.name + " (" + typeParamName + " >: " + lowerBound.name + " <: " + upperBound.name + ")"
}

trait LowerBoundedTypeParamConstraint extends TypeParamConstraint {

  /** The lower bound */
  def lowerBound: TypeEntity

  /** toString for debugging */
  override def toString =
    typeParamName + " is a superclass of " + lowerBound.name + " (" + typeParamName + " >: " +
      lowerBound.name + ")"
}

trait UpperBoundedTypeParamConstraint extends TypeParamConstraint {

  /** The lower bound */
  def upperBound: TypeEntity

  /** toString for debugging */
  override def toString =
    typeParamName + " is a subclass of " + upperBound.name + " (" + typeParamName + " <: " +
      upperBound.name + ")"
}

sealed trait LinkTo
final case class LinkToMember[Mbr, Tpl](mbr: Mbr, tpl: Tpl) extends LinkTo
final case class LinkToTpl[Tpl](tpl: Tpl) extends LinkTo
final case class LinkToExternalTpl(
    name: String,
    baseUrl: String,
    tpl: TemplateEntity
) extends LinkTo
final case class Tooltip(name: String) extends LinkTo

/** A body of text. A comment has a single body, which is composed of
 * at least one block. Inside every body is exactly one summary.
 * @see [[Summary]]
 */
final case class Body(blocks: Seq[Block]) {

  /** The summary text of the comment body. */
  lazy val summary: Option[Inline] = {
    def summaryInBlock(block: Block): Seq[Inline] = block match {
      case Title(text, _) => summaryInInline(text)
      case Paragraph(text) => summaryInInline(text)
      case UnorderedList(items) => items flatMap summaryInBlock
      case OrderedList(items, _) => items flatMap summaryInBlock
      case DefinitionList(items) => items.values.toSeq flatMap summaryInBlock
      case _ => Nil
    }
    def summaryInInline(text: Inline): Seq[Inline] = text match {
      case Summary(text) => List(text)
      case Chain(items) => items flatMap summaryInInline
      case Italic(text) => summaryInInline(text)
      case Bold(text) => summaryInInline(text)
      case Underline(text) => summaryInInline(text)
      case Superscript(text) => summaryInInline(text)
      case Subscript(text) => summaryInInline(text)
      case Link(_, title) => summaryInInline(title)
      case _ => Nil
    }
    (blocks flatMap { summaryInBlock(_) }).toList match {
      case Nil => None
      case inline :: Nil => Some(inline)
      case inlines => Some(Chain(inlines))
    }
  }
}

/** A block-level element of text, such as a paragraph or code block. */
sealed abstract class Block

final case class Title(text: Inline, level: Int) extends Block
final case class Paragraph(text: Inline) extends Block
final case class Code(data: String) extends Block
final case class UnorderedList(items: Seq[Block]) extends Block
final case class OrderedList(items: Seq[Block], style: String) extends Block
final case class DefinitionList(items: SortedMap[Inline, Block]) extends Block
final case class HorizontalRule() extends Block
final case class Table(
    header: Row,
    columnOptions: Seq[ColumnOption],
    rows: Seq[Row]
) extends Block
final case class ColumnOption(option: Char) {
  require(option == 'L' || option == 'C' || option == 'R')
}
object ColumnOption {
  val ColumnOptionLeft = ColumnOption('L')
  val ColumnOptionCenter = ColumnOption('C')
  val ColumnOptionRight = ColumnOption('R')
}
final case class Row(cells: Seq[Cell])
final case class Cell(blocks: Seq[Block])

/** An section of text inside a block, possibly with formatting. */
sealed abstract class Inline

final case class Chain(items: Seq[Inline]) extends Inline
final case class Italic(text: Inline) extends Inline
final case class Bold(text: Inline) extends Inline
final case class Underline(text: Inline) extends Inline
final case class Superscript(text: Inline) extends Inline
final case class Subscript(text: Inline) extends Inline
final case class Link(target: String, title: Inline) extends Inline
final case class Monospace(text: Inline) extends Inline
final case class Text(text: String) extends Inline
abstract class EntityLink(val title: Inline) extends Inline { def link: LinkTo }
object EntityLink {
  def apply(title: Inline, linkTo: LinkTo) = new EntityLink(title) {
    def link: LinkTo = linkTo
  }
  def unapply(el: EntityLink): Option[(Inline, LinkTo)] =
    Some((el.title, el.link))
}
final case class HtmlTag(data: String) extends Inline {
  private val (isEnd, tagName) = data match {
    case HtmlTag.Pattern(s1, s2) =>
      (!s1.isEmpty, Some(s2.toLowerCase))
    case _ =>
      (false, None)
  }

  def canClose(open: HtmlTag) = {
    isEnd && tagName == open.tagName
  }

  def close = tagName collect {
    case name if !HtmlTag.TagsNotToClose(name) && !data.endsWith(s"</$name>") =>
      HtmlTag(s"</$name>")
  }
}
object HtmlTag {
  private val Pattern = """(?ms)\A<(/?)(.*?)[\s>].*\z""".r
  private val TagsNotToClose = Set("br", "img")
}

/** The summary of a comment, usually its first sentence. There must be exactly one summary per body. */
final case class Summary(text: Inline) extends Inline

/** A Scaladoc comment and all its tags.
 *
 * '''Note:''' the only instantiation site of this class is in [[model.CommentFactory]].
 *
 * @author Manohar Jonnalagedda
 * @author Gilles Dubochet */
abstract class Comment {

  /** The main body of the comment that describes what the entity does and is.  */
  def body: Body

  private def closeHtmlTags(inline: Inline): Inline = {
    val stack = mutable.ListBuffer.empty[HtmlTag]
    def scan(i: Inline) {
      i match {
        case Chain(list) =>
          list foreach scan
        case tag: HtmlTag => {
          if (stack.nonEmpty && tag.canClose(stack.last)) {
            stack.remove(stack.length - 1)
          } else {
            tag.close match {
              case Some(t) =>
                stack += t
              case None =>
                ;
            }
          }
        }
        case _ =>
          ;
      }
    }
    scan(inline)
    Chain(List(inline) ++ stack.reverse)
  }

  /** A shorter version of the body. Either from `@shortDescription` or the
   *  first sentence of the body. */
  def short: Inline = {
    shortDescription orElse body.summary match {
      case Some(s) =>
        closeHtmlTags(s)
      case _ =>
        Text("")
    }
  }

  /** A list of authors. The empty list is used when no author is defined. */
  def authors: List[Body]

  /** A list of other resources to see, including links to other entities or
   * to external documentation. The empty list is used when no other resource
   * is mentioned. */
  def see: List[Body]

  /** A description of the result of the entity. Typically, this provides additional
   * information on the domain of the result, contractual post-conditions, etc. */
  def result: Option[Body]

  /** A map of exceptions that the entity can throw when accessed, and a
   * description of what they mean. */
  def throws: Map[String, Body]

  /** A map of value parameters, and a description of what they are. Typically,
   * this provides additional information on the domain of the parameters,
   * contractual pre-conditions, etc. */
  def valueParams: Map[String, Body]

  /** A map of type parameters, and a description of what they are. Typically,
   * this provides additional information on the domain of the parameters. */
  def typeParams: Map[String, Body]

  /** The version number of the entity. There is no formatting or further
   * meaning attached to this value. */
  def version: Option[Body]

  /** A version number of a containing entity where this member-entity was introduced. */
  def since: Option[Body]

  /** An annotation as to expected changes on this entity. */
  def todo: List[Body]

  /** Whether the entity is deprecated. Using the `@deprecated` Scala attribute
   * is preferable to using this Scaladoc tag. */
  def deprecated: Option[Body]

  /** An additional note concerning the contract of the entity. */
  def note: List[Body]

  /** A usage example related to the entity. */
  def example: List[Body]

  /** A description for the primary constructor */
  def constructor: Option[Body]

  /** A set of diagram directives for the inheritance diagram */
  def inheritDiagram: List[String]

  /** A set of diagram directives for the content diagram */
  def contentDiagram: List[String]

  /** The group this member is part of */
  def group: Option[String]

  /** Member group descriptions */
  def groupDesc: Map[String, Body]

  /** Member group names (overriding the short tag) */
  def groupNames: Map[String, String]

  /** Member group priorities */
  def groupPrio: Map[String, Int]

  /** A list of implicit conversions to hide */
  def hideImplicitConversions: List[String]

  /** A short description used in the entity-view and search results */
  def shortDescription: Option[Text]

  override def toString =
    body.toString + "\n" +
      (authors map ("@author " + _.toString)).mkString("\n") +
      (result map ("@return " + _.toString)).mkString("\n") +
      (version map ("@version " + _.toString)).mkString
}
