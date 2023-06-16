package scala.meta.internal.metals.newScalaFile

import scala.meta.internal.metals.clients.language.MetalsQuickPickItem

object NewFileTypes {
  sealed trait NewFileType {
    val id: String
    val syntax: Option[String]
    val label: String
    def toQuickPickItem: MetalsQuickPickItem = MetalsQuickPickItem(id, label)
  }

  case object ScalaFile extends NewFileType {
    override val id: String = "scala-file"
    override val syntax: Option[String] = None
    override val label: String = "Empty file"
  }

  case object Class extends NewFileType {
    override val id: String = "scala-class"
    override val syntax: Option[String] = Some("class")
    override val label: String = "Class"
  }

  case object CaseClass extends NewFileType {
    override val id: String = "scala-case-class"
    override val syntax: Option[String] = None
    override val label: String = "Case Class"
  }

  case object Enum extends NewFileType {
    override val id: String = "scala-enum"
    override val syntax: Option[String] = None
    override val label: String = "Enum"
  }

  case object Object extends NewFileType {
    override val id: String = "scala-object"
    override val syntax: Option[String] = Some("object")
    override val label: String = "Object"
  }

  case object Trait extends NewFileType {
    override val id: String = "scala-trait"
    override val syntax: Option[String] = Some("trait")
    override val label: String = "Trait"
  }

  case object PackageObject extends NewFileType {
    override val id: String = "scala-package-object"
    override val syntax: Option[String] = None
    override val label: String = "Package Object"
  }

  case object Worksheet extends NewFileType {
    override val id: String = "scala-worksheet"
    override val syntax: Option[String] = None
    override val label: String = "Worksheet"
  }

  case object ScalaScript extends NewFileType {
    override val id: String = "scala-script"
    override val syntax: Option[String] = None
    override val label: String = "Scala Script(Ammonite or Scala CLI)"
  }

  case object JavaClass extends NewFileType {
    override val id: String = "java-class"
    override val syntax: Option[String] = Some("class")
    override val label: String = "Class"
  }

  case object JavaInterface extends NewFileType {
    override val id: String = "java-interface"
    override val syntax: Option[String] = Some("interface")
    override val label: String = "Interface"
  }

  case object JavaEnum extends NewFileType {
    override val id: String = "java-enum"
    override val syntax: Option[String] = Some("enum")
    override val label: String = "Enum"
  }

  case object JavaRecord extends NewFileType {
    override val id: String = "java-record"
    override val syntax: Option[String] = None
    override val label: String = "Record"
  }

  def getFromString(id: String): Option[NewFileType] =
    id match {
      case Class.id => Some(Class)
      case CaseClass.id => Some(CaseClass)
      case Enum.id => Some(Enum)
      case Object.id => Some(Object)
      case Trait.id => Some(Trait)
      case ScalaFile.id => Some(ScalaFile)
      case PackageObject.id => Some(PackageObject)
      case Worksheet.id => Some(Worksheet)
      case ScalaScript.id => Some(ScalaScript)
      case JavaClass.id => Some(JavaClass)
      case JavaInterface.id => Some(JavaInterface)
      case JavaEnum.id => Some(JavaEnum)
      case JavaRecord.id => Some(JavaRecord)
      case invalid =>
        scribe.error(
          s"Invalid filetype given to new-(scala/java)-file command: $invalid"
        )
        None
    }
}
