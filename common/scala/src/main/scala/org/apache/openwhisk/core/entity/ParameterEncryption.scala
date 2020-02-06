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
import spray.json.{JsNull, JsString}
import pureconfig.generic.auto._
import spray.json._
case class ParameterStorageConfig(current: String = "", aes128: String = "", aes256: String = "")

object ParameterEncryption {
  private val storageConfigLoader = loadConfig[ParameterStorageConfig](ConfigKeys.parameterStorage)
  var storageConfig = storageConfigLoader.getOrElse(ParameterStorageConfig.apply())
  def lock(params: Parameters): Parameters = {
    val configuredEncryptors = new encrypters(storageConfig)
    new Parameters(
      params.getMap
        .map(({
          case (paramName, paramValue) if paramValue.encryption == JsNull =>
            paramName -> configuredEncryptors.getCurrentEncrypter().encrypt(paramValue)
          case (paramName, paramValue) => paramName -> paramValue
        })))
  }
  def unlock(params: Parameters): Parameters = {
    val configuredEncryptors = new encrypters(storageConfig)
    new Parameters(
      params.getMap
        .map(({
          case (paramName, paramValue)
              if paramValue.encryption != JsNull && !configuredEncryptors
                .getEncrypter(paramValue.encryption.convertTo[String])
                .isEmpty =>
            paramName -> configuredEncryptors
              .getEncrypter(paramValue.encryption.convertTo[String])
              .get
              .decrypt(paramValue)
          case (paramName, paramValue) => paramName -> paramValue
        })))
  }
}

private trait encrypter {
  def encrypt(p: ParameterValue): ParameterValue
  def decrypt(p: ParameterValue): ParameterValue
  val name: String
}

private class encrypters(val storageConfig: ParameterStorageConfig) {
  private val availableEncrypters = Map("" -> new NoopCrypt()) ++
    (if (!storageConfig.aes256.isEmpty) Some(Aes256.name -> new Aes256(getKeyBytes(storageConfig.aes256))) else None) ++
    (if (!storageConfig.aes128.isEmpty) Some(Aes128.name -> new Aes128(getKeyBytes(storageConfig.aes128))) else None)

  protected[entity] def getCurrentEncrypter(): encrypter = {
    availableEncrypters.get(ParameterEncryption.storageConfig.current).get
  }
  protected[entity] def getEncrypter(name: String) = {
    availableEncrypters.get(name)
  }

  def getKeyBytes(key: String): Array[Byte] = {
    if (key.length == 0) {
      Array[Byte]()
    } else {
      Base64.getDecoder().decode(key)
    }
  }
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
    ParameterValue(JsString(Base64.getEncoder.encodeToString(cipherMessage)), value.init, Some(JsString(name)))
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
  val name = ""
  def encrypt(p: ParameterValue): ParameterValue = {
    p
  }

  def decrypt(p: ParameterValue): ParameterValue = {
    p
  }
}
