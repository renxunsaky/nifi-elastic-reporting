package com.surpassun.datalab.nifi.reporting;

import com.yammer.metrics.core.VirtualMachineMetrics;
import io.searchbox.client.JestClient;
import io.searchbox.core.Index;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.reporting.util.metrics.MetricsService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * A reportor for Elastic Search for JVM related metrics.
 * <p>
 * This reportor collects JVM information.
 * And sends them to configured Elastic Search URL
 *
 * @author Xun REN
 */

@Tags({"metrics", "jvm", "reporting", "elasticSearch"})
@CapabilityDescription("This reporting task reports a set of metrics regarding the JVM" +
        "to ElasticSearch.")
public class JVMMetricsElasticSearchReportor extends AbstractElasticsearchReportor {

    private final MetricsService metricsService = new MetricsService();

    private static String hostname = getHostname();

    @Override
    public void onTrigger(ReportingContext context) {
        final boolean isClustered = context.isClustered();
        final String nodeId = context.getClusterNodeIdentifier();
        if (nodeId == null && isClustered) {
            getLogger().debug("This instance of NiFi is configured for clustering, but the Cluster Node Identifier is not yet available. "
                    + "Will wait for Node Identifier to be established.");
            return;
        }

        final VirtualMachineMetrics virtualMachineMetrics = VirtualMachineMetrics.getInstance();
        final Map<String, String> jvmMetrics = metricsService.getMetrics(virtualMachineMetrics);
        jvmMetrics.put("nodeId", nodeId);
        jvmMetrics.put("hostname", hostname);
        jvmMetrics.put("@timestamp", tf.format(new Date()));
        indexMetrics(jvmMetrics, context);
    }

    private void indexMetrics(Map<String, String> doc, ReportingContext context) {
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

    /**
     * Get AWS Route53 DNS name
     * @return hostname
     */
    private static String getHostname() {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("bash", "-c", "ec2-metadata | grep 'hostname=' | cut -d'=' -f2");
        String hostname = null;
        try {

            Process process = processBuilder.start();
            hostname = IOUtils.toString(process.getInputStream(), StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return hostname;
    }
}