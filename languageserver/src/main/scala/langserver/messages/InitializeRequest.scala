package langserver.messages

import play.api.libs.json._

object TextDocumentSyncKind {
	/**
	 * Documents should not be synced at all.
	 */
	final val None = 0

	/**
	 * Documents are synced by always sending the full content
	 * of the document.
	 */
	final val Full = 1
	
	/**
	 * Documents are synced by sending the full content on open.
	 * After that only incremental updates to the document are
	 * send.
	 */
	final val Incremental = 2
}

