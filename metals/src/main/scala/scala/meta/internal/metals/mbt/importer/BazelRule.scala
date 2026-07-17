package scala.meta.internal.metals.mbt.importer

// A `bazel query` rule reduced to the fields the importer reads. `attributes`
// keeps each `select()` unflattened as a list of branches.
case class BazelRule(
    name: String,
    ruleClass: Option[String],
    ruleInputs: List[String],
    ruleOutputs: List[String],
    attributes: Map[String, List[BazelSelectBranch]],
) {

  def branches(attribute: String): List[BazelSelectBranch] =
    attributes.getOrElse(attribute, Nil)

  def flattenedStrings(attribute: String): List[String] =
    branches(attribute).flatMap(_.values).filter(_.nonEmpty)
}

// `label` is the `config_setting` guarding a `select()` branch, or `None` for a
// plain value; `values` holds a scalar as a single-element list.
case class BazelSelectBranch(label: Option[String], values: List[String])
