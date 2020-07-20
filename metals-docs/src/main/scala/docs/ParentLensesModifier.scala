package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class ParentLensesModifier extends StringModifier {
  override val name: String = "parent-lenses"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {

    val isVSCode = info == "vscode"
    val gotoToSuper =
      if (isVSCode) "Metals: Go to super method" else "goto-super-method"
    val superMethodHierarchy =
      if (isVSCode) "Metals: Reveal super method hierachy"
      else "super-method-hierarchy"
    val additionalInfo =
      if (info == "vscode") "\nYou can also bind those commands to a shortcut."
      else ""

    s"""|## Go to parent code lenses
        |
        |Metals has the ability to display code lenses that, when invoked, 
        |will go to the parent class that contains the definition of the method or symbol.
        |Unfortunately, it might cause some lag in larger code bases, 
        |which is why it is not enabled currently by default.
        |
        |To enable the feature you need to modify the setting `metals.superMethodLensesEnabled` to `true`.
        |
        |Even without using the code lenses it's still possible to navigate the method hierarchy 
        |using two commands:
        |
        | - `$gotoToSuper` - immediately goes to the parent of the method the cursor is pointing to
        |
        | - `$superMethodHierarchy` - displays the full method hierachy and enables to move to any parent, 
        |it is best used with the Metals Quick Pick extension.
        |$additionalInfo
        |""".stripMargin
  }
}
