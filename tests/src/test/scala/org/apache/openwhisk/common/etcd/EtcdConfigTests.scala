package org.apache.openwhisk.common.etcd

import common.WskActorSystem
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdConfig}
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import scala.concurrent.ExecutionContextExecutor
@RunWith(classOf[JUnitRunner])
class EtcdConfigTests extends FlatSpec with Matchers with WskActorSystem {
  behavior of "EtcdConfig"

  implicit val ece: ExecutionContextExecutor = actorSystem.dispatcher

  it should "create client when no auth is supplied through config" in {
    val config = EtcdConfig("localhost:2379", None, None)

    val client = EtcdClient(config)
    client.close()
  }

  it should "create client when auth is supplied through config" in {
    val config = EtcdConfig("localhost:2379", Some("username"), Some("password"))

    val client = EtcdClient(config)
    client.close()
  }

  it should "fail to create client when one of username or password is supplied in config" in {
    val config = EtcdConfig("localhost:2379", None, Some("password"))

    assertThrows[IllegalArgumentException](EtcdClient(config))
  }
}
