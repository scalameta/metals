package scala.meta.internal.metals.testProvider.frameworks

import scala.annotation.tailrec

import scala.meta.Defn
import scala.meta.Pkg
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree

private[frameworks] object TreeUtils {

  /**
   * Extract class/trait template from the given Tree.
   * @param tree Tree which may contain Template
   * @param fullyQualifiedName fully qualified class name of class/trait
   * @return Template of a given class if present
   */
  def extractTemplateFrom(
      tree: Tree,
      fullyQualifiedName: String,
  ): Option[Template] = {

    /**
     * Search loop with short circuiting when first matching result is obtained.
     */
    def loop(
        t: Tree,
        currentPackage: Vector[String],
    ): Option[Template] = {
      t match {
        case cls: Defn.Class
            if isValid(cls.name.value, currentPackage, fullyQualifiedName) =>
          Some(cls.templ)
        case obj: Defn.Object
            if isValid(obj.name.value, currentPackage, fullyQualifiedName) =>
          Some(obj.templ)
        case trt: Defn.Trait
            if isValid(trt.name.value, currentPackage, fullyQualifiedName) =>
          Some(trt.templ)
        // short-circuit to not go deeper into unuseful defns
        case _: Defn => None
        case Pkg(ref, children) =>
          val pkg = extractPackageName(ref)
          val newPackage = currentPackage ++ pkg
          LazyList
            .from(children)
            .map(loop(_, newPackage))
            .find(_.isDefined)
            .flatten
        case _ =>
          LazyList
            .from(t.children)
            .map(loop(_, currentPackage))
            .find(_.isDefined)
            .flatten
      }
    }

    loop(tree, Vector.empty)
  }

  /**
   * Class definition is valid when package + class name is equal to one we are looking for
   */
  private def isValid(
      name: String,
      currentPackage: Vector[String],
      searched: String,
  ): Boolean = {
    val fullyQualifiedName = currentPackage.appended(name).mkString(".")
    fullyQualifiedName == searched
  }

  /**
   * Extract package name from given Term
   *
   * package a => Term.Name(a)
   * package a.b.c => Term.Select(Term.Select(a, b), c) (Term.Name are omitted)
   */
  @tailrec
  private def extractPackageName(
      term: Term,
      acc: List[String] = Nil,
  ): Vector[String] =
    term match {
      case Term.Name(value) => (value :: acc).toVector
      case Term.Select(qual, Term.Name(value)) =>
        extractPackageName(qual, value :: acc)
      case _ => Vector.empty
    }
}
