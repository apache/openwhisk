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

import java.util.function.Predicate

class CategoryManager {
    def categories = process([
        [name: "Main", travis: true, archived: false, suffixes: ['openwhisk', 'apigateway', 'catalog', 'cli', 'wskdeploy', 'composer', 'composer-python']],
        [name: "Clients", travis: true, archived: false, contains: ['-client-']],
        [name: "Runtimes", travis: true, archived: false, contains: ['-runtime-']],
        [name: "Deployments", travis: true, archived: false, contains: ['-deploy-']],
        [name: "Packages", travis: true, archived: false, contains: ['-package-', '-provider']],
        [name: "Samples and Examples", travis: false, archived: false, suffixes: ['workshop', 'slackinvite', 'sample-slackbot', 'sample-matos', 'tutorial', 'GitHubSlackBot']],
        [name: "Development Tools", travis: false, archived: false, suffixes: ['devtools', 'xcode', 'vscode', 'playground', 'debugger']],
        [name: "Utilities", travis: false, archived: false, suffixes: ['utilities', 'release']],
        [name: "Others", travis: false, archived: false],
        [name: "Archived", travis: false, archived: true]
    ])

    private def suffixMatcher(List<String> suffixes) {
        return {name -> suffixes.any {name.endsWith(it)}} as Predicate<String>
    }

    private def containsMatcher(List<String> marker) {
        return {name -> marker.any {name.contains(it)}} as Predicate<String>
    }

    private def createMatcher(Map m){
        if (m.containsKey('suffixes')) return suffixMatcher(m.suffixes)
        else if (m.containsKey('contains')) return containsMatcher(m['contains'])
        else return {true} as Predicate
    }

    private def process(List<Map> repos) {
        repos.collect {m -> new Category(m.name, m.travis, m.archived, createMatcher(m))}
    }

    def addToCategory(repo) {
        categories.find {c -> c.matches(repo.name) && c.archived == repo.archived}.addRepo(repo)
    }

    def sort(){
        categories.each {it.sort()}
    }
}

class Category {
    String name
    boolean travisEnabled
    boolean archived
    List repos = []
    Predicate<String> matcher

    Category(name, travisEnabled, archived, matcher) {
        this.name = name
        this.travisEnabled = travisEnabled
        this.archived = archived
        this.matcher = matcher
    }

    def matches(String repoName) {
        matcher.test(repoName)
    }

    def addRepo(repo){
        repos << repo
    }

    def sort() {
        repos.sort {a, b -> a.name <=> b.name}
    }
}
