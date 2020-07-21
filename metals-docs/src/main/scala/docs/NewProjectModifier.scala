package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class NewProjectModifier extends StringModifier {

  override val name: String = "new-project"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    val isVscode = info == "vscode"
    val newScalaProject =
      if (isVscode) "Metals: New Scala project"
      else "new-scala-project"
    val chooseWindow =
      if (isVscode)
        "\n5. Choose whether to open a new window for the created project or use the existing one."
      else ""

    val fromButtons =
      if (isVscode)
        "\nThe same command will be invoked when clicking the \"New Scala Project\" button in the Metals view."
      else ""
    s"""|## Create new project from template
        |
        |It is possible using Metals to easily setup a new project using the exiting [giter8](https://github.com/foundweekends/giter8/wiki/giter8-templates) templates. 
        |This is an equivalent to the `sbt new` command, which uses the same mechanism.
        |There is a great number of templates already available and it should be easy to find something for yourself.
        |To start the setup you can use the $newScalaProject command, which works as following:
        |1. Choose the template and then:
        |    1. Use the proposed templates.
        |    2. Choose "Discover more" and then choose from the list downloaded from the Giter8 wiki page.
        |    3. Input a custom Github repository following the `organization/repo` schema.
        |3. Navigate to the parent directory that you want to create your new project in.
        |4. Choose the name or accept the default one.
        |$chooseWindow
        |$fromButtons
        |
        |If you feel like a template should be included in the default displayed ones do not hesitate to create a 
        |[PR](https://github.com/scalameta/metals/blob/cda5b8c2029e5f201fb8d0636e0365d796407bd9/metals/src/main/scala/scala/meta/internal/builds/NewProjectProvider.scala#L308)
        |or file an issue.
        |""".stripMargin
  }

}
