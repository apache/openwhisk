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

package whisk.core.entitlement

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import spray.http.HttpMethod
import spray.http.HttpMethods.DELETE
import spray.http.HttpMethods.GET
import spray.http.HttpMethods.POST
import spray.http.HttpMethods.PUT
import whisk.common.TransactionId
import whisk.core.entity.DocId
import whisk.core.entity.SequenceExec
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskPackage
import whisk.core.entity.types.EntityStore
import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}
import whisk.core.entity.WhiskEntity
import whisk.core.entity.EntityName
import whisk.core.entity.Namespace

// for toTryFuture method
import whisk.utils.ExecutionContextFactory.FutureExtensions

protected[core] class ActionCollection(entityStore: EntityStore) extends Collection(Collection.ACTIONS) {

    /**
     * Computes implicit rights on an action.
     * Defers to super class if the action is a simple action.
     * Must fetch the resource if the action is a sequence and check whether the operation is allowed for
     * each individual action.
     */
    protected[core] override def implicitRights(namespaces: Set[String], right: Privilege, resource: Resource)(
        implicit ec: ExecutionContext, transid: TransactionId): Future[Boolean] = {
        // super hack fix this
        //return Future successful {true}
        if (right != Privilege.ACTIVATE) {
            super.implicitRights(namespaces, right, resource)
        } else resource.entity map {
            action =>
                // resolve the action based on the package bindings and check the rights
                resolveActionAndCheckRights(namespaces, right, resource.namespace, EntityName(action))
        } getOrElse {
            // this means there is no entity, it shouldn't get here
            // in any case, defer to super class
            info(this, "Entity is none, deferring to implicit rights")
            super.implicitRights(namespaces, right, resource)
        }
    }

    /**
     * resolve the action based on the package binding (if any) and check its rights
     */
    private def resolveActionAndCheckRights(namespaces: Set[String], right: Privilege, namespace: Namespace, action: EntityName)(
            implicit ec: ExecutionContext, transid: TransactionId): Future[Boolean] = {
        if (namespace.isDefaultPackage) { // default package, resolved already
            // check rights for the action as a resource
            checkResolvedPackageActionRights(namespaces, right, namespace, action)
        }
        else {
            // package exists, check read rights for package as a resource
            val packageResource = Resource(namespace.root, Collection(Collection.PACKAGES), Some(namespace.last.name))
            // irrespective of right, one needs READ right on the package
            val packageRight = packageResource.collection.implicitRights(namespaces, Privilege.READ, packageResource)
            packageRight flatMap {
                pkgRight =>
                    if (pkgRight) {
                        // check rights for the action itself
                        // first check if the package has a binding
                        val docid = DocId(WhiskEntity.qualifiedName(namespace.root, namespace.last))
                        WhiskPackage.get(entityStore, docid.asDocInfo) flatMap {
                            case wp if wp.binding.isEmpty =>
                                // empty binding => finally got to the actual namespace to check
                                checkResolvedPackageActionRights(namespaces, right, namespace, action)
                            case wp =>
                                val binding = wp.binding.get
                                // use the binding instead, including the package name (use whole binding, not only namespace)
                                resolveActionAndCheckRights(namespaces, right, Namespace(binding.toString), action)
                        }
                    } else Future successful {false}
            }
        }
    }

    /**
     * checks the rights for an action given its fully resolved package binding
     */
    private def checkResolvedPackageActionRights(namespaces: Set[String], right: Privilege, namespace: Namespace, action: EntityName)(
            implicit ec: ExecutionContext, transid: TransactionId): Future[Boolean] = {
        // need to check whether the action is a simple action or a sequence
        // retrieve info on action
        info(this, s"Checking right $right for a resolved action $namespace $action")
        val docid = DocId(WhiskEntity.qualifiedName(namespace, action))
        val actionResource = Resource(namespace, Collection(Collection.ACTIONS), Some(action.name))
        WhiskAction.get(entityStore, docid.asDocInfo).toTryFuture flatMap {
            case Success(wskaction) =>
                wskaction.exec match {
                    case SequenceExec(_, components) =>
                        info(this, s"Checking right '$right' for a sequence $namespace $action' with components '${components}'")
                        val rights = components map {
                            actionName => checkComponentActionRights(namespaces, right, actionName)
                        }
                        // collapse all futures in a sequence
                        val result = Future.sequence(rights)
                        // collapse all booleans in one
                        result map { seq => seq.forall(_ == true) }
                    case _ => // this is not a sequence, defer to super
                            info(this, s"Check rights for a simple action $namespace $action")
                            super.implicitRights(namespaces, right, actionResource)
                }
            case Failure(_) =>
                info(this, s"Action not found, calling implicit rights")
                super.implicitRights(namespaces, right, actionResource)
            }
    }

    private def checkComponentActionRights(namespaces: Set[String], right: Privilege, action: String)(
            implicit ec: ExecutionContext, transid: TransactionId): Future[Boolean] = {
        // use class Namespace to figure out package
        // action is a fully qualified name; split it into the namespace and action name
        val lastIndex = action.lastIndexOf(Namespace.PATHSEP)
        val actionName = action.drop(lastIndex + 1)
        val namespaceParts = action.dropRight(action.size - lastIndex)
        // components are fully qualified, may contain _ for default namespace; drop it if it exists
        val namespace = Namespace(namespaceParts)
        info(this, s"fully qualified name $namespace and $actionName")
        resolveActionAndCheckRights(namespaces, right, namespace, EntityName(action))
    }
}
