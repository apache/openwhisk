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

protected[core] object ParameterEncryption {

  val NO_ENCRYPTION = "noop"

  private val noop = new NoopCrypt()

  private var defaultEncryptor: encrypter = noop
  private var encryptors: Map[String, encrypter] = Map.empty

  {
    val configLoader = loadConfig[ParameterStorageConfig](ConfigKeys.parameterStorage)
    val config = configLoader.getOrElse(ParameterStorageConfig(noop.name))
    initialize(config)
  }

  def initialize(config: ParameterStorageConfig): Unit = {
    val availableEncrypters = Map(noop.name -> noop) ++
      config.aes128.map(k => Aes128.name -> new Aes128(getKeyBytes(k))) ++
      config.aes256.map(k => Aes256.name -> new Aes256(getKeyBytes(k)))

    // should this succeed if the given current scheme does not exist?
    defaultEncryptor = availableEncrypters.get(config.current).getOrElse(noop)
    encryptors = availableEncrypters
  }

  private def getKeyBytes(key: String): Array[Byte] = {
    if (key.length == 0) {
      Array.empty
    } else {
      Base64.getDecoder.decode(key)
    }
  }

  /**
   * Encrypts any parameters that are not yet encoded.
   *
   * @param params the parameters to encode
   * @return parameters with all values encrypted
   */
  def lock(params: Parameters): Parameters = {
    new Parameters(params.getMap.map {
      case (paramName, paramValue) if paramValue.encryption.isEmpty =>
        paramName -> defaultEncryptor.encrypt(paramValue)
      case p => p
    })
  }

  /**
   * Decodes parameters. If the encryption scheme for a parameter is not recognized, it is not modified.
   *
   * @param params the parameters to decode
   * @return parameters will all values decoded (where scheme is known)
   */
  def unlock(params: Parameters): Parameters = {
    new Parameters(params.getMap.map {
      case (paramName, paramValue) if paramValue.encryption.nonEmpty =>
        paramName -> encryptors(paramValue.encryption.getOrElse(noop.name)).decrypt(paramValue)
      case p => p
    })
  }
}

private trait encrypter {
  def encrypt(p: ParameterValue): ParameterValue
  def decrypt(p: ParameterValue): ParameterValue
  val name: String
}

private trait AesEncryption extends encrypter {
  val key: Array[Byte]
  val ivLen: Int
  val name: String
  private val tLen = 128
  private val secretKey = new SecretKeySpec(key, "AES")

  private val secureRandom = new SecureRandom()

  def encrypt(value: ParameterValue): ParameterValue = {
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

  def decrypt(value: ParameterValue): ParameterValue = {
    val cipherMessage = value.value.convertTo[String].getBytes(StandardCharsets.UTF_8)
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
    ParameterValue(plainText.parseJson, value.init)
  }

}

private object Aes128 {
  val name: String = "aes-128"
}
private case class Aes128(val key: Array[Byte], val ivLen: Int = 12, val name: String = Aes128.name)
    extends AesEncryption
    with encrypter

private object Aes256 {
  val name: String = "aes-256"
}
private case class Aes256(val key: Array[Byte], val ivLen: Int = 128, val name: String = Aes256.name)
    extends AesEncryption
    with encrypter

private class NoopCrypt extends encrypter {
  val name = ParameterEncryption.NO_ENCRYPTION
  def encrypt(p: ParameterValue) = p
  def decrypt(p: ParameterValue) = p
}
