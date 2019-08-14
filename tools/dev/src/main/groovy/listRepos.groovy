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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.commons.io.FilenameUtils

// script to read the Apache OpenWhisk repositories using the Github API
// it is recommended to use authentication by setting the 'GITHUB_ACCESS_TOKEN' env
// as otherwise requests will quickly become rate limited

assert args : "Expecting the OpenWhisk home directory to passed"
owHomePath = args[0]

def parser = new JsonSlurper()

// get as many repos per page as possible to cut down on the number of calls
def link = "https://api.github.com/orgs/apache/repos?per_page=100"
def creds = getCredentials()
def owRepos = []

if (!creds) {
    println "It is recommended to pass access token via env variable 'GITHUB_ACCESS_TOKEN' as otherwise requests will quickly become rate limited"
}

while ( link ) {
    def url = new URL(link)
    def conn = url.openConnection()
    if ( creds ) {
        conn.setRequestProperty("Authorization", "Basic " + creds.bytes.encodeBase64())
    }

    // add all projects matching naming conventions
    def result = parser.parse(conn.inputStream)
    owRepos += result
            .findAll { it.name.startsWith('openwhisk') }

    // find link to next page, if applicable
    link = null

    def links = conn.headerFields['Link']
    if ( links ) {
        def next = links[0].split(',').find{ it.contains('rel="next"') }
        link = next != null ? next.find('<(.*)>').replaceAll('<|>',''): null
    }
}


// ensure a consistent order
owRepos.sort {
    a,b -> a.name <=> b.name
}

def owReoNames = owRepos.collect {it.name}

def nameListFile = createNameListFile()
def jsonFile = createRepoJsonFile()
def list = owReoNames.join("\n")

nameListFile.text = list
jsonFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(owRepos))

println("Found ${owRepos.size()} repositories")
println(list)
println("Stored the list in ${nameListFile.getAbsolutePath()}")
println("Stored the json details in ${jsonFile.getAbsolutePath()}")

def getCredentials(){
    String creds = System.getenv("GITHUB_ACCESS_TOKEN")
    creds
}

def createNameListFile(){
    new File(getOutDir(), "repos.txt")
}

def createRepoJsonFile(){
    new File(getOutDir(), "repos.json")
}

def getOutDir(){
    def dir = new File(FilenameUtils.concat(owHomePath, "build/repos"))
    dir.mkdirs()
    dir
}
