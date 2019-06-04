# Chronograf

##  An Open-Source Time Series Visualization Tool

[Chronograf](https://github.com/influxdata/chronograf) is an open-source web application built by the folks over at [InfluxData](https://influxdata.com) and written in Go and React.js that provides the tools to visualize your monitoring data and easily create alerting and automation rules.



# InfluxDB

##  An Open-Source Time Series Database

[InfluxDB](https://github.com/influxdata/influxdb) is an open source time series database built by the folks over at [InfluxData](https://influxdata.com) with no external dependencies. It's useful for recording metrics, events, and performing analytics.


# Kapacitor

##  An Open-Source Time Series ETL and Alerting Engine

[Kapacitor](https://github.com/influxdata/kapacitor) is an open-source framework built by the folks over at [InfluxData](https://influxdata.com) and written in Go for processing, monitoring, and alerting on time series data 


# Telegraf

[Telegraf](https://github.com/influxdata/telegraf) is a plugin-driven server agent written by the folks over at [InfluxData](https://influxdata.com) for collecting & reporting metrics.




##  Process of installation

 Labeling on nodes: 
  1. add next labels to nodes: 
  ```
    kubectl label node rak8s000 node-role=master
    kubectl label node rak8s001 node-role=worker
    kubectl label node rak8s002 node-role=worker
  ```


 1. Install influxDB
    `helm install --wait --name influxdb --namespace tick influxdb`
 2. Install Capacitor
    `helm install --wait --name kapacitor --namespace tick kapacitor`
 3. Install Telegraf 
    `helm install --wait --name telegraf-s --namespace tick telegraf-s`
 4. Install Chronograf
    `helm install --wait --name chronograf --namespace tick chronograf`



 After installation status of pods should be 
 ```
tick          chronograf-chronograf-5f7877d656-7nkmk   1/1       Running   0          40m       10.244.1.13   rak8s101   <none>           <none>
tick          influxdb-influxdb-6944cc9fd6-56nkh       1/1       Running   0          16h       10.244.1.10   rak8s101   <none>           <none>
tick          kapacitor-kapacitor-547d697845-cdbfw     1/1       Running   0          46m       10.244.1.12   rak8s101   <none>           <none>
tick          telegraf-s-telegraf-s-b478d7c4f-gqb9k    1/1       Running   0          5m56s     10.244.0.73   rak8s100   <none>           <none>
```

 Connection to InfluxDB and 