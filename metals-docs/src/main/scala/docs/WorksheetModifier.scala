package docs

import scala.meta.inputs.Input

import mdoc.Reporter
import mdoc.StringModifier

class WorksheetModifier extends StringModifier {
  override val name: String = "worksheet"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {

    val (howYouSeeEvaluations, howToHover) = info match {
      case "vscode" =>
        "as a decoration at the end of the line." ->
          "hover on the decoration to expand the decoration."
      case "vim" =>
        """|differently depending on whether you are using Neovim or Vim. If 
           |using Vim, you will see them appear as comments at the end of
           |the line. If you're using Nvim you will see this as virtual text.
           |Keep in mind that if you're using coc-metals with Nvim you'll need
           |to make sure `codeLens.enable` is set to `true`.""".stripMargin ->
          """|hover on the comment if you're in Vim or expand the virtual text by
             |using the `coc-metals-expand-decoration` command. The default for this
             |is:
             |```vim
             |nmap <Leader>ws <Plug>(coc-metals-expand-decoration)
             |```
             |""".stripMargin
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
        |:: is the same as %% in sbt, which will append the current Scala binary version
        |to the artifact name.
        |""".stripMargin
  }

}
