/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.discovery.ec2;

import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Filter;

import software.amazon.awssdk.services.ec2.model.GroupIdentifier;
import software.amazon.awssdk.services.ec2.model.Tag;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.SingleObjectCache;
import org.opensearch.discovery.SeedHostsProvider;
import org.opensearch.transport.TransportService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.disjoint;
import static org.opensearch.discovery.ec2.AwsEc2Service.HostType.PRIVATE_DNS;
import static org.opensearch.discovery.ec2.AwsEc2Service.HostType.PRIVATE_IP;
import static org.opensearch.discovery.ec2.AwsEc2Service.HostType.PUBLIC_DNS;
import static org.opensearch.discovery.ec2.AwsEc2Service.HostType.PUBLIC_IP;
import static org.opensearch.discovery.ec2.AwsEc2Service.HostType.TAG_PREFIX;

class AwsEc2SeedHostsProvider implements SeedHostsProvider {

    private static final Logger logger = LogManager.getLogger(AwsEc2SeedHostsProvider.class);

    private final TransportService transportService;

    private final AwsEc2Service awsEc2Service;

    private final boolean bindAnyGroup;

    private final Set<String> groups;

    private final Map<String, List<String>> tags;

    private final Set<String> availabilityZones;

    private final String hostType;

    private final TransportAddressesCache dynamicHosts;

    AwsEc2SeedHostsProvider(Settings settings, TransportService transportService, AwsEc2Service awsEc2Service) {
        this.transportService = transportService;
        this.awsEc2Service = awsEc2Service;

        this.hostType = AwsEc2Service.HOST_TYPE_SETTING.get(settings);
        this.dynamicHosts = new TransportAddressesCache(AwsEc2Service.NODE_CACHE_TIME_SETTING.get(settings));

        this.bindAnyGroup = AwsEc2Service.ANY_GROUP_SETTING.get(settings);
        this.groups = new HashSet<>();
        this.groups.addAll(AwsEc2Service.GROUPS_SETTING.get(settings));

        this.tags = AwsEc2Service.TAG_SETTING.getAsMap(settings);

        this.availabilityZones = new HashSet<>();
        availabilityZones.addAll(AwsEc2Service.AVAILABILITY_ZONES_SETTING.get(settings));

        if (logger.isDebugEnabled()) {
            logger.debug(
                "using host_type [{}], tags [{}], groups [{}] with any_group [{}], availability_zones [{}]",
                hostType,
                tags,
                groups,
                bindAnyGroup,
                availabilityZones
            );
        }
    }

    @Override
    public List<TransportAddress> getSeedAddresses(HostsResolver hostsResolver) {
        return dynamicHosts.getOrRefresh();
    }

