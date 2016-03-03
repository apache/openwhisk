package whisk.core.database

import whisk.core.entity.DocInfo
import whisk.core.entity.DocId
import whisk.core.entity.DocRevision

abstract class CouchDbLikeProvider[View: CouchDbLikeViewProvider] {
  type Client
  type Database
  type Response

  def mkClient(dbHost: String, dbPort: Int, dbUsername: String, dbPassword: String) : Client

  def getDB(client: Client, dbName: String) : Database

  def saveInDB(doc: Document, db: Database) : Response

  def findInDB[D](docInfo: DocInfo, db: Database)(implicit manifest: Manifest[D]) : D

  def allDocsInDB[D](db: Database)(implicit manifest: Manifest[D]) : Seq[D]

  def updateInDB(doc: Document, db: Database) : Response

  def removeFromDB(docInfo: DocInfo, db: Database) : Response

  def obtainViewFromDB(table: String, db: Database, includeDocs: Boolean, descending: Boolean, reduce: Boolean, inclusiveEnd: Boolean) : View

  def mkDocInfo(response: Response) : DocInfo

  def describeResponse(response: Response) : String

  def validateResponse(response: Response) : Boolean

  def shutdownClient(client: Client) : Unit

  final def viewProvider: CouchDbLikeViewProvider[View] = implicitly[CouchDbLikeViewProvider[View]]
}

trait CouchDbLikeViewProvider[V] {
  def limitView(view: V, limit: Int) : V

  def skipView(view: V, skip: Int) : V

  def withStartEndView(view: V, startKey: List[Any], endKey: List[Any]) : V

  def queryView[T](view: V)(implicit manifest: Manifest[T]) : Seq[T]
}
