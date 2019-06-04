# InfluxDB data generation process 

## 1. Connection to Chronograf and InfluxDB from world 
###    a. To connect to Chronograf execute:
       `kubectl get service -n tick`

       output 

       ```
       chronograf-chronograf   NodePort    10.102.209.128   <none>        80:31700/TCP        4h18m
       influxdb-influxdb       ClusterIP   10.111.60.108    <none>        8086/TCP,8088/TCP   20h
       kapacitor-kapacitor     ClusterIP   10.110.149.185   <none>        9092/TCP            4h25m
       telegraf-s-telegraf-s   ClusterIP   10.103.174.76    <none>        8125/UDP            4h22m
       ```

       to open web ui open via browser url `http://10.1.10.170:31700` ( example )

###    b. To connect to InfluxDB execute:
       `kubectl get pods -n tick`
         

        output
        ```
        chronograf-chronograf-5f7877d656-7nkmk   1/1       Running   0          4h21m
        influxdb-influxdb-6944cc9fd6-vwblc       1/1       Running   0          113m
        kapacitor-kapacitor-547d697845-cdbfw     1/1       Running   0          4h27m
        telegraf-s-telegraf-s-b478d7c4f-gqb9k    1/1       Running   0          3h46m
        ```

        to open connection to InfluxDB execute 
        `kubectl port-forward influxdb-influxdb-6944cc9fd6-vwblc 8086:8086` ( example ) - this will proxy traffic of influx to your local OS, connection will be available at http://localhost:8086

##  2. In influxdb/influx execute next commands 
    `mvn clean build` 
    `mvn clean compile`
    `mvn package`
     
     jar file will be located on target/influx-1.0-SNAPSHOT-jar-with-dependencies.jar 

     Connection to influxdb configured in src/main/java/com.rlr/Main.java `InfluxDB influxDB = InfluxDBFactory.connect("http://localhost:8086", "root", "root");`
     this value should be changed, however for now connection executed through localhost. 


     to perform data generating execute command from `target` subfolder
     `java -jar influx-1.0-SNAPSHOT-jar-with-dependencies.jar` - non't run it now - only on step 4.

 3. Data collection visualisation

     a. Open Chronograf UI `http://10.1.10.170:31700` ( example ) - this is the step of connection Chronograf to InfluxDB and Capasitor 

    b. add InfluxDB connection
        Connection URL - http://influxdb:8086
        Connection Name - any
        Telegraf Database Name - any 
        All other field can be blank
   c. Choose Suggested Dashboards for your Source:
        mark dasboard InfluxDB and System
        click on Create Dashboards 
    d. add Kapacitor Connection
        Kapacitor URL - http://kapacitor-kapacitor:9092/
        Name - Any
    e. Open UI and proceed to Dashboards 

 4. Custom Data Dashboard 
    - on Dasboards page click on `+Create Dashboard` button and set name of new dashboard
    - start generating data with point 2 `java -jar influx-1.0-SNAPSHOT-jar-with-dependencies.jar`
    - click on Add Data button 
    - you will see list of db's - click on `aTimeSeries.aRetentionPolicy` -> `measurments` -> in `Fields` set checkboxes to all 3 IOT_98* checkboxes
      ![intro](images/3.png)
    - on top of screen choose `Visualisation` button 
      ![intro](images/5.png)
    - in the lower left corner choose `Visualisation Type` and then `Table`
    - with green V button in right top corner save table 
    - choose correct size of table on dashboard 
    - choose data range 5 mins and refresh 5 s 
    - now you should observe such table with live data 
      ![intro](images/6.png)


 



