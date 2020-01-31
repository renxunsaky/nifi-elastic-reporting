package com.surpassun.datalab.nifi.reporting;

import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.config.HttpClientConfig;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.state.Scope;
import org.apache.nifi.components.state.StateManager;
import org.apache.nifi.components.state.StateMap;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.AbstractReportingTask;
import org.apache.nifi.reporting.ReportingInitializationContext;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A reportor for Elastic Search for NiFi instance and JVM related metrics.
 * <p>
 * This reportor collects process group's information and JVM information.
 * And sends them to configured Elastic Search URL
 *
 * @author Xun REN
 */
public abstract class AbstractElasticsearchReportor extends AbstractReportingTask {

    protected JestClient jestClient;
    protected JestClientFactory jestClientFactory;
    protected final SimpleDateFormat df = new SimpleDateFormat("yyyy.MM.dd");
    protected final SimpleDateFormat tf = new SimpleDateFormat ("YYYY-MM-dd'T'HH:mm:ss.SSS'Z'");

    public static final PropertyDescriptor ELASTICSEARCH_URL = new PropertyDescriptor
            .Builder().name("Elasticsearch URL")
            .displayName("Elasticsearch URL")
            .description("The address for Elasticsearch")
            .required(true)
            .defaultValue("http://localhost:9200")
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    public static final PropertyDescriptor ELASTICSEARCH_INDEX = new PropertyDescriptor
            .Builder().name("Index")
            .displayName("Index")
            .description("The name of the Elasticsearch index")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor ELASTICSEARCH_DOC_TYPE = new PropertyDescriptor
            .Builder().name("Document Type")
            .displayName("Document Type")
            .description("The type of documents to insert into the index")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();



    @Override
    protected void init(ReportingInitializationContext config) {
        this.jestClientFactory = new JestClientFactory();
    }

    protected long getLastEventId(StateManager stateManager) {
        try {
            final StateMap stateMap = stateManager.getState(Scope.CLUSTER);
            final String lastEventIdStr = stateMap.get("lastEventId");
            final long lastEventId = lastEventIdStr != null ? Long.parseLong(lastEventIdStr) : 0;
            return lastEventId;
        } catch (final IOException ioe) {
            getLogger().warn("Failed to retrieve the last event id from the "
                    + "state manager.", ioe);
            return 0;
        }
    }

    protected void setLastEventId(StateManager stateManager, long eventId) throws IOException {
        final StateMap stateMap = stateManager.getState(Scope.CLUSTER);
        final Map<String, String> statePropertyMap = new HashMap<>(stateMap.toMap());
        statePropertyMap.put("lastEventId", Long.toString(eventId));
        stateManager.setState(statePropertyMap, Scope.CLUSTER);
    }

    protected JestClient getJestClient(String elasticsearch) {
        if (jestClient == null) {
            getLogger().info("Initialize Jest Client for ElasticSearch, URL: " + elasticsearch);
            jestClientFactory.setHttpClientConfig(
                    new HttpClientConfig.Builder(elasticsearch)
                            .multiThreaded(true)
                            .build()
            );
            this.jestClient = jestClientFactory.getObject();

        }

        return jestClient;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(ELASTICSEARCH_URL);
        descriptors.add(ELASTICSEARCH_INDEX);
        descriptors.add(ELASTICSEARCH_DOC_TYPE);
        return descriptors;
    }
}