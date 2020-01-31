package com.surpassun.datalab.nifi.reporting;

import io.searchbox.client.JestClient;
import io.searchbox.core.Index;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.ReportingContext;

import java.io.IOException;
import java.util.*;

/**
 * A reportor for Elastic Search for NiFi instance and JVM related metrics.
 * <p>
 * This reportor collects process group's information and JVM information.
 * And sends them to configured Elastic Search URL
 *
 * @author Xun REN
 */

@Tags({"metrics", "reporting", "elasticSearch"})
@CapabilityDescription("This reporting task reports the status of NiFi process groups " +
        "to ElasticSearch. It can be optionally used for a specific" +
        "process group if a property with the group id is provided.")
public class StatusElasticSearchReportor extends AbstractElasticsearchReportor {

    /**
     * Metrics of the process group with this ID should be reported. If not specified, use the root process group.
     */
    protected static final PropertyDescriptor PROCESS_GROUP_IDs = new PropertyDescriptor.Builder()
            .name("process group id")
            .displayName("Process Group IDs")
            .description("The IDs of the process groups to report. Separate by comma for multiple groups")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> descriptors = super.getSupportedPropertyDescriptors();
        descriptors.add(PROCESS_GROUP_IDs);
        return descriptors;
    }

    @Override
    public void onTrigger(ReportingContext context) {
        final boolean isClustered = context.isClustered();
        final String nodeId = context.getClusterNodeIdentifier();
        if (nodeId == null && isClustered) {
            getLogger().debug("This instance of NiFi is configured for clustering, but the Cluster Node Identifier is not yet available. "
                    + "Will wait for Node Identifier to be established.");
            return;
        }

        String groupIdString = context.getProperty(PROCESS_GROUP_IDs).evaluateAttributeExpressions().getValue();
        String[] groupIds = groupIdString.split(",");

        for (String groupId : groupIds) {
            if (groupIds != null) {
                ProcessGroupStatus statusToReport = context.getEventAccess().getGroupStatus(groupId);
                if (statusToReport != null) {
                    // Because the doc is long, index them one by one instead of batch mode to avoid SocketTimeoutException from ElasticSearch
                    Map<String, Object> doc = createDoc(statusToReport, groupId);
                    if (doc != null && doc.size() > 0) {
                        indexDoc(doc, context);
                    }
                } else {
                    getLogger().error("Process group with provided group id could not be found.");
                }
            }
        }

    }

    private Map<String, Object> createDoc(ProcessGroupStatus statusToReport, String groupId) {
        if (statusToReport != null) {
            Map<String, Object> event = new HashMap<>();
            event.put("@timestamp", tf.format(new Date()));
            event.put("reportId", statusToReport.getId());
            event.put("groupId", groupId);
            event.put("name", statusToReport.getName());
            event.put("activeThreadCount", statusToReport.getActiveThreadCount());
            event.put("bytesRead", statusToReport.getBytesRead());
            event.put("bytesWritten", statusToReport.getBytesWritten());
            event.put("bytesReceived", statusToReport.getBytesReceived());
            event.put("bytesSent", statusToReport.getBytesSent());
            event.put("bytesTransferred", statusToReport.getBytesTransferred());
            event.put("connectionStatus", statusToReport.getConnectionStatus());
            event.put("flowFilesReceived", statusToReport.getFlowFilesReceived());
            event.put("flowFilesSent", statusToReport.getFlowFilesSent());
            event.put("flowFilesTransferred", statusToReport.getFlowFilesTransferred());
            event.put("inputCount", statusToReport.getInputCount());
            event.put("inputContentSize", statusToReport.getInputContentSize());
            event.put("inputPortStatus", statusToReport.getInputPortStatus());
            event.put("outputContentSize", statusToReport.getOutputContentSize());
            event.put("outputCount", statusToReport.getOutputCount());
            event.put("outputPortStatus", statusToReport.getOutputPortStatus());
            List<ProcessGroupStatus> processGroupStatuses = (List<ProcessGroupStatus>)statusToReport.getProcessGroupStatus();
            if (processGroupStatuses != null && ! processGroupStatuses.isEmpty()) {
                processGroupStatuses.forEach(s -> {
                    s.setProcessGroupStatus(Collections.emptyList());
                    s.setConnectionStatus(Collections.emptyList());
                });
            }
            event.put("processGroupStatus", processGroupStatuses);
            event.put("processorStatus", statusToReport.getProcessorStatus());
            event.put("queuedContentSize", statusToReport.getQueuedContentSize());
            event.put("terminatedThreadCount", statusToReport.getTerminatedThreadCount());

            return event;
        } else {
            return null;
        }
    }

    private void indexDoc(Map<String, Object> doc, ReportingContext context) {
        final String elasticsearchUrl = context.getProperty(ELASTICSEARCH_URL).getValue();
        final String elasticsearchIndex = context.getProperty(ELASTICSEARCH_INDEX).evaluateAttributeExpressions().getValue();
        String dateString = df.format(new Date());
        final String elasticsearchType = context.getProperty(ELASTICSEARCH_DOC_TYPE).evaluateAttributeExpressions().getValue();

        final JestClient client = getJestClient(elasticsearchUrl);

        if (getLogger().isDebugEnabled()) {
            getLogger().debug("The document to be inserted: " + doc.toString());
        }
        final Index index = new Index.Builder(doc)
                .index(elasticsearchIndex + "-" + dateString)
                .type(elasticsearchType)
                .build();
        try {
            client.execute(index);
        } catch (IOException e) {
            getLogger().error("Error while indexing events into Elastic Search", e);
        }
    }

}