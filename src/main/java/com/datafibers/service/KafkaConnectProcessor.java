package com.datafibers.service;

import com.datafibers.model.DFJobPOPJ;
import com.datafibers.util.ConstantApp;
import com.hubrick.vertx.rest.MediaType;
import com.hubrick.vertx.rest.RestClient;
import com.hubrick.vertx.rest.RestClientRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class KafkaConnectProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConnectProcessor.class);

    public KafkaConnectProcessor(){

    }

    /**
     * This method first decode the REST POST request to DFJobPOPJ object. Then, it updates its job status and repack
     * for Kafka REST POST. After that, it forward the new POST to Kafka Connect.
     * Once REST API forward is successful, update data to the local repository.
     *
     * @param routingContext This is the contect from REST API POST
     * @param restClient This is vertx non-blocking rest client used for forwarding
     * @param mongoClient This is the client used to insert final data to repository - mongodb
     * @param mongoCOLLECTION This is mongodb collection name
     * @param dfJobResponsed This is the response object return to rest client or ui or mongo insert
     */
    public static void forwardPOSTAsAddOne(RoutingContext routingContext, RestClient restClient, MongoClient mongoClient,
                                     String mongoCOLLECTION, DFJobPOPJ dfJobResponsed) {
        // Create REST Client for Kafka Connect REST Forward
        final RestClientRequest postRestClientRequest = restClient.post(ConstantApp.KAFKA_CONNECT_REST_URL, String.class,
                portRestResponse -> {
                    String rs = portRestResponse.getBody();
                    JsonObject jo = new JsonObject(rs);
                    LOG.debug("json object name: " + jo.getString("name"));
                    LOG.debug("json object config: " + jo.getJsonObject("config"));
                    LOG.debug("json object tasks: " + jo.getMap().get("tasks"));
                    LOG.info("received response from Kafka server: " + portRestResponse.statusMessage());
                    LOG.info("received response from Kafka server: " + portRestResponse.statusCode());

                    // Once REST API forward is successful, add the record to the local repository
                    mongoClient.insert(mongoCOLLECTION, dfJobResponsed.toJson(), r -> routingContext
                            .response().setStatusCode(ConstantApp.STATUS_CODE_OK_CREATED)
                            .putHeader(ConstantApp.CONTENT_TYPE, ConstantApp.APPLICATION_JSON_CHARSET_UTF_8)
                            .end(Json.encodePrettily(dfJobResponsed.setId(r.result()))));
                });

        postRestClientRequest.exceptionHandler(exception -> {
            routingContext.response().setStatusCode(ConstantApp.STATUS_CODE_CONFLICT)
                    .putHeader(ConstantApp.CONTENT_TYPE, ConstantApp.APPLICATION_JSON_CHARSET_UTF_8)
                    .end(errorMsg(10, "POST Request exception - " + exception.toString()));
        });

        postRestClientRequest.setContentType(MediaType.APPLICATION_JSON);
        postRestClientRequest.setAcceptHeader(Arrays.asList(MediaType.APPLICATION_JSON));
        postRestClientRequest.end(dfJobResponsed.toKafkaConnectJson().toString());

    }

    /**
     * This method first decode the REST PUT request to DFJobPOPJ object. Then, it updates its job status and repack
     * for Kafka REST PUT. After that, it forward the new POST to Kafka Connect.
     * Once REST API forward is successful, update data to the local repository.
     *
     * @param routingContext This is the contect from REST API POST
     * @param restClient This is vertx non-blocking rest client used for forwarding
     * @param mongoClient This is the client used to insert final data to repository - mongodb
     * @param mongoCOLLECTION This is mongodb collection name
     * @param dfJobResponsed This is the response object return to rest client or ui or mongo insert
     */
    public static void forwardPUTAsUpdateOne (RoutingContext routingContext, RestClient restClient, MongoClient mongoClient,
                                         String mongoCOLLECTION, DFJobPOPJ dfJobResponsed) {
        final String id = routingContext.request().getParam("id");
        LOG.info("connectorConfig has change. Will forward to Kafka Connect.");

        final RestClientRequest postRestClientRequest =
                restClient.put(
                        ConstantApp.KAFKA_CONNECT_PLUGIN_CONFIG.
                                replace("CONNECTOR_NAME_PLACEHOLDER", dfJobResponsed.getConnector()),
                        String.class, portRestResponse -> {
                            LOG.info("received response from Kafka server: " + portRestResponse.statusMessage());
                            LOG.info("received response from Kafka server: " + portRestResponse.statusCode());
                        });

        postRestClientRequest.exceptionHandler(exception -> {
            routingContext.response().setStatusCode(ConstantApp.STATUS_CODE_CONFLICT)
                    .putHeader(ConstantApp.CONTENT_TYPE, ConstantApp.APPLICATION_JSON_CHARSET_UTF_8)
                    .end(errorMsg(31, "POST Request exception - " + exception.toString()));
        });

        postRestClientRequest.setContentType(MediaType.APPLICATION_JSON);
        postRestClientRequest.setAcceptHeader(Arrays.asList(MediaType.APPLICATION_JSON));
        postRestClientRequest.end(dfJobResponsed.mapToJsonString(dfJobResponsed.getConnectorConfig()));

        mongoClient.updateCollection(mongoCOLLECTION, new JsonObject().put("_id", id), // Select a unique document
                // The update syntax: {$set, the json object containing the fields to update}
                new JsonObject().put("$set", dfJobResponsed.toJson()), v -> {
                    if (v.failed()) {
                        routingContext.response().setStatusCode(ConstantApp.STATUS_CODE_NOT_FOUND)
                                .end(errorMsg(32, "updateOne to repository is failed."));
                    } else {
                        routingContext.response()
                                .putHeader(ConstantApp.CONTENT_TYPE,
                                        ConstantApp.APPLICATION_JSON_CHARSET_UTF_8).end();
                    }
                });
    }

    public static void forwardDELETEAsDeleteOne (RoutingContext routingContext, RestClient restClient, MongoClient mongoClient,
                                              String mongoCOLLECTION, DFJobPOPJ dfJobResponsed) {
        String id = routingContext.request().getParam("id");
        // Create REST Client for Kafka Connect REST Forward
        final RestClientRequest postRestClientRequest = restClient.delete(ConstantApp.KAFKA_CONNECT_REST_URL + "/" +
                        dfJobResponsed.getConnector(), String.class,
                portRestResponse -> {
                    LOG.info("received response from Kafka server: " + portRestResponse.statusMessage());
                    LOG.info("received response from Kafka server: " + portRestResponse.statusCode());
                    if(portRestResponse.statusCode() == 409) {
                        // Once REST API forward is successful, delete the record to the local repository
                        mongoClient.removeDocument(mongoCOLLECTION, new JsonObject().put("_id", id),
                                ar -> routingContext.response().end(id + " is deleted from repository."));
                    } else {
                        LOG.error("DELETE conflict and rebalance is in process.");
                    }
                });

        postRestClientRequest.exceptionHandler(exception -> {
            routingContext.response().setStatusCode(ConstantApp.STATUS_CODE_CONFLICT)
                    .putHeader(ConstantApp.CONTENT_TYPE, ConstantApp.APPLICATION_JSON_CHARSET_UTF_8)
                    .end(errorMsg(40, "DELETE Request exception - " + exception.toString()));
        });

        postRestClientRequest.setContentType(MediaType.APPLICATION_JSON);
        postRestClientRequest.setAcceptHeader(Arrays.asList(MediaType.APPLICATION_JSON));
        postRestClientRequest.end("");
    }

    /**
     * Print error message in better JSON format
     *
     * @param error_code
     * @param msg
     * @return
     */
    public static String errorMsg(int error_code, String msg) {
        return Json.encodePrettily(new JsonObject()
                .put("code", String.format("%6d", error_code))
                .put("message", msg));
    }
}
