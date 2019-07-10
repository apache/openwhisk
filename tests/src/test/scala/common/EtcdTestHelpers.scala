package common

import io.etcd.jetcd.Response
import org.apache.openwhisk.core.etcd.EtcdClient

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Try}

trait EtcdTestHelpers {

  val etcd: EtcdClient

  type Entries = ListBuffer[String]

  class EntryCleaner(entriesToDeleteAfterTest: Entries) {
    def withCleaner[T <: Response](keys: List[String], sanitizer: String => Unit)(
      cmd: (EtcdClient, List[String]) => Unit): Unit = {
      keys.foreach(sanitizer)

      entriesToDeleteAfterTest ++= keys
      cmd(etcd, keys)
    }
  }

  def withEntryCleaner[T](sanitizer: String => Unit)(test: EntryCleaner => T): T = {
    val entriesToDeleteAfterTest = new Entries()

    try {
      test(new EntryCleaner(entriesToDeleteAfterTest))
    } catch {
      case t: Throwable =>
        // log the exception that occurred in the test and rethrow it
        println(s"Exception occurred during test execution: $t")
        t.printStackTrace()
        throw t
    } finally {
      val deletedAll = entriesToDeleteAfterTest.reverse.map { key =>
        key -> Try {
          sanitizer(key)
        }
      } forall {
        case (k, Failure(t)) =>
          println(s"ERROR: deleting entry failed for $k: $t")
          false
        case _ =>
          true
      }
      assert(deletedAll, "some entries were not deleted")
    }
  }

}

case class EtcdResult()
