/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.discovery.oak.cluster;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.discovery.InstanceDescription;
import org.apache.sling.discovery.base.commons.ClusterViewService;
import org.apache.sling.discovery.base.commons.UndefinedClusterViewException;
import org.apache.sling.discovery.base.commons.UndefinedClusterViewException.Reason;
import org.apache.sling.discovery.commons.providers.DefaultInstanceDescription;
import org.apache.sling.discovery.commons.providers.spi.LocalClusterView;
import org.apache.sling.discovery.commons.providers.spi.base.DiscoveryLiteDescriptor;
import org.apache.sling.discovery.commons.providers.spi.base.IdMapService;
import org.apache.sling.discovery.commons.providers.util.LogSilencer;
import org.apache.sling.discovery.commons.providers.util.ResourceHelper;
import org.apache.sling.discovery.oak.Config;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Oak-based implementation of the ClusterViewService interface.
 */
@Component (service = ClusterViewService.class)
public class OakClusterViewService implements ClusterViewService {
    
    private static final String PROPERTY_CLUSTER_ID = "clusterId";
    private static final String PROPERTY_CLUSTER_ID_DEFINED_AT = "clusterIdDefinedAt";
    private static final String PROPERTY_CLUSTER_ID_DEFINED_BY = "clusterIdDefinedBy";

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Reference
    private SlingSettingsService settingsService;

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private Config config;

    @Reference
    private IdMapService idMapService;

    /** the last sequence number read from the oak discovery-lite descriptor **/
    private long lastSeqNum = -1;

    /** the lowest sequence number this class has handled (returned in asClusterView()) successfully */
    private long lowestSeqNum = -1;

    /** timeout (in millis since 1970) while partially started instances are suppressed */
    private long partialStartupSuppressingTimeout = 0;

    /**
     * Keeps track of which id existed in the local cluster - to avoid suppressing those.
     * Note that discovery clusters aren't usually terribly big - so this map shouldn't grow too large ever.
     **/
    private Map<Integer,InstanceInfo> seenLocalInstances = new HashMap<>();

    private final LogSilencer logSilencer = new LogSilencer(logger);

    public static OakClusterViewService testConstructor(SlingSettingsService settingsService,
            ResourceResolverFactory resourceResolverFactory,
            IdMapService idMapService,
            Config config) {
        OakClusterViewService service = new OakClusterViewService();
        service.settingsService = settingsService;
        service.resourceResolverFactory = resourceResolverFactory;
        service.config = config;
        service.idMapService = idMapService;
        service.activate();
        return service;
    }

    @Activate
    public void activate() {
        final String suppressPartiallyStartedInstances = config == null ? "unknown" : String.valueOf(config.getSuppressPartiallyStartedInstances());
        logger.info("activate: suppressPartiallyStartedInstances = " + suppressPartiallyStartedInstances);
    }

    @Override
    public String getSlingId() {
    	if (settingsService==null) {
    		return null;
    	}
        return settingsService.getSlingId();
    }

    protected ResourceResolver getResourceResolver() throws LoginException {
        return resourceResolverFactory.getServiceResourceResolver(null);
    }

