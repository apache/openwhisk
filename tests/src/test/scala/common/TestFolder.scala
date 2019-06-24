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

package common

import java.io.File

import org.scalatest._

/**
 * Creates a temporary folder for the lifetime of a single test.
 * The folder's name will exist in a `File` field named `testFolder`.
 */
trait TestFolder extends TestSuite { self: Suite =>
  var testFolder: File = _

  //Default value ensures that temp files are created under build dir
  protected def parentFolder: File = new File("build/tmp/scalaTestFolder")

  private def deleteFile(file: File) {
    if (!file.exists) return
    if (file.isFile) {
      file.delete()
    } else {
      file.listFiles().foreach(deleteFile)
      file.delete()
    }
  }

  abstract override def withFixture(test: NoArgTest): Outcome = {
    testFolder = createTemporaryFolderIn(parentFolder)
    try {
      super.withFixture(test)
    } finally {
      deleteFile(testFolder)
    }
  }

  def newFile(): File = File.createTempFile("scalatest", null, rootFolder)

  def newFolder(): File = createTemporaryFolderIn(rootFolder)

  private def rootFolder = {
    require(testFolder != null, "the temporary folder has not yet been created")
    testFolder
  }

  private def createTemporaryFolderIn(parentFolder: File) = {
    if (!parentFolder.exists()) {
      parentFolder.mkdirs()
    }
    val createdFolder = File.createTempFile("scalatest", "", parentFolder)
    createdFolder.delete
    createdFolder.mkdir
    createdFolder
  }
}
