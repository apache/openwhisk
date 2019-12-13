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

private trait encrypter {
  def encrypt(p: ParameterValue): ParameterValue
  def decrypt(p: ParameterValue): ParameterValue
  val name: String
}

case class ParameterStorageConfig(key: String = "") {
  def getKeyBytes(): Array[Byte] = {
    if (key.length == 0) {
      Array[Byte]()
    } else {
      Base64.getDecoder().decode(key)
    }
  }

}

object ParameterEncryption {
  private val storageConfigLoader = loadConfig[ParameterStorageConfig](ConfigKeys.parameterStorage)
  var storageConfig = storageConfigLoader.getOrElse(ParameterStorageConfig.apply())
  private def enc = storageConfig.getKeyBytes().length match {
    case 16 => new Aes128(storageConfig.getKeyBytes())
    case 32 => new Aes256(storageConfig.getKeyBytes())
    case 0  => new NoopCrypt
    case _ =>
      throw new IllegalArgumentException(
        s"Only 0, 16 and 32 characters support for key size but instead got ${storageConfig.getKeyBytes().length}")
  }
  def lock(params: Parameters): Parameters = {
    new Parameters(
      params.getMap
        .map(({
          case (paramName, paramValue) if paramValue.encryption == JsNull =>
            paramName -> enc.encrypt(paramValue)
          case (paramName, paramValue) => paramName -> paramValue
        })))
  }
  def unlock(params: Parameters): Parameters = {
    new Parameters(
      params.getMap
        .map(({
          case (paramName, paramValue)
              if paramValue.encryption != JsNull && paramValue.encryption.convertTo[String] == enc.name =>
            paramName -> enc.decrypt(paramValue)
          case (paramName, paramValue) => paramName -> paramValue
        })))
  }
}

private trait AesEncryption extends encrypter {
  val key: Array[Byte]
  val ivLen: Int
  val name: String
  private val tLen = key.length * 8
  private val secretKey = new SecretKeySpec(key, "AES")

  private val secureRandom = new SecureRandom()

  def encrypt(value: ParameterValue): ParameterValue = {

    val iv = new Array[Byte](ivLen)
    secureRandom.nextBytes(iv)
    val gcmSpec = new GCMParameterSpec(tLen, iv)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
    val clearText = value.value.convertTo[String].getBytes(StandardCharsets.UTF_8)
    val cipherText = cipher.doFinal(clearText)

    val byteBuffer = ByteBuffer.allocate(4 + iv.length + cipherText.length)
    byteBuffer.putInt(iv.length)
    byteBuffer.put(iv)
    byteBuffer.put(cipherText)
    val cipherMessage = byteBuffer.array
    ParameterValue(JsString(Base64.getEncoder.encodeToString(cipherMessage)), value.init, JsString(name))
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
    ParameterValue(JsString(plainText), value.init)
  }

}

private case class Aes128(val key: Array[Byte], val ivLen: Int = 12, val name: String = "aes128")
    extends AesEncryption
    with encrypter

private case class Aes256(val key: Array[Byte], val ivLen: Int = 128, val name: String = "aes256")
    extends AesEncryption
    with encrypter

private class NoopCrypt extends encrypter {
  val name = "noop"
  def encrypt(p: ParameterValue): ParameterValue = {
    p
  }

  def decrypt(p: ParameterValue): ParameterValue = {
    p
  }
}
