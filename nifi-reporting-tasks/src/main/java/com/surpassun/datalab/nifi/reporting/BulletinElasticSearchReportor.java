package com.surpassun.datalab.nifi.reporting;

import io.searchbox.client.JestClient;
import io.searchbox.core.Index;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.Restricted;
import org.apache.nifi.annotation.behavior.Restriction;
import org.apache.nifi.annotation.behavior.Stateful;
import org.apache.nifi.annotation.configuration.DefaultSchedule;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.RequiredPermission;
import org.apache.nifi.components.state.Scope;
import org.apache.nifi.components.state.StateManager;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.Bulletin;
import org.apache.nifi.reporting.BulletinQuery;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.scheduling.SchedulingStrategy;

import java.io.IOException;
import java.util.Date;
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

@Tags({"bulletin", "reporting", "elasticSearch"})
@CapabilityDescription("This reporting task reports the bulletin events of NiFi process groups " +
        "to ElasticSearch. It can be optionally used for a specific" +
        "process group if a property with the group id is provided.")
@Stateful(scopes = Scope.CLUSTER, description = "After querying the "
        + "bulletin repository, the last seen event id is stored so "
        + "reporting can persist across restarts of the reporting task or "
        + "NiFi. To clear the maximum values, clear the state of the processor "
        + "per the State Management documentation.")
@Restricted(
        restrictions = {
                @Restriction(
                        requiredPermission = RequiredPermission.EXPORT_NIFI_DETAILS,
                        explanation = "Provides operator the ability to send sensitive details contained in bulletin events to any external system.")
        }
)
@DefaultSchedule(strategy = SchedulingStrategy.TIMER_DRIVEN, period = "1 min")
public class BulletinElasticSearchReportor extends AbstractElasticsearchReportor {

    /**
     * Metrics of the process group with this ID should be reported. If not specified, use the root process group.
     */
    protected static final PropertyDescriptor PROCESS_GROUP_IDs = new PropertyDescriptor.Builder()
            .name("process group id")
            .displayName("Process Group IDs")
            .description("The IDs of the process groups to report. Separate by comma for multiple groups. If not specified, it will monitor on the root group")
            .required(false)
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

        final StateManager stateManager = context.getStateManager();
        long lastEventId = getLastEventId(stateManager);
        getLogger().info("starting bulletin event id: " + lastEventId);

        String groupIdString = context.getProperty(PROCESS_GROUP_IDs).evaluateAttributeExpressions().getValue();
        String[] groupIds = StringUtils.split(groupIdString, ",");

        if (groupIds != null && groupIds.length > 0) {
            for (String groupId : groupIds) {
                BulletinQuery bulletinQuery = new BulletinQuery.Builder().after(lastEventId).groupIdMatches(groupId).build();
                queryForBulletin(bulletinQuery, context, lastEventId);
            }
        } else {
            BulletinQuery bulletinQuery = new BulletinQuery.Builder().after(lastEventId).build();
            queryForBulletin(bulletinQuery, context, lastEventId);
        }

    }

    private void queryForBulletin(BulletinQuery bulletinQuery, ReportingContext context, long lastEventId) {
        final List<Bulletin> bulletins = context.getBulletinRepository().findBulletins(bulletinQuery);

        if(bulletins == null || bulletins.isEmpty()) {
            if (getLogger().isDebugEnabled()) {
                getLogger().debug("No events to send because no events are stored in the repository.");
            }
            return;
        }

        long currMaxId = -1;
        for (Bulletin bulletin : bulletins) {
            indexDoc(createDoc(bulletin), context);
            if (bulletin.getId() > currMaxId) {
                currMaxId = bulletin.getId();
            }
        }

        try {
            if (currMaxId < lastEventId) {
                getLogger().warn("Current bulletin max id is {} which is less than what was stored in state as the last queried event, which was {}. "
                        + "This means the bulletins repository restarted its ids. Restarting querying from the beginning.", new Object[]{currMaxId, lastEventId});
                setLastEventId(context.getStateManager(), -1L);
            } else if (currMaxId == lastEventId) {
                if (getLogger().isDebugEnabled()) {
                    getLogger().debug("No events to send due to the current max id being equal to the last id that was sent.");
                }
            } else {
                setLastEventId(context.getStateManager(), currMaxId);
                if (getLogger().isDebugEnabled()) {
                    getLogger().debug("Current bulletin ID increased to : " + currMaxId);
                }
            }
        } catch (IOException e) {
            getLogger().error("Error while setting last event ID" + lastEventId, e);
        }
    }

    private Map<String, Object> createDoc(Bulletin bulletin) {
        if (bulletin != null) {
            Map<String, Object> event = new HashMap<>();
            event.put("bulletinId", bulletin.getId());
            event.put("category", bulletin.getCategory());
            event.put("groupId", bulletin.getGroupId());
            event.put("groupName", bulletin.getGroupName());
            event.put("level", bulletin.getLevel());
            event.put("message", bulletin.getMessage());
            event.put("nodeAddress", bulletin.getNodeAddress());
            event.put("sourceId", bulletin.getSourceId());
            event.put("sourceName", bulletin.getSourceName());
            event.put("sourceType", bulletin.getSourceType());
            event.put("@timestamp", bulletin.getTimestamp());

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