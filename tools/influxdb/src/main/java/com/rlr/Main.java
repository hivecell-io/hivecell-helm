package com.rlr;

import org.influxdb.BatchOptions;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;


import java.util.Random;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        InfluxDB influxDB = InfluxDBFactory.connect("http://localhost:8086", "root", "root");
        String dbName = "aTimeSeries";
        influxDB.query(new Query("CREATE DATABASE " + dbName));
        influxDB.setDatabase(dbName);
        String rpName = "aRetentionPolicy";
        influxDB.query(new Query("CREATE RETENTION POLICY " + rpName + " ON " + dbName + " DURATION 30h REPLICATION 2 SHARD DURATION 30m DEFAULT"));
        influxDB.setRetentionPolicy(rpName);

        influxDB.enableBatch(BatchOptions.DEFAULTS);
        Random random = new Random();
        while(true) {
            influxDB.write(Point.measurement("measurement")
                    .time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                    .addField("IOT_98415_telemetry", random.nextLong())
                    .addField("IOT_98475_telemetry", random.nextLong())
                    .addField("IOT_98405_telemetry", random.nextLong())
                    .build());

        }
        //Query query = new Query("SELECT cpu FROM idle", dbName);
        //influxDB.query(query);
//        influxDB.query(new Query("DROP RETENTION POLICY " + rpName + " ON " + dbName));
//        influxDB.query(new Query("DROP DATABASE " + dbName));
        //influxDB.query(query, 20, queryResult -> System.out.println(queryResult));
        //influxDB.close();
    }
}
