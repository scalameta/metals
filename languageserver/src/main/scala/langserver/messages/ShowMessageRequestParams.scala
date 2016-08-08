package langserver.messages

case class ShowMessageRequestParams(
	/**
	 * The message type. @see MessageType
	 */
	tpe: Long,

	/**
	 * The actual message
	 */
	message: String,

	/**
	 * The message action items to present.
	 */
	actions: Seq[MessageActionItem]
)

case class MessageActionItem(
	/**
	 * A short title like 'Retry', 'Open Log' etc.
	 */
	title: String
)