# Search Guard SSL for Elasticsearch 5.0.2

Search Guard SSL is a free and open source plugin for Elasticsearch which provides SSL for Elasticsearch. 
It does not provide authentication and authorization. For that pls refer to [Search Guard](https://github.com/floragunncom/search-guard).

![Logo](https://raw.githubusercontent.com/floragunncom/sg-assets/master/logo/sg_logo_small.jpg) 

##Features
* Node-to-node encryption through SSL/TLS (Transport layer)
* Secure REST layer through HTTPS (SSL/TLS)
* Supports JDK SSL and Open SSL
* Only external dependency is netty tcnative if Open SSL is used
* Works with Kibana, logstash and beats

##Installation
 ``bin/elasticsearch-plugin install -b com.floragunn:search-guard-ssl:5.0.2-19``

_Note_: If you install Search Guard 5 then you must not install this plugin (because SG 5 already contains it). This is different from how it worked with SG 2.

## Elasticsearch 5

[Search Guard SSL 5](https://github.com/floragunncom/search-guard-ssl/tree/5.0.0) (compatible with Elasticsearch 5) is now also available. You may also want to read this [blog post about Search Guard 5](https://floragunn.com/search-guard-5/).

##Documentation
Documentation is provided in a separate repository in markdown format.

[Search Guard SSL Documentation](https://github.com/floragunncom/search-guard-ssl-docs)

##Support
[See wiki](https://github.com/floragunncom/search-guard-ssl/wiki/Support)

###License
Copyright 2015-2016 floragunn GmbH

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   ``http://www.apache.org/licenses/LICENSE-2.0``

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
