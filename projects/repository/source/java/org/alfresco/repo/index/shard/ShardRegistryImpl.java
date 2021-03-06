/*
 * Copyright (C) 2005-2015 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.alfresco.repo.index.shard;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.alfresco.repo.cache.SimpleCache;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.cmr.attributes.AttributeService;
import org.alfresco.service.cmr.attributes.AttributeService.AttributeQueryCallback;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.util.GUID;
import org.alfresco.util.Pair;

import com.hazelcast.util.ConcurrentHashSet;

/**
 * @author Andy
 */
public class ShardRegistryImpl implements ShardRegistry
{
    /**
     * The best shard sould be at the top;
     * @author Andy
     *
     */
    public static class FlocComparator implements Comparator<Pair<Floc, HashMap<Shard, HashSet<ShardState>>>>
    {
        public FlocComparator()
        {
            
        }

        @Override
        public int compare(Pair<Floc, HashMap<Shard, HashSet<ShardState>>> left, Pair<Floc, HashMap<Shard, HashSet<ShardState>>> right)
        { 
            double leftTxCount = 0;
            for(HashSet<ShardState> states : left.getSecond().values())
            {
                long shardMaxTxCount = 0;
                for(ShardState state : states)
                {
                    shardMaxTxCount = Math.max(shardMaxTxCount, state.getLastIndexedTxId());
                }
                leftTxCount += ((double)shardMaxTxCount)/left.getFirst().getNumberOfShards();
            }
            
            double rightTxCount = 0;
            for(HashSet<ShardState> states : right.getSecond().values())
            {
                long shardMaxTxCount = 0;
                for(ShardState state : states)
                {
                    shardMaxTxCount = Math.max(shardMaxTxCount, state.getLastIndexedTxId());
                }
                rightTxCount += ((double)shardMaxTxCount)/right.getFirst().getNumberOfShards();
            }
            return (int)(rightTxCount - leftTxCount);
        }

    }

    private static String SHARD_STATE_KEY = ".SHARD_STATE";

    private AttributeService attributeService;

    private SimpleCache<ShardInstance, ShardState> shardStateCache;

    private SimpleCache<ShardInstance, String> shardToGuidCache;

    private ConcurrentHashSet<Floc> knownFlocks = new ConcurrentHashSet<Floc>();

    private Random random = new Random(123);
    
    private boolean purgeOnInit = false;
    
    TransactionService transactionService;

    private long shardInstanceTimeoutInSeconds = 300;

    private long maxAllowedReplicaTxCountDifference = 1000;

    public ShardRegistryImpl()
    {
    }
    
    public void init()
    {
        if(purgeOnInit && (transactionService != null))
        {
            transactionService.getRetryingTransactionHelper().doInTransaction(new RetryingTransactionCallback<Object>()
            {

                @Override
                public Object execute() throws Throwable
                {
                    purge();
                    return null;
                }
            }, false, true);
            
        }
    }

    public void setAttributeService(AttributeService attributeService)
    {
        this.attributeService = attributeService;
    }

    public void setShardStateCache(SimpleCache<ShardInstance, ShardState> shardStateCache)
    {
        this.shardStateCache = shardStateCache;
    }

    public void setShardToGuidCache(SimpleCache<ShardInstance, String> shardToGuidCache)
    {
        this.shardToGuidCache = shardToGuidCache;
    }
    
    public void setPurgeOnInit(boolean purgeOnInit)
    {
        this.purgeOnInit = purgeOnInit;
    }

    public void setShardInstanceTimeoutInSeconds(int shardInstanceTimeoutInSeconds)
    {
        this.shardInstanceTimeoutInSeconds = shardInstanceTimeoutInSeconds;
    }

    
    
    /**
     * @param maxAllowedReplicaTxCountDifference the maxAllowedReplicaTxCountDifference to set
     */
    public void setMaxAllowedReplicaTxCountDifference(long maxAllowedReplicaTxCountDifference)
    {
        this.maxAllowedReplicaTxCountDifference = maxAllowedReplicaTxCountDifference;
    }

    /**
     * @param transactionService the transactionService to set
     */
    public void setTransactionService(TransactionService transactionService)
    {
        this.transactionService = transactionService;
    }

    public void purge()
    {
        ShardStateCollector shardStates = getPersistedShardStates();
        for(String guid : shardStates.shardGuids.values())
        {
            DeleteCallBack dcb = new DeleteCallBack(attributeService, guid);
            transactionService.getRetryingTransactionHelper().doInTransaction(dcb, false, true);
        }
    }
    
