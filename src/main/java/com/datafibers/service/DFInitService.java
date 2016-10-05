package com.datafibers.service;

import com.datafibers.model.DFJobPOPJ;
import com.datafibers.processor.FlinkTransformProcessor;
import com.datafibers.util.Runner;
import io.vertx.core.json.JsonObject;
import org.apache.avro.data.Json;
import org.apache.avro.reflect.AvroSchema;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.table.StreamTableEnvironment;
import org.apache.flink.api.java.typeutils.runtime.kryo.Serializers;
import org.apache.flink.api.table.Table;
import org.apache.flink.api.table.TableEnvironment;
import org.apache.flink.api.table.sinks.CsvTableSink;
import org.apache.flink.api.table.sinks.TableSink;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer09;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer09;
import org.apache.flink.streaming.connectors.kafka.Kafka09JsonTableSource;
import org.apache.flink.streaming.connectors.kafka.KafkaJsonTableSource;
import org.apache.flink.streaming.util.serialization.SimpleStringSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Properties;

public class DFInitService {

    private static final Logger LOG = LoggerFactory.getLogger(DFInitService.class);

    public static void main(String[] args) {
        welcome();
        if (null == args || args.length == 0) { // Use all default start settings - standalone with web ui
            Runner.runExample(DFDataProcessor.class);
            LOG.info("Start DF Data Processor in standalone mode ...");
            Runner.runExample(DFWebUI.class);

        } else { // Use full parameters start
            if(args[0].equalsIgnoreCase("c") && args[1].equalsIgnoreCase("ui")) {
                Runner.runClusteredExample(DFDataProcessor.class);
                LOG.info("Start DF Data Processor in cluster mode...");
                Runner.runExample(DFWebUI.class);

            } else if(args[0].equalsIgnoreCase("c") && args[1].equalsIgnoreCase("no-ui")) {
                Runner.runClusteredExample(DFDataProcessor.class);
                LOG.info("Start DF Data Processor in cluster mode without Web UI...");

            } else if(args[0].equalsIgnoreCase("s") && args[1].equalsIgnoreCase("ui")) {
                Runner.runExample(DFDataProcessor.class);
                LOG.info("Start DF Data Processor in standalone mode ...");
                Runner.runExample(DFWebUI.class);

            } else if(args[0].equalsIgnoreCase("s") && args[1].equalsIgnoreCase("no-ui")) {
                Runner.runExample(DFDataProcessor.class);
                LOG.info("Start DF Data Processor in standalone mode without Web UI...");

            } else if(args[0].equalsIgnoreCase("t")) {
                testFlinkRun();
            } else {
                System.err.println("Usage: java -jar DFDataProcessor.jar <SERVICE_TO_DEPLOY> <UI_Enabled>");
                System.err.println("Note:");
                System.err.println("<SERVICE_TO_DEPLOY>=s: Deploy DFDataProcessor vertical in standalone mode.");
                System.err.println("<SERVICE_TO_DEPLOY>=c: Deploy DFDataProcessor vertical in cluster mode.");
                System.err.println("<UI_Enabled>=ui: Deploy Web UI.");
                System.err.println("<UI_Enabled>=no-ui: Start without Web UI.");
                System.exit(0);
            }
        }
        LOG.info("Start DF Services Completed :)");

    }
    public static void welcome() {
        System.out.println(" __    __     _                            _             ___      _          ___ _ _                   \n" +
                "/ / /\\ \\ \\___| | ___ ___  _ __ ___   ___  | |_ ___      /   \\__ _| |_ __ _  / __(_) |__   ___ _ __ ___ \n" +
                "\\ \\/  \\/ / _ \\ |/ __/ _ \\| '_ ` _ \\ / _ \\ | __/ _ \\    / /\\ / _` | __/ _` |/ _\\ | | '_ \\ / _ \\ '__/ __|\n" +
                " \\  /\\  /  __/ | (_| (_) | | | | | |  __/ | || (_) |  / /_// (_| | || (_| / /   | | |_) |  __/ |  \\__ \\\n" +
                "  \\/  \\/ \\___|_|\\___\\___/|_| |_| |_|\\___|  \\__\\___/  /___,' \\__,_|\\__\\__,_\\/    |_|_.__/ \\___|_|  |___/\n" +
                "                                                                                                       ");
    }

    public static void testFlinkRun() {
        LOG.info("Only Unit Testing Function is enabled - Test Flink Run UDF");
        String jarFile = "/home/vagrant/quick-start-1.0-fat.jar";
        FlinkTransformProcessor.runFlinkJar(jarFile, "localhost:6123");
    }

    public static void testFlinkSQL() {

        LOG.info("Only Unit Testing Function is enabled");
        String resultFile = "/home/vagrant/test.txt";

        try {

            String jarPath = DFInitService.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            StreamExecutionEnvironment env = StreamExecutionEnvironment.createRemoteEnvironment("localhost", 6123, jarPath)
                        .setParallelism(1);
            String kafkaTopic = "finance";
            String kafkaTopic_stage = "df_trans_stage_finance";
            String kafkaTopic_out = "df_trans_out_finance";



            StreamTableEnvironment tableEnv = TableEnvironment.getTableEnvironment(env);
            Properties properties = new Properties();
            properties.setProperty("bootstrap.servers", "localhost:9092");
            properties.setProperty("group.id", "consumer3");

            // Internal covert Json String to Json - Begin
            DataStream<String> stream = env
                    .addSource(new FlinkKafkaConsumer09<>(kafkaTopic, new SimpleStringSchema(), properties));

            stream.map(new MapFunction<String, String>() {
                @Override
                public String map(String jsonString) throws Exception {
                    return jsonString.replaceAll("\\\\", "").replace("\"{", "{").replace("}\"","}");
                }
            }).addSink(new FlinkKafkaProducer09<String>("localhost:9092", kafkaTopic_stage, new SimpleStringSchema()));
            // Internal covert Json String to Json - End

            String[] fieldNames =  new String[] {"name"};
            Class<?>[] fieldTypes = new Class<?>[] {String.class};

            KafkaJsonTableSource kafkaTableSource = new Kafka09JsonTableSource(
                    kafkaTopic_stage,
                    properties,
                    fieldNames,
                    fieldTypes);

            //kafkaTableSource.setFailOnMissingField(true);

            tableEnv.registerTableSource("Orders", kafkaTableSource);

            Table result = tableEnv.sql("SELECT STREAM name FROM Orders");

            Files.deleteIfExists(Paths.get(resultFile));

            // create a TableSink
            TableSink sink = new CsvTableSink(resultFile, "|");
            // write the result Table to the TableSink
            result.writeToSink(sink);

            env.execute("FlinkConsumer");

            byte[] message;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
