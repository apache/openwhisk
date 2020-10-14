/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openwhisk.core.entity

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import org.apache.openwhisk.core.ConfigKeys
import pureconfig.loadConfig
import spray.json.DefaultJsonProtocol._
import spray.json._
import pureconfig.generic.auto._
import spray.json._

protected[core] case class ParameterStorageConfig(current: String = ParameterEncryption.NO_ENCRYPTION,
                                                  aes128: Option[String] = None,
                                                  aes256: Option[String] = None)

protected[core] class ParameterEncryption(val default: Option[Encrypter], encryptors: Map[String, Encrypter]) {

  /**
   * Gets the coder for the given scheme name.
   *
   * @param name the name of the encryption algorithm (defaults to current from last configuration)
   * @return the coder if there is one else no-op encryptor
   */
  def encryptor(name: String): Encrypter = {
    encryptors.get(name).getOrElse(ParameterEncryption.noop)
  }

}

protected[core] object ParameterEncryption {

  val NO_ENCRYPTION = "noop"
  val AES128_ENCRYPTION = "aes-128"
  val AES256_ENCRYPTION = "aes-256"

  val noop = new Encrypter {
    override val name = NO_ENCRYPTION
  }

  val singleton: ParameterEncryption = {
    val configLoader = loadConfig[ParameterStorageConfig](ConfigKeys.parameterStorage)
    val config = configLoader.getOrElse(ParameterStorageConfig(noop.name))
    ParameterEncryption(config)
  }

  def apply(config: ParameterStorageConfig): ParameterEncryption = {
    val availableEncoders = Map(noop.name -> noop) ++
      config.aes128.map(k => AES128_ENCRYPTION -> new Aes128(k)) ++
      config.aes256.map(k => AES256_ENCRYPTION -> new Aes256(k))

    val current = config.current.toLowerCase match {
      case "" | "off" | NO_ENCRYPTION => NO_ENCRYPTION
      case s                          => s
    }

    val defaultEncoder: Encrypter = availableEncoders.get(current).getOrElse(noop)
    new ParameterEncryption(Option(defaultEncoder).filter(_ != noop), availableEncoders)
  }
}

protected[core] trait Encrypter {
  val name: String
  def encrypt(p: ParameterValue): ParameterValue = p
  def decrypt(p: ParameterValue): ParameterValue = p
  def decrypt(v: JsString): JsValue = v
}

protected[core] object Encrypter {
  protected[entity] def getKeyBytes(key: String): Array[Byte] = {
    if (key.length == 0) {
      Array.empty
    } else {
      Base64.getDecoder.decode(key)
    }
  }
}

protected[core] trait AesEncryption extends Encrypter {
  val key: Array[Byte]
  val ivLen: Int
  val name: String
  private val tLen = 128
  private val secureRandom = new SecureRandom()
  private lazy val secretKey = new SecretKeySpec(key, "AES")

  override def encrypt(value: ParameterValue): ParameterValue = {
    val iv = new Array[Byte](ivLen)
    secureRandom.nextBytes(iv)
    val gcmSpec = new GCMParameterSpec(tLen, iv)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
    val clearText = value.value.compactPrint.getBytes(StandardCharsets.UTF_8)
    val cipherText = cipher.doFinal(clearText)

    val byteBuffer = ByteBuffer.allocate(4 + iv.length + cipherText.length)
    byteBuffer.putInt(iv.length)
    byteBuffer.put(iv)
    byteBuffer.put(cipherText)
    val cipherMessage = byteBuffer.array
    ParameterValue(JsString(Base64.getEncoder.encodeToString(cipherMessage)), value.init, Some(name))
  }

  override def decrypt(p: ParameterValue): ParameterValue = {
    p.value match {
      case s: JsString => p.copy(v = decrypt(s), encryption = None)
      case _           => p
    }
  }

  override def decrypt(value: JsString): JsValue = {
    val cipherMessage = value.convertTo[String].getBytes(StandardCharsets.UTF_8)
    val byteBuffer = ByteBuffer.wrap(Base64.getDecoder.decode(cipherMessage))
    val ivLength = byteBuffer.getInt
    if (ivLength != ivLen) {
      throw new IllegalArgumentException("invalid iv length")
    }
    val iv = new Array[Byte](ivLength)
    byteBuffer.get(iv)
    val cipherText = new Array[Byte](byteBuffer.remaining)
    byteBuffer.get(cipherText)

    val gcmSpec = new GCMParameterSpec(tLen, iv)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
    val plainTextBytes = cipher.doFinal(cipherText)
    val plainText = new String(plainTextBytes, StandardCharsets.UTF_8)
    plainText.parseJson
  }

}

protected[core] class Aes128(val k: String) extends AesEncryption with Encrypter {
  override val key = Encrypter.getKeyBytes(k)
  override val name = ParameterEncryption.AES128_ENCRYPTION
  override val ivLen = 12
}

protected[core] class Aes256(val k: String) extends AesEncryption with Encrypter {
  override val key = Encrypter.getKeyBytes(k)
  override val name = ParameterEncryption.AES256_ENCRYPTION
  override val ivLen = 128
}
