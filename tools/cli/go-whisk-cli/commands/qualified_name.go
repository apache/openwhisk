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

package commands

import (
    "errors"
    "fmt"
    "strings"
    "../../go-whisk/whisk"
    "../wski18n"
)

type QualifiedName struct {
    namespace   string  // namespace. does not include leading '/'.  may be "" (i.e. default namespace)
    packageName string  // package.  may be "".  does not include leading/trailing '/'
    entity      string  // entity.  should not be ""
    EntityName  string  // pkg+entity
}

///////////////////////////
// QualifiedName Methods //
///////////////////////////

//  GetFullQualifiedName() returns a full qualified name in proper string format
//      from qualifiedName with proper syntax.
//  Example: /namespace/[package/]entity
func (qualifiedName *QualifiedName) GetFullQualifiedName() string {
    output := []string{}

    if len(qualifiedName.GetNamespace()) > 0 {
        output = append(output, "/", qualifiedName.GetNamespace(), "/")
    }
    if len(qualifiedName.GetPackageName()) > 0 {
        output = append(output, qualifiedName.GetPackageName(), "/")
    }
    output = append(output, qualifiedName.GetEntity())

    return strings.Join(output, "")
}

//  GetPackageName() returns the package name from qualifiedName without a
//      leading '/'
func (qualifiedName *QualifiedName) GetPackageName() string {
    return qualifiedName.packageName
}

//  GetEntityName() returns the entity name ([package/]entity) of qualifiedName
//      without a leading '/'
func (qualifiedName *QualifiedName) GetEntityName() string {
    return qualifiedName.EntityName
}

//  GetEntity() returns the name of entity in qualifiedName without a leading '/'
func (qualifiedName *QualifiedName) GetEntity() string {
    return qualifiedName.entity
}

//  GetNamespace() returns the name of the namespace in qualifiedName without
//      a leading '/'
func (qualifiedName *QualifiedName) GetNamespace() string {
    return qualifiedName.namespace
}

//  NewQualifiedName(name) initializes and constructs a (possibly fully qualified)
//      QualifiedName struct.
//
//      NOTE: If the given qualified name is None, then this is a default qualified
//          name and it is resolved from properties.
//      NOTE: If the namespace is missing from the qualified name, the namespace
//          is also resolved from the property file.
//
//  Examples:
//      foo => qualifiedName {namespace: "_", entityName: foo}
//      pkg/foo => qualifiedName {namespace: "_", entityName: pkg/foo}
//      /ns/foo => qualifiedName {namespace: ns, entityName: foo}
//      /ns/pkg/foo => qualifiedName {namespace: ns, entityName: pkg/foo}
func NewQualifiedName(name string) (*QualifiedName, error) {
    qualifiedName := new(QualifiedName)

    // If name has a preceding delimiter (/), or if it has two delimiters with a
    // leading non-empty string, then it contains a namespace. Otherwise the name
    // does not specify a namespace, so default the namespace to the namespace
    // value set in the properties file; if that is not set, use "_"
    name = addLeadSlash(name)
    parts := strings.Split(name, "/")
    if  strings.HasPrefix(name, "/")  {
        qualifiedName.namespace = parts[1]

        if len(parts) < 2 || len(parts) > 4 {
            return qualifiedName, qualifiedNameNotSpecifiedErr()
        }

        for i := 1; i < len(parts); i++ {
            if len(parts[i]) == 0 || parts[i] == "." {
                return qualifiedName, qualifiedNameNotSpecifiedErr()
            }
        }

        qualifiedName.EntityName = strings.Join(parts[2:], "/")
        if len(parts) == 4 {
            qualifiedName.packageName = parts[2]
        }
        qualifiedName.entity = parts[len(parts)-1]
    } else {
        if len(name) == 0 || name == "." {
            return qualifiedName, qualifiedNameNotSpecifiedErr()
        }

        qualifiedName.entity = parts[len(parts)-1]
        if len(parts) == 2 {
            qualifiedName.packageName = parts[0]
        }
        qualifiedName.EntityName = name
        qualifiedName.namespace = getNamespaceFromProp()
    }

    whisk.Debug(whisk.DbgInfo, "Qualified pkg+entity (EntityName): %s\n", qualifiedName.GetEntityName())
    whisk.Debug(whisk.DbgInfo, "Qualified namespace: %s\n", qualifiedName.GetNamespace())
    whisk.Debug(whisk.DbgInfo, "Qualified package: %s\n", qualifiedName.GetPackageName())
    whisk.Debug(whisk.DbgInfo, "Qualified entity: %s\n", qualifiedName.GetEntity())

    return qualifiedName, nil
}

/////////////////////
// Error Functions //
/////////////////////

//  qualifiedNameNotSpecifiedErr() returns generic whisk error for
//      invalid qualified names detected while building a new
//      QualifiedName struct.
func qualifiedNameNotSpecifiedErr() error {
    whisk.Debug(whisk.DbgError, "A valid qualified name was not detected\n")
    errStr := wski18n.T("A valid qualified name must be specified.")
    return whisk.MakeWskError(errors.New(errStr), whisk.NOT_ALLOWED, whisk.DISPLAY_MSG, whisk.NO_DISPLAY_USAGE)
}

//  NewQualifiedNameError(entityName, err) returns specific whisk error
//      for invalid qualified names.
func NewQualifiedNameError(entityName string, err error) (error) {
    whisk.Debug(whisk.DbgError, "NewQualifiedName(%s) failed: %s\n", entityName, err)

    errMsg := wski18n.T(
        "'{{.name}}' is not a valid qualified name: {{.err}}",
        map[string]interface{}{
            "name": entityName,
            "err": err,
        })

    return whisk.MakeWskError(errors.New(errMsg), whisk.EXIT_CODE_ERR_GENERAL, whisk.DISPLAY_MSG, whisk.DISPLAY_USAGE)
}

///////////////////////////
// Helper/Misc Functions //
///////////////////////////

//  addLeadSlash(name) returns a (possibly fully qualified) resource name,
//      inserting a leading '/' if it is of 3 parts (namespace/package/action)
//      and lacking the leading '/'.
func addLeadSlash(name string) string {
    parts := strings.Split(name, "/")
    if len(parts) == 3 && parts[0] != "" {
        name = "/" + name
    }
    return name
}

//  getNamespaceFromProp() returns a namespace from Properties if one exists,
//      else defaults to returning "_"
func getNamespaceFromProp() (string) {
    namespace := "_"

    if Properties.Namespace != "" {
        namespace = Properties.Namespace
    }

    return namespace
}

//  getQualifiedName(name, namespace) returns a fully qualified name given a
//      (possibly fully qualified) resource name and optional namespace.
//
//  Examples:
//      (foo, None) => /_/foo
//      (pkg/foo, None) => /_/pkg/foo
//      (foo, ns) => /ns/foo
//      (/ns/pkg/foo, None) => /ns/pkg/foo
//      (/ns/pkg/foo, otherns) => /ns/pkg/foo
func getQualifiedName(name string, namespace string) (string) {
    name = addLeadSlash(name)
    if strings.HasPrefix(name, "/") {
        return name
    } else if strings.HasPrefix(namespace, "/")  {
        return fmt.Sprintf("%s/%s", namespace, name)
    } else {
        if len(namespace) == 0 {
            namespace = Properties.Namespace
        }
        return fmt.Sprintf("/%s/%s", namespace, name)
    }
}
