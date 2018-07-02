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

import groovy.json.JsonSlurper
import groovy.text.SimpleTemplateEngine
import org.apache.commons.io.FilenameUtils

assert args : "Expecting the OpenWhisk home directory to passed"
owHomePath = args[0]

def repos = loadRepoJson()

def template = getClass().getResource("./modules.md").text
def engine = new SimpleTemplateEngine()

def categoryManager = new CategoryManager()

repos.each{ repo ->categoryManager.addToCategory(repo)}
categoryManager.sort()

def binding = ["categories":categoryManager.categories]
def result = engine.createTemplate(template).make(binding)

def file = getModuleOutputFile()
file.setText(result.toString(), 'UTF-8')
println "Generated modules details at ${file.getAbsolutePath()}"

def loadRepoJson(){
    File file = new File(FilenameUtils.concat(owHomePath, "build/repos/repos.json"))
    assert file.exists() : "Did not found ${file.absolutePath}. Run './gradlew :tools:dev:listRepos' prior to this script"
    def parser = new JsonSlurper()
    parser.parseText(file.text)
}

def getModuleOutputFile(){
    new File(FilenameUtils.concat(owHomePath, "docs/dev/modules.md"))
}
