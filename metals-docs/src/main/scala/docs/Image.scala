package docs

object Image {
  val all: Map[String, Map[String, String]] = Map(
    "vscode" -> Map(
      "importBuild" -> "https://i.imgur.com/0VqZWay.png",
      "importChanges" -> "https://i.imgur.com/72kdZkL.png",
      "importCommand" -> "https://i.imgur.com/QHLKt8u.png",
      "runDoctor" -> "https://i.imgur.com/K02g0UM.png"
    ),
    "atom" -> Map(
      "importBuild" -> "https://i.imgur.com/WxfhMFz.png",
      "importChanges" -> "https://i.imgur.com/xDb9oJU.png",
      "importCommand" -> "https://i.imgur.com/EGVO5Yb.png",
      "runDoctor" -> "https://i.imgur.com/8ODqcUj.png"
    ),
    "vim" -> Map(
      "importBuild" -> "https://i.imgur.com/1EyQPTC.png",
      "importChanges" -> "https://i.imgur.com/iocTVb6.png"
    ),
    "emacs" -> Map(
      "importBuild" -> "https://i.imgur.com/UdwMQFk.png",
      "importChanges" -> "https://i.imgur.com/UFK0p8i.png"
    )
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
