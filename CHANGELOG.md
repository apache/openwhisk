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

# OpenWhisk Core

## Apache 1.0.0
### Branch: [1.0.0](https://github.com/apache/openwhisk/tree/1.0.0)
### Notable changes
- Improvements to parameter encryption to support per-namespace keys. ([#4855](https://github.com/apache/openwhisk/pull/4855), [@rabbah](https://github.com/rabbah))
- Use latest code if action's revision is mismatched. ([#4954](https://github.com/apache/openwhisk/pull/4954), [@upgle](https://github.com/upgle))
- Do not delete previous annotation and support delete annotation via CLI. ([#4940](https://github.com/apache/openwhisk/pull/4940), [@ningyougang](https://github.com/ningyougang))
- Prewarm eviction variance. ([#4916](https://github.com/apache/openwhisk/pull/4916), [@tysonnorris](https://github.com/tysonnorris))
- Allow to get activation list by a binding package name. ([#4919](https://github.com/apache/openwhisk/pull/4919), [@upgle](https://github.com/upgle))
- Allow parent/child transaction ids. ([#4819](https://github.com/apache/openwhisk/pull/4819), [@upgle](https://github.com/upgle))
- Adjust prewarm container dynamically. ([#4871](https://github.com/apache/openwhisk/pull/4871), [@ningyougang](https://github.com/ningyougang))
- Add NodeJS 14 runtime. ([#4902](https://github.com/apache/openwhisk/pull/4902), [@rabbah](https://github.com/rabbah))
- Create AES128 and AES256 encryption for parameters. ([#4756](https://github.com/apache/openwhisk/pull/4756), [@mcdan](https://github.com/mcdan))
- Implement an ElasticSearchActivationStore. ([#4724](https://github.com/apache/openwhisk/pull/4724), [@jiangpengcheng](https://github.com/jiangpengcheng))
- Add Swift 5.1 runtime to runtimes.json. ([#4803](https://github.com/apache/openwhisk/pull/4803), [@dgrove-oss](https://github.com/dgrove-oss))
- Add volume mapping for Docker credentials. ([#4791](https://github.com/apache/openwhisk/pull/4791), [@style95](https://github.com/style95))
- add .NET Core 3.1 runtime kind. ([#4792](https://github.com/apache/openwhisk/pull/4792), [@dgrove-oss](https://github.com/dgrove-oss))
- Add PHP 7.4 runtime. ([#4767](https://github.com/apache/openwhisk/pull/4767), [@akrabat](https://github.com/akrabat))
- Serialize `updated` value of entity document in response. ([#4646](https://github.com/apache/openwhisk/pull/4646), [@upgle](https://github.com/upgle))
- Provide environment at init time. ([#4722](https://github.com/apache/openwhisk/pull/4722), [@upgle](https://github.com/upgle))
- OpenWhisk User Events. ([#4584](https://github.com/apache/openwhisk/pull/4584), [@selfxp](https://github.com/selfxp))
- Openwhisk in a standalone runnable jar. ([#4516](https://github.com/apache/openwhisk/pull/4516), [@chetanmeh](https://github.com/chetanmeh))
- Update Docker client version to 18.06.3. ([#4430](https://github.com/apache/openwhisk/pull/4430), [@style95](https://github.com/style95))
- Add `binding` annotation to record an action path not resolved. ([#4211](https://github.com/apache/openwhisk/pull/4211), [@upgle](https://github.com/upgle))
- Add SPI for invoker. ([#4453](https://github.com/apache/openwhisk/pull/4453), [@style95](https://github.com/style95))
- Enable CouchDB persist_path in a distributed environment as well. ([#4290](https://github.com/apache/openwhisk/pull/4290), [@style95](https://github.com/style95))
- Feature flag to turn on/off support for provide-api-key annotation. ([#4334](https://github.com/apache/openwhisk/pull/4334), [@chetanmeh](https://github.com/chetanmeh))
- Add annotations to inject the API key into the action context. ([#4284](https://github.com/apache/openwhisk/pull/4284), [@rabbah](https://github.com/rabbah))
- Update CosmosDB to 2.4.2. ([#4321](https://github.com/apache/openwhisk/pull/4321), [@chetanmeh](https://github.com/chetanmeh))
- Adding YARNContainerFactory. ([#4129](https://github.com/apache/openwhisk/pull/4129), [@SamHjelmfelt](https://github.com/SamHjelmfelt))
- Allow persisted CouchDB directory mount. ([#4250](https://github.com/apache/openwhisk/pull/4250), [@rabbah](https://github.com/rabbah))
- Bump ephemeral CouchDB to v2.3. ([#4202](https://github.com/apache/openwhisk/pull/4202), [@jonpspri](https://github.com/jonpspri))
- Add Ballerina 0.990.2 runtime. ([#4239](https://github.com/apache/openwhisk/pull/4239), [@rabbah](https://github.com/rabbah))
- Add Swift 4.2 runtime in default deployment. ([#4210](https://github.com/apache/openwhisk/pull/4210), [@csantanapr](https://github.com/csantanapr))
- Add PHP 7.3 runtime. ([#4182](https://github.com/apache/openwhisk/pull/4182), [@akrabat](https://github.com/akrabat))
- Add .NET Core 2.2 runtime. ([#4172](https://github.com/apache/openwhisk/pull/4172), [@shawnallen85](https://github.com/shawnallen85))
- Updated Intellij script to start controller and invoker locally. ([#4142](https://github.com/apache/openwhisk/pull/4142), [@ddragosd](https://github.com/ddragosd))
- Ensure ResultMessage is processed. ([#4135](https://github.com/apache/openwhisk/pull/4135), [@jiangpengcheng](https://github.com/jiangpengcheng))
- Protect Package Bindings from containing circular references. ([#4122](https://github.com/apache/openwhisk/pull/4122), [@asteed](https://github.com/asteed))
- Ensure, that Result-ack is sent before Completion-ack. ([#4115](https://github.com/apache/openwhisk/pull/4115), [@cbickel](https://github.com/cbickel))
- Add NodeJS 10 runtime to default set of runtimes for ansible/vagrant. ([#4124](https://github.com/apache/openwhisk/pull/4124), [@csantanapr](https://github.com/csantanapr))
- Enable concurrent activation processing. ([#2795](https://github.com/apache/openwhisk/pull/2795), [@tysonnorris](https://github.com/tysonnorris))
- Rename the package from whisk to org.apache.openwhisk. ([#4073](https://github.com/apache/openwhisk/pull/4073), [@houshengbo](https://github.com/houshengbo))
- Allow web actions from package bindings. ([#3880](https://github.com/apache/openwhisk/pull/3880), [@upgle](https://github.com/upgle))
- Switch to Scala 2.12.7 ([#4062](https://github.com/apache/openwhisk/pull/4062), [@chetanmeh](https://github.com/chetanmeh))
- Always return activation without logs on blocking invoke. ([#4100](https://github.com/apache/openwhisk/pull/4100), [@cbickel](https://github.com/cbickel))
- Changes to include Go runtime. ([#4093](https://github.com/apache/openwhisk/pull/4093), [@sciabarracom](https://github.com/sciabarracom))
- Send active-ack after log collection for non-blocking activations. ([#4041](https://github.com/apache/openwhisk/pull/4041), [@cbickel](https://github.com/cbickel))
- Increase max-content-length to 50 MB. ([#4059](https://github.com/apache/openwhisk/pull/4059), [@chetanmeh](https://github.com/chetanmeh))
- Using non-root user in controller. ([#3579](https://github.com/apache/openwhisk/pull/3579), [@Himavanth](https://github.com/Himavanth))
- Customize invoker user memory for memory based load-balancing. ([#4011](https://github.com/apache/openwhisk/pull/4011), [@ningyougang](https://github.com/ningyougang))
- Secure the invoker with SSL. ([#3968](https://github.com/apache/openwhisk/pull/3968), [@cbickel](https://github.com/cbickel))
- Reuse a container on `applicationError`. ([#3941](https://github.com/apache/openwhisk/pull/3941), [@tysonnorris](https://github.com/tysonnorris))
- Memory based load-balancing ([#3747](https://github.com/apache/openwhisk/pull/3747), [@cbickel](https://github.com/cbickel))
- Activation ID in header. ([#3671](https://github.com/apache/openwhisk/pull/3671), [@style95](https://github.com/style95))
- Treat action code as attachments. ([#3945](https://github.com/apache/openwhisk/pull/3945), [@chetanmeh](https://github.com/chetanmeh))
- K8S: Implement invoker-node affinity and eliminate usage of kubectl. ([#3963](https://github.com/apache/openwhisk/pull/3963), [@dgrove-oss](https://github.com/dgrove-oss))
- Add Ruby 2.5 runtime support. ([#3725](https://github.com/apache/openwhisk/pull/3725), [@remore](https://github.com/remore))
- S3AttachmentStore. ([#3779](https://github.com/apache/openwhisk/pull/3779), [@chetanmeh](https://github.com/chetanmeh))
- ContainerClient + Akka HTTP alternative to HttpUtils. ([#3812](https://github.com/apache/openwhisk/pull/3812), [@tysonnorris](https://github.com/tysonnorris))
- Throttle the system based on active-ack timeouts. ([#3875](https://github.com/apache/openwhisk/pull/3875), [@markusthoemmes](https://github.com/markusthoemmes))
- Recover image pulls by trying to run the container anyways. ([#3813](https://github.com/apache/openwhisk/pull/3813), [@markusthoemmes](https://github.com/markusthoemmes))
- Use separate DB users for deployed components. ([#3876](https://github.com/apache/openwhisk/pull/3876), [@cbickel](https://github.com/cbickel))
- Introduce SPI to swap authentication directives. ([#3829](https://github.com/apache/openwhisk/pull/3829), [@mhenke1](https://github.com/mhenke1))
- ArtifactStore implementation for CosmosDB. ([#3562](https://github.com/apache/openwhisk/pull/3562), [@chetanmeh](https://github.com/chetanmeh))
- Add support for PHP 7.2 runtime. ([#3736](https://github.com/apache/openwhisk/pull/3736), [@akrabat](https://github.com/akrabat))

## Incubating 0.9.0
### Branch: [0.9.0-incubating](https://github.com/apache/openwhisk/tree/0.9.0-incubating)
### Notable changes
- Initial release.
