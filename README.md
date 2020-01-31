[![Build Status](https://travis-ci.org/jfrazee/nifi-provenance-reporting-bundle.svg?branch=master)](https://travis-ci.org/jfrazee/nifi-provenance-reporting-bundle)

# nifi-provenance-reporting-bundle

NiFi provenance reporting tasks.

## Table of Contents

- [Installation](#installation)
- [Tasks](#tasks)
    - [BulletinElasticSearchReportor](#BulletinElasticSearchReportor)
    - [JVMMetricsElasticSearchReportor](#JVMMetricsElasticSearchReportor)
    - [StatusElasticSearchReportor](#StatusElasticSearchReportor)

## Installation

```sh
$ mvn clean package
$ cp nifi-reporting-nar/target/nifi-reporting-nar-1.0.0.nar $NIFI_HOME/lib
$ nifi restart
```

## ElasticSearch
Elastic Search templates and mappings could be found in the directory ./elasticSearch
Then you could create yourself the index pattern in Kibana

## Dashboards
You could chose to use both Kibana and Grafana or other solution for creating dashboards
Here, I am using Grafana to centralize with other monitoring dashboards.
You could find the screenshots in the files
![Dashboard_1.png](./Dashboard_1.png?raw=true)

and 

![Dashboard_2.png](./Dashboard_2.png?raw=true)
 