    protected List<TransportAddress> fetchDynamicNodes() {
        logger.info("fetchDynamicNodes");

        final List<TransportAddress> dynamicHosts = new ArrayList<>();

        final DescribeInstancesResponse descInstances;
        try (AmazonEc2ClientReference clientReference = awsEc2Service.client()) {
            // Query EC2 API based on AZ, instance state, and tag.

            // NOTE: we don't filter by security group during the describe instances request for two reasons:
            // 1. differences in VPCs require different parameters during query (ID vs Name)
            // 2. We want to use two different strategies: (all security groups vs. any security groups)
            DescribeInstancesRequest instancesRequest = buildDescribeInstancesRequest();
            descInstances = SocketAccess.doPrivileged(() -> clientReference.get().describeInstances(instancesRequest));
        } catch (final RuntimeException e) {
            logger.warn("Exception while retrieving instance list from AWS API: {}", e.getMessage());
            logger.warn("Full exception:", e);
            return dynamicHosts;
        }

        logger.info("finding seed nodes...");
        for (final Reservation reservation : descInstances.reservations()) {
            for (final Instance instance : reservation.instances()) {
                // lets see if we can filter based on groups
                if (!groups.isEmpty()) {
                    final List<GroupIdentifier> instanceSecurityGroups = instance.securityGroups();
                    final List<String> securityGroupNames = new ArrayList<>(instanceSecurityGroups.size());
                    final List<String> securityGroupIds = new ArrayList<>(instanceSecurityGroups.size());
                    for (final GroupIdentifier sg : instanceSecurityGroups) {
                        securityGroupNames.add(sg.groupName());
                        securityGroupIds.add(sg.groupId());
                    }
                    if (bindAnyGroup) {
                        // We check if we can find at least one group name or one group id in groups.
                        if (disjoint(securityGroupNames, groups) && disjoint(securityGroupIds, groups)) {
                            logger.trace(
                                "filtering out instance {} based on groups {}, not part of {}",
                                instance.instanceId(),
                                instanceSecurityGroups,
                                groups
                            );
                            // continue to the next instance
                            continue;
                        }
                    } else {
                        // We need tp match all group names or group ids, otherwise we ignore this instance
                        if (!(securityGroupNames.containsAll(groups) || securityGroupIds.containsAll(groups))) {
                            logger.trace(
                                "filtering out instance {} based on groups {}, does not include all of {}",
                                instance.instanceId(),
                                instanceSecurityGroups,
                                groups
                            );
                            // continue to the next instance
                            continue;
                        }
                    }
                }

                String address = null;
                if (hostType.equals(PRIVATE_DNS)) {
                    address = instance.privateDnsName();
                } else if (hostType.equals(PRIVATE_IP)) {
                    address = instance.privateIpAddress();
                } else if (hostType.equals(PUBLIC_DNS)) {
                    address = instance.publicDnsName();
                } else if (hostType.equals(PUBLIC_IP)) {
                    address = instance.publicIpAddress();
                } else if (hostType.startsWith(TAG_PREFIX)) {
                    // Reading the node host from its metadata
                    final String tagName = hostType.substring(TAG_PREFIX.length());
                    logger.debug("reading hostname from [{}] instance tag", tagName);
                    final List<Tag> tags = instance.tags();
                    for (final Tag tag : tags) {
                        if (tag.key().equals(tagName)) {
                            address = tag.value();
                            logger.debug("using [{}] as the instance address", address);
                        }
                    }
                } else {
                    throw new IllegalArgumentException(hostType + " is unknown for discovery.ec2.host_type");
                }
                if (address != null) {
                    try {
                        final TransportAddress[] addresses = transportService.addressesFromString(address);
                        for (int i = 0; i < addresses.length; i++) {
                            logger.trace("adding {}, address {}, transport_address {}", instance.instanceId(), address, addresses[i]);
                            dynamicHosts.add(addresses[i]);
                        }
                    } catch (final Exception e) {
                        final String finalAddress = address;
                        logger.warn(
                            (Supplier<?>) () -> new ParameterizedMessage(
                                "failed to add {}, address {}",
                                instance.instanceId(),
                                finalAddress
                            ),
                            e
                        );
                    }
                } else {
                    logger.trace("not adding {}, address is null, host_type {}", instance.instanceId(), hostType);
                }
            }
        }

        logger.debug("using dynamic transport addresses {}", dynamicHosts);

        return dynamicHosts;
    }

    private DescribeInstancesRequest buildDescribeInstancesRequest() {
        ArrayList<Filter> filters = new ArrayList<Filter>(
            List.of(Filter.builder().name("instance-state-name").values("running", "pending").build())
        );

        for (final Map.Entry<String, List<String>> tagFilter : tags.entrySet()) {
            // for a given tag key, OR relationship for multiple different values
            filters.add(Filter.builder().name("tag:" + tagFilter.getKey()).values(tagFilter.getValue()).build());
        }

        if (!availabilityZones.isEmpty()) {
            // OR relationship amongst multiple values of the availability-zone filter
            filters.add(Filter.builder().name("availability-zone").values(availabilityZones).build());
        }

        return DescribeInstancesRequest.builder().filters(filters).build();
    }

    private final class TransportAddressesCache extends SingleObjectCache<List<TransportAddress>> {

        protected TransportAddressesCache(TimeValue refreshInterval) {
            super(refreshInterval, new ArrayList<>());
        }

        @Override
        protected List<TransportAddress> refresh() {
            return fetchDynamicNodes();
        }
    }
}
