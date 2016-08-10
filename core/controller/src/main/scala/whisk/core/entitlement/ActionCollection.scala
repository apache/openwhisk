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
import whisk.core.entity.types.EntityStore
import scala.concurrent.Promise
import whisk.core.entity.WhiskEntity
import whisk.core.entity.EntityName
import whisk.core.entity.Namespace

protected[core] class ActionCollection(entityStore: EntityStore) extends Collection(Collection.ACTIONS) {

    /**
     * Computes implicit rights on an action.
     * Defers to super class if the action is a simple action.
     * Must fetch the resource if the action is a sequence and check whether the operation is allowed for
     * each individual action.
     */
    protected[core] override def implicitRights(namespaces: Set[String], right: Privilege, resource: Resource)(
        implicit ec: ExecutionContext, transid: TransactionId): Future[Boolean] = {
        resource.entity map {
            action =>
                // need to check whether the action is a simple action or a sequence
                // retrieve info on action
                val docid = DocId(WhiskEntity.qualifiedName(resource.namespace.root, EntityName(action)))
                (WhiskAction.get(entityStore, docid.asDocInfo) flatMap {
                    wskaction =>
                        wskaction.exec match {
                            case SequenceExec(_, components) =>
                                val rights = components map {
                                    actionName => checkComponentActionRights(namespaces, right, actionName)
                                }
                                // collapse all futures in a sequence
                                val result = Future.sequence(rights)
                                // collapse all booleans in one
                                result map { seq => seq.forall(_ == true) }
                                case _ => // this is not a sequence, defer to super
                                super.implicitRights(namespaces, right, resource)
                        }
                })
        } getOrElse {
            // this means there is no entity, it shouldn't get here
            // in any case, defer to super class
            super.implicitRights(namespaces, right, resource)
        }
    }

    private def checkComponentActionRights(namespaces: Set[String], right: Privilege, action: String)(
        implicit ec: ExecutionContext, transid: TransactionId): Future[Boolean] = {
        // use class Namespace to figure out package
        val fullyQualifiedName = Namespace(action)
        if (fullyQualifiedName.root == Namespace.DEFAULT) { // default package
            // check rights for the action as a resource
            val actionResource = Resource(fullyQualifiedName.root, Collection(Collection.ACTIONS), Some(fullyQualifiedName.last.toString))
            actionResource.collection.implicitRights(namespaces, right, actionResource)
        }
        else {
            // package exists, check read rights for package as a resource
            val packageResource = Resource(fullyQualifiedName.root, Collection(Collection.PACKAGES), Some(fullyQualifiedName.root.toString))
            // irrespective of right, one needs READ right on the package
            packageResource.collection.implicitRights(namespaces, Privilege.READ, packageResource)
            // check rights for the action itself
            val actionResource = Resource(fullyQualifiedName.root, Collection(Collection.ACTIONS), Some(fullyQualifiedName.last.toString))
            actionResource.collection.implicitRights(namespaces, right, actionResource)
        }
    }
}