    @Override
    public LocalClusterView getLocalClusterView() throws UndefinedClusterViewException {
        logger.trace("getLocalClusterView: start");
        ResourceResolver resourceResolver = null;
        try {
            DiscoveryLiteDescriptor descriptor = null;
            try{
                resourceResolver = getResourceResolver();
                descriptor = DiscoveryLiteDescriptor.getDescriptorFrom(resourceResolver);
            } catch (Exception e) {
                // SLING-10204 : log less noisy as this can legitimately happen
                logger.warn("getLocalClusterView: got Exception (enable debug logging to see stacktrace) : " + e);
                logger.debug("getLocalClusterView: Exception stacktrace", e);
                throw new UndefinedClusterViewException(Reason.REPOSITORY_EXCEPTION, "Exception while processing descriptor: "+e);
            }
            if (lastSeqNum!=descriptor.getSeqNum()) {
                logger.info("getLocalClusterView: sequence number change detected - clearing idmap cache");
                idMapService.clearCache();
                lastSeqNum = descriptor.getSeqNum();
            }
            return asClusterView(descriptor, resourceResolver);
        } catch (UndefinedClusterViewException e) {
            logger.info("getLocalClusterView: undefined clusterView: "+e.getReason()+" - "+e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("getLocalClusterView: repository exception: "+e, e);
            throw new UndefinedClusterViewException(Reason.REPOSITORY_EXCEPTION, "Exception while processing descriptor: "+e);
        } finally {
            logger.trace("getLocalClusterView: end");
            if (resourceResolver!=null) {
                resourceResolver.close();
            }
        }
    }

    private boolean isSyncTokenEnabled() {
        return config != null && config.getSyncTokenEnabled();
    }

    private boolean isPartialSuppressionEnabled() {
        return config != null && config.getSuppressPartiallyStartedInstances();
    }

    private LocalClusterView asClusterView(DiscoveryLiteDescriptor descriptor, ResourceResolver resourceResolver) throws Exception {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        if (resourceResolver==null) {
            throw new IllegalArgumentException("resourceResolver must not be null");
        }
        logger.trace("asClusterView: start");
        String clusterViewId = descriptor.getViewId();
        if (clusterViewId == null || clusterViewId.length() == 0) {
            logger.trace("asClusterView: no clusterId provided by discovery-lite descriptor - reading from repo.");
            clusterViewId = readOrDefineClusterId(resourceResolver);
        }
        final long seqNum = descriptor.getSeqNum();
        String localClusterSyncTokenId = /*descriptor.getViewId()+"_"+*/String.valueOf(seqNum);
        if (!descriptor.isFinal()) {
            throw new UndefinedClusterViewException(Reason.NO_ESTABLISHED_VIEW, "descriptor is not yet final: "+descriptor);
        }
        LocalClusterView cluster = new LocalClusterView(clusterViewId, localClusterSyncTokenId);
        int me = descriptor.getMyId();
        int[] activeIds = descriptor.getActiveIds();
        if (activeIds==null || activeIds.length==0) {
            throw new UndefinedClusterViewException(Reason.NO_ESTABLISHED_VIEW, "Descriptor contained no active ids: "+descriptor.getDescriptorStr());
        }

        final List<Integer> activeIdsList = Arrays.stream( activeIds ).boxed().collect( Collectors.toList() );

        // step 1: sort activeIds by their leaderElectionId
        //   serves two purposes: pos[0] is then leader
        //   and the rest are properly sorted within the cluster

        final ClusterReader reader = new ClusterReader(resourceResolver, config, idMapService, seenLocalInstances);
        final Map<Integer,InstanceInfo> regularInstances = new HashMap<>();
        final Set<Integer> partiallyStartedClusterNodeIds = new HashSet<>();
        boolean suppressionEnabled = isSyncTokenEnabled() && isPartialSuppressionEnabled();

        final InstanceReadResult myInstanceResult = reader.readInstance(me, false);
        final InstanceInfo myInstance = myInstanceResult.getInstanceInfo();
        if (myInstance == null) {
            throw new UndefinedClusterViewException(Reason.NO_ESTABLISHED_VIEW, myInstanceResult.getErrorMsg());
        }

        if (partialStartupSuppressingTimeout > 0
                && partialStartupSuppressingTimeout < System.currentTimeMillis()) {
            // if partial suppression timeout is set and it has passed, then don't suppress
            suppressionEnabled = false;
        }

        if (suppressionEnabled && myInstance.isSyncTokenNewerOrEqual(lowestSeqNum)) {
            // that means that the local instance did store a synctoken ever
            // so it did successfully once go through the syncTokenService
            //
            // as a result we can now start suppressing
        } else {
            // otherwise even the local instance hasn't done a full join ever,
            // so we shouldn't do any suppression just yet
            suppressionEnabled = false;
        }

        // categorize the activeIds into
        // - partiallyStarted : added to partiallyStartedClusterNodeIds
        // - fully started    : added to fullyStartedInstances
        for (Integer id : activeIdsList) {
            if (id == me) {
                regularInstances.put(me, myInstance);
                continue;
            }
            InstanceReadResult readResult = reader.readInstance(id, suppressionEnabled);
            InstanceInfo instanceInfo = readResult.getInstanceInfo();
            if (instanceInfo == null && !suppressionEnabled) {
                // retry with a fresh idmap
                idMapService.clearCache();
                readResult = reader.readInstance(id, suppressionEnabled);
                instanceInfo = readResult.getInstanceInfo();
            }
            if (instanceInfo == null) {
                if (suppressionEnabled) {
                    // then suppress this instance by not adding it to the resultingInstances map
                    partiallyStartedClusterNodeIds.add(id);
                } else {
                    throw new UndefinedClusterViewException(Reason.NO_ESTABLISHED_VIEW, readResult.getErrorMsg());
                }
            } else {
                regularInstances.put(id, instanceInfo);
            }
        }

        if (!partiallyStartedClusterNodeIds.isEmpty()) {
            logSilencer.infoOrDebug("asClusterView : partial instances : " + partiallyStartedClusterNodeIds);
            activeIdsList.removeAll(partiallyStartedClusterNodeIds);
        }

        final List<Integer> sortedIds = leaderElectionSort(regularInstances);

        if (sortedIds.size() != activeIdsList.size()) {
            logger.error("asClusterView : list size mismatch : sorted = " + sortedIds.size()
                + ", active = " + activeIdsList.size() + " (partial = " + partiallyStartedClusterNodeIds.size() + ")");
        }

        boolean seenAllSyncTokens = true;
        for(int i=0; i<sortedIds.size(); i++) {
            int id = sortedIds.get(i);
            boolean isLeader = i==0; // thx to sorting above [0] is leader indeed
            boolean isOwn = id==me;
            InstanceInfo in = regularInstances.get(id);
            String slingId = in == null ? null : in.getSlingId();
            if (slingId == null) {
                idMapService.clearCache();
                logger.info("asClusterView: cannot resolve oak-clusterNodeId {} to a slingId", id);
                throw new Exception("Cannot resolve oak-clusterNodeId "+id+" to a slingId");
            }
            if (!in.isSyncTokenNewerOrEqual(seqNum)) {
                logSilencer.infoOrDebug("Not seen syncToken (" + seqNum + ") of this instance yet : " + in);
                seenAllSyncTokens = false;
            }
            Map<String, String> properties = readProperties(slingId, resourceResolver);
            // create a new instance (adds itself to the cluster in the constructor)
            new DefaultInstanceDescription(cluster, isLeader, isOwn, slingId, properties);
        }
        if (!partiallyStartedClusterNodeIds.isEmpty()) {
            logSilencer.infoOrDebug("asClusterView: partially started instance nearby - clearing idmap cache");
            idMapService.clearCache();
        } else if (!seenAllSyncTokens) {
            logSilencer.infoOrDebug("asClusterView: not seen all syncTokens yet - clearing idmap cache");
            idMapService.clearCache();
        }
        if (!partiallyStartedClusterNodeIds.isEmpty()) {
            logSilencer.infoOrDebug("asClusterView : adding as partially started slingIds: clusterNodeIds = " +
                        partiallyStartedClusterNodeIds);
            cluster.setPartiallyStartedClusterNodeIds(partiallyStartedClusterNodeIds);
        } else {
            logSilencer.reset();
        }

        logger.trace("asClusterView: returning {}", cluster);
        InstanceDescription local = cluster.getLocalInstance();
        if (local == null) {
            logger.info("getClusterView: the local instance ("+getSlingId()+") is currently not included in the existing established view! "
                    + "This is normal at startup. At other times is pseudo-network-partitioning is an indicator for repository/network-delays or clocks-out-of-sync (SLING-3432). "
                    + "(increasing the heartbeatTimeout can help as a workaround too) "
                    + "The local instance will stay in TOPOLOGY_CHANGING or pre _INIT mode until a new vote was successful.");
            throw new UndefinedClusterViewException(Reason.ISOLATED_FROM_TOPOLOGY,
                    "established view does not include local instance - isolated");
        }
        if (lowestSeqNum == -1) {
            // this starts partialStartup suppression (if all other conditions met)
            lowestSeqNum = seqNum;
        }
        // now remember those regularInstances in the seenLocalInstances map
        // but before we do that, lets do some paranoia checks (useful for tests to fail)
        for (InstanceInfo aSeenInstance : seenLocalInstances.values()) {
            InstanceInfo r = regularInstances.get(aSeenInstance.getClusterNodeId());
            if (r != null) {
                continue;
            }
            final int clusterNodeId = aSeenInstance.getClusterNodeId();
            if (!activeIdsList.contains(clusterNodeId)) {
                // ok, then this one is no longer active, perfect.
                continue;
            }
            logger.error("asClusterView : an instance is unexpectedly no longer part of the view : " + aSeenInstance);
        }
        this.seenLocalInstances = regularInstances;
        if (partiallyStartedClusterNodeIds.isEmpty()) {
            // success without suppressing -> reset the timeout
            partialStartupSuppressingTimeout = 0;
        } else {
            // success with suppressing -> set the timeout (if not already set)
            if (partialStartupSuppressingTimeout == 0) {
                final long suppressionTimeoutSeconds = config.getSuppressionTimeoutSeconds();
                if (suppressionTimeoutSeconds <= 0) {
                    partialStartupSuppressingTimeout = 0;
                } else {
                    partialStartupSuppressingTimeout = System.currentTimeMillis()
                            + (suppressionTimeoutSeconds * 1000);
                }
            }
        }
        return cluster;
    }

    private List<Integer> leaderElectionSort(Map<Integer, InstanceInfo> resultingInstances) {
        final Map<Integer, String> leaderElectionIds = new HashMap<>();
        for (InstanceInfo i : resultingInstances.values()) {
            leaderElectionIds.put(i.getClusterNodeId(), i.getLeaderElectionId());
        }
        List<Integer> sortedIds = new LinkedList<>(resultingInstances.keySet());
        leaderElectionSort(sortedIds, leaderElectionIds);
        return sortedIds;
    }

    private void leaderElectionSort(List<Integer> activeIdsList, final Map<Integer, String> leaderElectionIds) {
        final Comparator<Integer> comparator;
        if (config.isInvertLeaderElectionPrefixOrder()) {
            // SLING-7830 : inverted leaderElectionPrefix sorting
            comparator = new Comparator<Integer>() {
                
                private long prefixOf(String leaderElectionId) {
                    final int underScore = leaderElectionId.indexOf("_");
                    if (underScore == -1) {
                        return -1;
                    }
                    final String prefixStr = leaderElectionId.substring(0, underScore);
                    try{
                        return Long.parseLong(prefixStr);
                    } catch(Exception e) {
                        return -1;
                    }
                }

                @Override
                public int compare(Integer arg0, Integer arg1) {
                    // 'inverted sorting' means that the prefix is ordered descending
                    // while the remainder is ordered ascending
                    final String leaderElectionId0 = leaderElectionIds.get(arg0);
                    final String leaderElectionId1 = leaderElectionIds.get(arg1);
                    // so first step is to order the part before '_', eg the '1' in
                    // 1_0000001534409616936_374019fc-68bd-4c8d-a4cf-8ee8b07c63bc
                    final long prefix0 = prefixOf(leaderElectionId0);
                    final long prefix1 = prefixOf(leaderElectionId1);
                    // if a prefix is -1 (due to eg wrong formatting) it automatically
                    // ends up at the end
                    if (prefix0 == prefix1) {
                        // if they are the same, order the classic way
                        // note that when they are both '-1' that can be one of the following
                        // -1_0000001534409616936_374019fc-68bd-4c8d-a4cf-8ee8b07c63bc
                        // _0000001534409616936_374019fc-68bd-4c8d-a4cf-8ee8b07c63bc
                        // notALong_0000001534409616936_374019fc-68bd-4c8d-a4cf-8ee8b07c63bc
                        // so all of the above three get compared the classic way
                        return leaderElectionId0
                                .compareTo(leaderElectionId1);
                    } else {
                        // inverted order comparison:
                        return Long.valueOf(prefix1).compareTo(prefix0);
                    }
                }

            };
        } else {
            comparator = new Comparator<Integer>() {
    
                @Override
                public int compare(Integer arg0, Integer arg1) {
                    return leaderElectionIds.get(arg0)
                            .compareTo(leaderElectionIds.get(arg1));
                }
            };
        }
        Collections.sort(activeIdsList, comparator);
    }

    /**
     * oak's discovery-lite can opt to not provide a clusterViewId eg in the
     * single-VM case. (for clusters discovery-lite normally defines the
     * clusterViewId, as it is the one responsible for defining the membership
     * too) Thus if we're not getting an id here we have to define one here. (we
     * can typically assume that this corresponds to a singleVM case, but that's
     * not a 100% requirement). This id must be stored to ensure the contract
     * that the clusterId is stable across restarts. For that, the id is stored
     * under /var/discovery/oak (and to account for odd/edgy cases we'll do a
     * retry when storing the id, in case we'd run into conflicts, even though
     * they should not occur in singleVM cases)
     * 
     * @param resourceResolver the ResourceResolver with which to read or write
     * the clusterId properties under /var/discovery/oak
     * @return the clusterId to be used - either the one read or defined
     * at /var/discovery/oak - or the slingId in case of non-fixable exceptions
     * @throws PersistenceException when /var/discovery/oak could not be
     * accessed or auto-created
     */
    private String readOrDefineClusterId(ResourceResolver resourceResolver) throws PersistenceException {
        //TODO: if Config gets a specific, public getDiscoveryResourcePath, this can be simplified:
        final String clusterInstancesPath = config.getClusterInstancesPath();
        final String discoveryResourcePath = clusterInstancesPath.substring(0, 
                clusterInstancesPath.lastIndexOf("/", clusterInstancesPath.length()-2));
        final int MAX_RETRIES = 5;
        for(int retryCnt=0; retryCnt<MAX_RETRIES; retryCnt++) {
            Resource varDiscoveryOak = resourceResolver.getResource(discoveryResourcePath);
            if (varDiscoveryOak == null) {
                varDiscoveryOak = ResourceHelper.getOrCreateResource(resourceResolver, discoveryResourcePath);
            }
            if (varDiscoveryOak == null) {
                logger.error("readOrDefinedClusterId: Could not create: "+discoveryResourcePath);
                throw new RuntimeException("could not create " + discoveryResourcePath);
            }
            ModifiableValueMap props = varDiscoveryOak.adaptTo(ModifiableValueMap.class);
            if (props == null) {
                logger.error("readOrDefineClusterId: Could not adaptTo ModifiableValueMap: "+varDiscoveryOak);
                throw new RuntimeException("could not adaptTo ModifiableValueMap: " + varDiscoveryOak);
            }
            Object clusterIdObj = props.get(PROPERTY_CLUSTER_ID);
            String clusterId = (clusterIdObj == null) ? null : String.valueOf(clusterIdObj);
            if (clusterId != null && clusterId.length() > 0) {
                logger.trace("readOrDefineClusterId: read clusterId from repo as {}", clusterId);
                return clusterId;
            }

            // must now define a new clusterId and store it under /var/discovery/oak
            final String newClusterId = UUID.randomUUID().toString();
            props.put(PROPERTY_CLUSTER_ID, newClusterId);
            props.put(PROPERTY_CLUSTER_ID_DEFINED_BY, getSlingId());
            props.put(PROPERTY_CLUSTER_ID_DEFINED_AT, Calendar.getInstance());
            try {
                logger.info("readOrDefineClusterId: storing new clusterId as " + newClusterId);
                resourceResolver.commit();
                return newClusterId;
            } catch (PersistenceException e) {
                logger.warn("readOrDefineClusterId: could not persist clusterId "
                        + "(retrying in 1 sec max " + (MAX_RETRIES - retryCnt - 1) + " more times: " + e, e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e1) {
                    logger.warn("readOrDefineClusterId: got interrupted: "+e1, e1);
                }
                logger.info("readOrDefineClusterId: retrying now.");
            }
        }
        throw new RuntimeException("failed to write new clusterId (see log file earlier for more details)");
    }

    private Map<String, String> readProperties(String slingId, ResourceResolver resourceResolver) {
        Resource res = resourceResolver.getResource(config.getClusterInstancesPath() + "/" + slingId);
        final Map<String, String> props = new HashMap<>();
        if (res != null) {
            final Resource propertiesChild = res.getChild("properties");
            if (propertiesChild != null) {
                final ValueMap properties = propertiesChild.adaptTo(ValueMap.class);
                if (properties != null) {
                    for (Iterator<String> it = properties.keySet().iterator(); it
                            .hasNext();) {
                        String key = it.next();
                        if (!key.equals("jcr:primaryType")) {
                            props.put(key, properties.get(key, String.class));
                        }
                    }
                }
            }
        }
        return props;
    }

}
