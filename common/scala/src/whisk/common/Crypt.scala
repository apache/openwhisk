/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.common

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter

class Crypt(secret: String) extends Logging {

    def decrypt(encrypted: String): String = {
        if (encrypted == null) return null
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv))
        val clearbyte = cipher.doFinal(DatatypeConverter.parseHexBinary(encrypted))
        new String(clearbyte)
    }

    def encrypt(content: String): String = {
        if (content == null) return null
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv))
        val input = content.getBytes("utf-8")
        val ct = new Array[Byte](cipher.getOutputSize(input.length))
        val ctLength = cipher.update(input, 0, input.length, ct, 0)
        cipher.doFinal(ct, ctLength)
        DatatypeConverter.printHexBinary(ct).toLowerCase
    }

    private val iv = "0000000000000000".getBytes;
    private val key = new SecretKeySpec(secret.getBytes, "AES")
    private val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

}
