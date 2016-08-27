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

package whisk.core.cli.test

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.BeforeAndAfterAll

import common.RunWskAdminCmd
import common.TestHelpers
import common.TestUtils
import common.TestUtils.FORBIDDEN
import common.TestUtils.NOT_FOUND
import common.TestUtils.TIMEOUT
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol._
import spray.json.pimpAny
import whisk.core.entity.Subject
import whisk.core.entity.WhiskPackage

@RunWith(classOf[JUnitRunner])
class WskCoreBasicTests
    extends TestHelpers
    with WskTestHelpers
    with BeforeAndAfterAll {

    val originWskProps = WskProps()
    val wsk = new Wsk(usePythonCLI = false)
    val samplePackage = "samplePackage"
    val sampleAction = s"$samplePackage/sampleAction"
    val sampleFeed = s"$samplePackage/sampleFeed"
    val wskadmin = new RunWskAdminCmd {}

    val otherNamespace = Subject().toString
    val create = wskadmin.cli(Seq("user", "create", otherNamespace))
    val otherAuthkey = create.stdout.trim
    implicit val otherWskProps = WskProps(namespace = otherNamespace, authKey = otherAuthkey)

    override def afterAll() = {
        withClue(s"failed to delete temporary namespace $otherNamespace") {
            wskadmin.cli(Seq("user", "delete", otherNamespace)).stdout should include("Subject deleted")
        }
    }

    behavior of "Wsk CLI"

    it should "reject deleting action in shared package not owned by authkey" in withAssetCleaner(otherWskProps) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.pkg, samplePackage) {
                (pkg, _) =>
                    pkg.create(samplePackage, shared = Some(true))
            }
            assetHelper.withCleaner(wsk.action, sampleAction) {
                val file = Some(TestUtils.getTestActionFilename("empty.js"))
                (action, _) => action.create(sampleAction, file, shared = Some(true))
            }
            val fullyQualifiedActionName = s"/$otherNamespace/$sampleAction"
            wsk.action.get(fullyQualifiedActionName)(originWskProps)
            wsk.action.delete(fullyQualifiedActionName, expectedExitCode = FORBIDDEN)(originWskProps)
    }

    it should "reject create action in shared package not owned by authkey" in withAssetCleaner(otherWskProps) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.pkg, samplePackage) {
                (pkg, name) => pkg.create(name, shared = Some(true))
            }
            val fullyQualifiedActionName = s"/$otherNamespace/notallowed"
            val file = Some(TestUtils.getTestActionFilename("empty.js"))
            assetHelper.withCleaner(wsk.action, fullyQualifiedActionName, confirmDelete = false) {
                 (action, name) => action.create(name, file, expectedExitCode = FORBIDDEN)(originWskProps)
            }
    }

    it should "reject update action in shared package not owned by authkey" in withAssetCleaner(otherWskProps) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.pkg, samplePackage) {
                (pkg, _) =>
                    pkg.create(samplePackage, shared = Some(true))
            }
            assetHelper.withCleaner(wsk.action, sampleAction) {
                val file = Some(TestUtils.getTestActionFilename("empty.js"))
                (action, _) => action.create(sampleAction, file, shared = Some(true))
            }

            val fullyQualifiedActionName = s"/$otherNamespace/notallowed"
            assetHelper.withCleaner(wsk.action, fullyQualifiedActionName, confirmDelete = false) {
                (action, _) => action.create(fullyQualifiedActionName, None, update = true,
                                             expectedExitCode = FORBIDDEN)(originWskProps)
            }
    }

    behavior of "Wsk Package CLI"

    it should "list shared packages" in withAssetCleaner(otherWskProps) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.pkg, samplePackage) {
                (pkg, _) =>
                    pkg.create(samplePackage, shared = Some(true))
            }
            val fullyQualifiedPackageName = s"/$otherNamespace/$samplePackage"
            val result = wsk.pkg.list(Some(s"/$otherNamespace"))(originWskProps).stdout
            result should include regex (fullyQualifiedPackageName + """\s+shared""")
    }

    it should "not list private packages" in withAssetCleaner(otherWskProps) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.pkg, samplePackage) {
                (pkg, _) =>
                    pkg.create(samplePackage)
            }
            val fullyQualifiedPackageName = s"/$otherNamespace/$samplePackage"
            val result = wsk.pkg.list(Some(s"/$otherNamespace"))(originWskProps).stdout
            result should not include regex (fullyQualifiedPackageName)
    }

    it should "list shared package actions" in withAssetCleaner(otherWskProps) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.pkg, samplePackage) {
                (pkg, _) =>
                    pkg.create(samplePackage, shared = Some(true))
            }
            assetHelper.withCleaner(wsk.action, sampleAction) {
                val file = Some(TestUtils.getTestActionFilename("empty.js"))
                (action, _) => action.create(sampleAction, file, kind = Some("nodejs"), shared = Some(true))
            }
            val fullyQualifiedPackageName = s"/$otherNamespace/$samplePackage"
            val fullyQualifiedActionName = s"/$otherNamespace/$sampleAction"
            val result = wsk.action.list(Some(fullyQualifiedPackageName))(originWskProps).stdout
            result should include regex (fullyQualifiedActionName + """\s+shared""")
    }

    it should "create a package binding" in withAssetCleaner(otherWskProps) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.pkg, samplePackage) {
                (pkg, _) =>
                    pkg.create(samplePackage, shared = Some(true))
            }
            val name = "bindPackage"
            val annotations = Map("a" -> "A".toJson, WhiskPackage.bindingFieldName -> "xxx".toJson)
            val provider = s"/$otherNamespace/$samplePackage"
            withAssetCleaner(originWskProps) {
                (wp, assetHelper) =>
                    assetHelper.withCleaner(wsk.pkg, name) {
                        (pkg, _) =>
                            pkg.bind(provider, name, annotations = annotations)(originWskProps)
                    }
                    val stdout = wsk.pkg.get(name)(originWskProps).stdout
                    val annotationString = wsk.parseJsonString(stdout).fields("annotations").toString
                    annotationString should include regex (""""key":"a"""")
                    annotationString should include regex (""""value":"A"""")
                    annotationString should include regex (s""""key":"${WhiskPackage.bindingFieldName}"""")
                    annotationString should not include regex(""""key":"xxx"""")
                    annotationString should include regex(s""""name":"${samplePackage}"""")
            }
    }

    behavior of "Wsk Action CLI"

    it should "get an action" in withAssetCleaner(otherWskProps) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.pkg, samplePackage) {
                (pkg, _) =>
                    pkg.create(samplePackage, parameters = Map("a" -> "A".toJson), shared = Some(true))
            }
            assetHelper.withCleaner(wsk.action, sampleAction) {
                val file = Some(TestUtils.getTestActionFilename("empty.js"))
                (action, _) => action.create(sampleAction, file, shared = Some(true))
            }
            val fullyQualifiedActionName = s"/$otherNamespace/$sampleAction"
            val stdout = wsk.action.get(fullyQualifiedActionName)(originWskProps).stdout
            stdout should include("name")
            stdout should include("parameters")
            stdout should include("limits")
            stdout should include regex (""""key": "a"""")
            stdout should include regex (""""value": "A"""")
    }

    behavior of "Wsk Trigger CLI"

    it should "not create a trigger with timeout error when feed fails to initialize" in withAssetCleaner(otherWskProps) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.pkg, samplePackage) {
                (pkg, _) =>
                    pkg.create(samplePackage, shared = Some(true))
            }
            assetHelper.withCleaner(wsk.action, sampleFeed) {
                val file = Some(TestUtils.getTestActionFilename("empty.js"))
                (action, _) => action.create(sampleFeed, file, kind = Some("nodejs"), shared = Some(true))
            }
            val fullyQualifiedFeedName = s"/$otherNamespace/$sampleFeed"
            assetHelper.withCleaner(wsk.trigger, "badfeed", confirmDelete = false) {
                (trigger, name) =>
                    trigger.create(name, feed = Some(fullyQualifiedFeedName), expectedExitCode = TIMEOUT)
            }
            wsk.trigger.get("badfeed", expectedExitCode = NOT_FOUND)(originWskProps)
    }

}