    private static class DeleteCallBack implements  RetryingTransactionCallback<Object>
    {
        AttributeService attributeService;
        
        String guid;

        DeleteCallBack(AttributeService attributeService, String guid)
        {
            this.attributeService = attributeService;
            this.guid = guid;
        }

        /* (non-Javadoc)
         * @see org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback#execute()
         */
        @Override
        public Object execute() throws Throwable
        {
            attributeService.removeAttributes(SHARD_STATE_KEY,  guid);
            return null;
        }
        
    }
    
    /**
     * @param shardState
     */
    public void registerShardState(final ShardState shardState)
    {

        transactionService.getRetryingTransactionHelper().doInTransaction(new RetryingTransactionCallback<Object>()
                {

                    @Override
                    public Object execute() throws Throwable
                    {
                        String guid = getPersistedShardStatusGuid(shardState.getShardInstance());
                        attributeService.setAttribute(shardState, SHARD_STATE_KEY, guid);
                        shardStateCache.put(shardState.getShardInstance(), shardState);
                        knownFlocks.add(shardState.getShardInstance().getShard().getFloc());
                        return null;
                    }
                }, false, true);
        
        
    }

    public List<ShardInstance> getIndexSlice(SearchParameters searchParameters)
    {
        Set<Floc> flocs = findFlocsFromKnown(searchParameters);
        if (flocs.size() == 0)
        {
            updateKnownFlocs();
            flocs = findFlocsFromKnown(searchParameters);
        }
        return selectShardInstancesForBestFlock(flocs);

    }

    private List<ShardInstance> selectShardInstancesForBestFlock(Set<Floc> flocs)
    {
        ArrayList<Pair<Floc, HashMap<Shard, HashSet<ShardState>>>> indexes = new ArrayList<Pair<Floc, HashMap<Shard, HashSet<ShardState>>>>  ();
        
        for(Floc floc : flocs)
        {
            HashMap<Shard, HashSet<ShardState>> index = new  HashMap<Shard, HashSet<ShardState>>();
            getShardStatesFromCache(floc, index);
            if (index.size() < floc.getNumberOfShards())
            {
                updateShardStateCache(floc);
                getShardStatesFromCache(floc, index);
            }
            indexes.add(new Pair<Floc, HashMap<Shard, HashSet<ShardState>>>(floc,  index));
        }

        Collections.sort(indexes, new FlocComparator());
          
        Pair<Floc, HashMap<Shard, HashSet<ShardState>>> best = indexes.get(0);
        ArrayList<ShardInstance> slice = new ArrayList<ShardInstance>(best.getFirst().getNumberOfShards());
        for (Shard shard : best.getSecond().keySet())
        {
            // Only allow replicas within some fraction of the  max TxId
            ShardState[] allowedInstances = getAllowedInstances(best.getSecond().get(shard));
            int position = random.nextInt(allowedInstances.length);
            ShardInstance instance = allowedInstances[position].getShardInstance();
            slice.add(instance);
        }
        return slice;
    }


    /**
     * @param hashSet
     * @return
     */
    private ShardState[] getAllowedInstances(HashSet<ShardState> states)
    {
        HashSet<ShardState> allowed = new  HashSet<ShardState>();
        
        long maxTxId = 0;
        for(ShardState state :states)
        {
            maxTxId = Math.max(maxTxId, state.getLastIndexedTxId());
        }
        
        for(ShardState state :states)
        {
            if( (maxTxId - state.getLastIndexedTxId()) <= maxAllowedReplicaTxCountDifference)
            {
                allowed.add(state);
            }
        }
        
        
        return allowed.toArray(new ShardState[] {});
    }

    /**
     * @param floc
     */
    private void updateShardStateCache(Floc floc)
    {
        ShardStateCollector shardStates = getPersistedShardStates();
        HashMap<Shard, HashMap<ShardInstance, ShardState>> shards = shardStates.getIndexes().get(floc);
        if(shards != null)
        {
            for (HashMap<ShardInstance, ShardState> map : shards.values())
            {
                for (ShardInstance instance : map.keySet())
                {
                    shardStateCache.put(instance, map.get(instance));
                }
            }
        }
    }

