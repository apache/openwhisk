<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
-->
#### Distributed Deployment using OpenStack as IaaS

To communicate with the Openstack APIs, the bootstapper will need a few additional dependencies to be installed.

Install prerequisites
```
sudo apt-get -y install python-setuptools python-dev libssl-dev build-essential libssl-dev libffi-dev python-dev python-novaclient
sudo pip install shade pytz positional appdirs monotonic rfc3986 pyparsing stevedore debtcollector netaddr oslo.config futures warlock six
```

Populate the [OpenStack .env file](openstack.env) with valid credentials/endpoint. Please note that OS_WSK_DB_VOLUME is optional. If not specified, local disk will be used instead of persistent disk for CouchDB.

By default, 2 invokers are created. To adjust this value, simply change the num_instances value in the [environments/distributed/group_vars/all](environments/distributed/group_vars/all:67) file.

Once the group_vars and .env files have been populated, run the following playbook to boot instances and generate the respective hosts file.
```
ansible-playbook -i environments/distributed provision_env_dist.yml
```

Ensure the VMs are up and that the bootstrapper can reach them
```
ansible all -i environments/distributed -m ping
```
