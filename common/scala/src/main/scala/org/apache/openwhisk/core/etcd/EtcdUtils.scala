package org.apache.openwhisk.core.etcd

import java.nio.charset.StandardCharsets

import com.google.protobuf.ByteString
import io.etcd.jetcd.ByteSequence
import org.apache.openwhisk.core.entity.SizeUnits.MB
import org.apache.openwhisk.core.entity._
import spray.json.DefaultJsonProtocol

import scala.language.implicitConversions

case class Lease(id: Long, ttl: Long)

object Lease extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat2(Lease.apply)
}

case class EtcdLeader(key: String, value: String, lease: Lease)

object EtcdLeader extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat3(EtcdLeader.apply)
}

case class EtcdFollower(key: String, value: String)

object EtcdFollower extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat2(EtcdFollower.apply)
}

case class EtcdConfig(hosts: String)

case class EtcdException(msg: String) extends Exception(msg)

object EtcdType {

  implicit def stringToByteSequence(str: String): ByteSequence = ByteSequence.from(str, StandardCharsets.UTF_8)

  implicit def ByteSequenceToString(byteSequence: ByteSequence): String = byteSequence.toString(StandardCharsets.UTF_8)

  implicit def stringToByteString(str: String): ByteString = ByteString.copyFromUtf8(str)

  implicit def ByteStringToString(byteString: ByteString): String = byteString.toString(StandardCharsets.UTF_8)

  implicit def ByteSequenceToInt(byteSequence: ByteSequence): Int = byteSequence.toString(StandardCharsets.UTF_8).toInt

  implicit def IntToByteSequence(int: Int): ByteSequence = ByteSequence.from(int.toString, StandardCharsets.UTF_8)

  implicit def ByteSequenceToLong(byteSequence: ByteSequence): Long =
    byteSequence.toString(StandardCharsets.UTF_8).toInt

  implicit def LongToByteSequence(long: Long): ByteSequence = ByteSequence.from(long.toString, StandardCharsets.UTF_8)

  implicit def ByteSequenceToBoolean(byteSequence: ByteSequence): Boolean =
    byteSequence.toString(StandardCharsets.UTF_8).toBoolean

  implicit def BooleanToByteSequence(bool: Boolean): ByteSequence =
    ByteSequence.from(bool.toString, StandardCharsets.UTF_8)

  implicit def ByteSequenceToByteSize(byteSequence: ByteSequence): ByteSize =
    ByteSize(byteSequence.toString(StandardCharsets.UTF_8).toLong, MB)

  implicit def ByteSizeToByteSequence(byteSize: ByteSize): ByteSequence =
    ByteSequence.from(byteSize.toMB.toString, StandardCharsets.UTF_8)

}
