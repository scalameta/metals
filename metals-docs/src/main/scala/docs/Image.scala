package docs

object Image {
  val all: Map[String, Map[String, String]] = Map(
    "vscode" -> Map(
      "importBuild" -> "https://raw.githubusercontent.com/scalameta/gh-pages-images/main/metals/Image/0VqZWay.png",
      "importChanges" -> "https://raw.githubusercontent.com/scalameta/gh-pages-images/main/metals/Image/72kdZkL.png",
      "importCommand" -> "https://raw.githubusercontent.com/scalameta/gh-pages-images/main/metals/Image/QHLKt8u.png",
      "runDoctor" -> "https://raw.githubusercontent.com/scalameta/gh-pages-images/main/metals/Image/K02g0UM.png",
    ),
    "vim" -> Map(
      "importBuild" -> "https://raw.githubusercontent.com/scalameta/gh-pages-images/main/metals/Image/1EyQPTC.png",
      "importChanges" -> "https://raw.githubusercontent.com/scalameta/gh-pages-images/main/metals/Image/iocTVb6.png",
    ),
    "emacs" -> Map(
      "importBuild" -> "https://raw.githubusercontent.com/scalameta/gh-pages-images/main/metals/Image/UdwMQFk.png",
      "importChanges" -> "https://raw.githubusercontent.com/scalameta/gh-pages-images/main/metals/Image/UFK0p8i.png",
    ),
  )
  def importBuild(editor: String): String =
    all(editor)("importBuild")
  def importChanges(editor: String): String =
    all(editor)("importChanges")
  def importCommand(editor: String): String =
    all(editor)("importCommand")
  def runDoctor(editor: String): String =
    all(editor)("runDoctor")
}
