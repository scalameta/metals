package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class WorksheetModifier extends StringModifier {
  override val name: String = "worksheet"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter,
  ): String = {

    val (howYouSeeEvaluations, howToHover) = info match {
      case "vscode" =>
        "as a decoration at the end of the line." ->
          "hover on the decoration to expand the decoration."
      case _ =>
        "as a comment as the end of the line." -> "hover on the comment to expand."
    }

    s"""|## Worksheets
        |
        |Worksheets are a great way to explore an api, try out an idea, or code
        |up an example and quickly see the evaluated expression or result. Behind
        |the scenes worksheets are powered by the great work done in
        |[mdoc](https://scalameta.org/mdoc/).
        |
        |### Getting started with Worksheets
        |
        |To get started with a worksheet you can either use the `metals.new-scala-file`
        |command and select *Worksheet* or create a file called `*.worksheet.sc`.
        |This format is important since this is what tells Metals that it's meant to be
        |treated as a worksheet and not just a Scala script. Where you create the
        |script also matters. If you'd like to use classes and values from your
        |project, you need to make sure the worksheet is created inside of your `src`
        |directory. You can still create a worksheet in other places, but you will
        |only have access to the standard library and your dependencies.
        |
        |### Evaluations
        |
        |After saving you'll see the result of the expression ${howYouSeeEvaluations}
        |You may not see the full result for example if it's too long, so you are also
        |able to ${howToHover}
        |
        |Keep in mind that you don't need to wrap your code in an `object`. In worksheets
        |everything can be evaluated at the top level.
        |
        |### Using dependencies in worksheets
        |
        |You are able to include an external dependency in your worksheet by including
        |it in one of the following two ways.
        |
        |```scala
        |// $$dep.`organisation`::artifact:version` style
        |import $$dep.`com.lihaoyi::scalatags:0.7.0`
        |
        |// $$ivy.`organisation::artifact:version` style
        |import $$ivy.`com.lihaoyi::scalatags:0.7.0`
        |```
        |
        |`::` is the same as `%%` in sbt, which will append the current Scala binary version
        |to the artifact name.
        |
        |You can also import `scalac` options in a special `$$scalac` import like below:
        |
        |```scala
        |import $$scalac.`-Ywarn-unused`
        |```
        |
        |### Troubleshooting
        |
        |Since worksheets are not standard Scala files, you may run into issues with some constructs.
        |For example, you may see an error like this:
        |
        |```
        |value classes may not be a member of another class - mdoc
        |```
        |
        |This means that one of the classes defined in the worksheet extends AnyVal, which is
        |not currently supported. You can work around this by moving the class to a separate file or removing
        |the AnyVal parent.
        |""".stripMargin
  }

}