    /**
     * @param floc
     * @param index
     */
    private void getShardStatesFromCache(Floc floc, HashMap<Shard, HashSet<ShardState>> index)
    {
        long now = System.currentTimeMillis();
        for (ShardInstance instance : shardStateCache.getKeys())
        {
            ShardState state = shardStateCache.get(instance);
            if( (now - state.getLastUpdated()) > (shardInstanceTimeoutInSeconds * 1000) )
            {
                continue;
            }
            
            if (instance.getShard().getFloc().equals(floc))
            {
                HashSet<ShardState> replicas = index.get(instance.getShard());
                if (replicas == null)
                {
                    replicas = new HashSet<ShardState>();
                    index.put(instance.getShard(), replicas);
                }
                replicas.add(state);
            }
        }
    }

    private void updateKnownFlocs()
    {
        ShardStateCollector shardStates = getPersistedShardStates();
        knownFlocks.addAll(shardStates.getIndexes().keySet());
    }

    private HashSet<Floc> findFlocsFromKnown(SearchParameters searchParameters)
    {
        HashSet<Floc> flocs = new HashSet<Floc>();
        for (Floc floc : knownFlocks)
        {
            if (floc.getStoreRefs().containsAll(searchParameters.getStores()))
            {
                flocs.add(floc);
            }
        }
        return flocs;
    }

    private Floc getBestFloc(Floc best, Floc floc)
    {
        if (best == null)
        {
            return floc;
        }
        
        
        if (best.getNumberOfShards() >= floc.getNumberOfShards())
        {
            return best;
        }
        else
        {
            return floc;
        }
    }

    private String getPersistedShardStatusGuid(ShardInstance shardInstance)
    {
        String guid = shardToGuidCache.get(shardInstance);
        if (guid == null)
        {
            ShardStateCollector shardStates = getPersistedShardStates();
            for (ShardInstance instance : shardStates.getShardGuids().keySet())
            {
                if (!shardToGuidCache.contains(instance))
                {
                    shardToGuidCache.put(instance, shardStates.getShardGuids().get(instance));
                }
            }
            guid = shardToGuidCache.get(shardInstance);
        }
        if(guid == null)
        {
            guid =  GUID.generate();
            shardToGuidCache.put(shardInstance, guid);
        }
        return guid;
    }

    private ShardStateCollector getPersistedShardStates()
    {
        ShardStateCollector shardStateCollector = new ShardStateCollector();
        attributeService.getAttributes(shardStateCollector, SHARD_STATE_KEY);
        knownFlocks.addAll(shardStateCollector.getIndexes().keySet());
        return shardStateCollector;
    }

    protected static class ShardStateCollector implements AttributeQueryCallback
    {
        HashMap<ShardInstance, String> shardGuids = new HashMap<ShardInstance, String>();

        HashMap<Floc, HashMap<Shard, HashMap<ShardInstance, ShardState>>> indexes = new HashMap<Floc, HashMap<Shard, HashMap<ShardInstance, ShardState>>>();

        public ShardStateCollector()
        {
        }

        @Override
        public boolean handleAttribute(Long id, Serializable value, Serializable[] keys)
        {
            if(value == null)
            {
                return true;
            }
            
            
            String shardInstanceGuid = (String) keys[1];

            ShardState shardState = (ShardState) value;

            shardGuids.put(shardState.getShardInstance(), shardInstanceGuid);

            HashMap<Shard, HashMap<ShardInstance, ShardState>> shards = indexes.get(shardState.getShardInstance().getShard().getFloc());
            if (shards == null)
            {
                shards = new HashMap<Shard, HashMap<ShardInstance, ShardState>>();
                indexes.put(shardState.getShardInstance().getShard().getFloc(), shards);
            }
            HashMap<ShardInstance, ShardState> shardInstances = shards.get(shardState.getShardInstance().getShard());
            if (shardInstances == null)
            {
                shardInstances = new HashMap<ShardInstance, ShardState>();
                shards.put(shardState.getShardInstance().getShard(), shardInstances);
            }
            ShardState currentState = shardInstances.get(shardState.getShardInstance());

            shardInstances.put(shardState.getShardInstance(), shardState);

            return true;
        }

        /**
         * @return the shardGuids
         */
        public HashMap<ShardInstance, String> getShardGuids()
        {
            return shardGuids;
        }

        /**
         * @return the indexes
         */
        public HashMap<Floc, HashMap<Shard, HashMap<ShardInstance, ShardState>>> getIndexes()
        {
            return indexes;
        }

    }
}
