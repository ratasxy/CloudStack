/**
 * Copyright (C) 2011 Citrix Systems, Inc.  All rights reserved
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package com.cloud.user;

import java.util.List;

import com.cloud.api.commands.UpdateResourceCountCmd;
import com.cloud.configuration.Resource.ResourceOwnerType;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.configuration.ResourceCount;
import com.cloud.configuration.ResourceLimit;
import com.cloud.domain.Domain;

public interface ResourceLimitService {
    
    /**
     * Updates an existing resource limit with the specified details. If a limit doesn't exist, will create one.
     * 
     * @param ownerId
     *            the command that wraps the domainId, accountId, type, and max parameters
     * @param ownerType TODO
     * @param resourceType TODO
     * @param max TODO
     * @return the updated/created resource limit
     */
    ResourceLimit updateResourceLimit(Long ownerId, ResourceOwnerType ownerType, Integer resourceType, Long max);

    /**
     * Updates an existing resource count details for the account/domain
     * 
     * @param cmd
     *            the command that wraps the domainId, accountId, resource type  parameters
     * @return the updated/created resource counts
     */
    List<? extends ResourceCount> recalculateResourceCount(UpdateResourceCountCmd cmd);
    
    /**
     * Search for resource limits for the given id and/or account and/or type and/or domain.
     * @param id TODO
     * @param accountName TODO
     * @param domainId TODO
     * @param type TODO
     * @return a list of limits that match the criteria
     */
    public List<? extends ResourceLimit> searchForLimits(Long id, String accountName, Long domainId, Integer type, Long startIndex, Long pageSizeVal);
    
    /**
     * Finds the resource limit for a specified account and type. If the account has an infinite limit, will check
     * the account's parent domain, and if that limit is also infinite, will return the ROOT domain's limit.
     * @param accountId
     * @param type
     * @return resource limit
     */
    public long findCorrectResourceLimitForAccount(long accountId, ResourceType type);

    /**
     * Finds the resource limit for a specified domain and type. If the domain has an infinite limit, will check
     * up the domain hierarchy
     * @param account
     * @param type
     * @return resource limit
     */
    public long findCorrectResourceLimitForDomain(Domain domain, ResourceType type);

    /**
     * Increments the resource count
     * @param accountId
     * @param type
     * @param delta
     */
    public void incrementResourceCount(long accountId, ResourceType type, Long...delta);
    
    /**
     * Decrements the resource count
     * @param accountId
     * @param type
     * @param delta
     */
    public void decrementResourceCount(long accountId, ResourceType type, Long...delta);
    
    /**
     * Checks if a limit has been exceeded for an account
     * @param account
     * @param type
     * @param count the number of resources being allocated, count will be added to current allocation and compared against maximum allowed allocation
     * @return true if the limit has been exceeded
     */
    public boolean resourceLimitExceeded(Account account, ResourceCount.ResourceType type, long...count);
    
    /**
     * Gets the count of resources for a resource type and account
     * @param account
     * @param type
     * @return count of resources
     */
    public long getResourceCount(Account account, ResourceType type);

    boolean resourceLimitExceededForDomain(Domain domain, ResourceType type, long... count);

}