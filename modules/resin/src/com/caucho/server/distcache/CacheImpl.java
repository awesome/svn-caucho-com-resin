/*
 * Copyright (c) 1998-2012 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.server.distcache;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import javax.cache.Cache;
import javax.cache.CacheConfiguration;
import javax.cache.CacheException;
import javax.cache.CacheLoader;
import javax.cache.CacheManager;
import javax.cache.CacheStatistics;
import javax.cache.Status;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.CacheEntryListenerException;
import javax.cache.event.CacheEntryReadListener;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;
import javax.cache.event.Filter;
import javax.cache.mbeans.CacheMXBean;

import com.caucho.config.ConfigException;
import com.caucho.distcache.ByteStreamCache;
import com.caucho.distcache.ExtCacheEntry;
import com.caucho.distcache.ObjectCache;
import com.caucho.env.distcache.CacheDataBacking;
import com.caucho.env.thread.AbstractWorkerQueue;
import com.caucho.loader.Environment;
import com.caucho.management.server.AbstractManagedObject;
import com.caucho.server.distcache.CacheStoreManager.DataItem;
import com.caucho.util.Alarm;
import com.caucho.util.ConcurrentArrayList;
import com.caucho.util.CurrentTime;
import com.caucho.util.HashKey;
import com.caucho.util.L10N;
import com.caucho.util.LruCache;
import com.caucho.vfs.StreamSource;
import com.caucho.vfs.WriteStream;

/**
 * Implements the distributed cache
 */

public class CacheImpl<K,V>
  implements ObjectCache<K,V>, ByteStreamCache, Closeable
{
  private static final L10N L = new L10N(CacheImpl.class);

  private CacheManagerImpl _localManager;
  private CacheStoreManager _manager;

  private final String _name;
  private final String _managerName;
  private final String _guid;

  private final CacheConfig _config;

  private ConcurrentArrayList<ReadListener<K,V>> _readListeners;
  private ConcurrentArrayList<UpdatedListener<K,V>> _updatedListeners;
  private ConcurrentArrayList<CacheEntryExpiredListener> _expiredListeners;
  private ConcurrentArrayList<RemovedListener<K,V>> _removedListeners;
  
  private LoadQueueWorker<K,V> _loadQueue;

  // private LruCache<Object,DistCacheEntry> _entryCache;
  
  private final AtomicLong _getCount = new AtomicLong();
  private final AtomicLong _hitCount = new AtomicLong();
  private final AtomicLong _missCount = new AtomicLong();
  private final AtomicLong _putCount = new AtomicLong();
  private final AtomicLong _removeCount = new AtomicLong();
  
  private CacheAdmin _admin = new CacheAdmin();

  private boolean _isInit;
  private boolean _isClosed;

  public CacheImpl(CacheManagerImpl localManager,
                   String name,
                   String managerName,
                   String guid,
                   CacheConfig config)
  {
    _localManager = localManager;
    _name = name;
    _managerName = managerName;
    _guid = guid;
    _config = config;
    
    init(true);
  }

  /**
   * Returns the name of the cache.
   */
  public String getName()
  {
    return _name;
  }
  
  public String getManagerName()
  {
    return _managerName;
  }
  
  @Override
  public CacheManager getCacheManager()
  {
    throw new UnsupportedOperationException(getClass().getSimpleName());
  }
  
  public String getGuid()
  {
    return _guid;
  }

  /**
   * The maximum valid time for an item.  Items stored in the cache
   * for longer than the expire time are no longer valid and z null value
   * will be returned for a get.
   * <p/>
   * Default is infinite.
   */
  public long getExpireTimeout()
  {
    return _config.getModifiedExpireTimeout();
  }

  /**
   * The maximum idle time for an item, which is typically used for
   * temporary data like sessions.  For example, session
   * data might be removed if idle over 30 minutes.
   * <p/>
   * Cached data would have infinite idle time because
   * it doesn't depend on how often it's accessed.
   * <p/>
   * Default is infinite.
   */
  public long getIdleTimeout()
  {
    return _config.getAccessedExpireTimeout();
  }

  /**
   * Returns the idle check window, used to minimize traffic when
   * updating access times.
   */
  public long getIdleTimeoutWindow()
  {
    return _config.getAccessedExpireTimeoutWindow();
  }

  /**
   * The lease timeout is the time a server can use the local version
   * if it owns it, before a timeout.
   */
  public long getLeaseTimeout()
  {
    return _config.getLeaseExpireTimeout();
  }

  /**
   * The local read timeout is how long a local copy of
   * a cache item can be reused before checking with the master copy.
   * <p/>
   * A read-only item could be infinite (-1).  A slow changing item
   * like a list of bulletin-board comments could be 10s.  Even a relatively
   * quickly changing item can be 10ms or 100ms.
   * <p/>
   * The default is 10ms
   */
  public long getLocalReadTimeout()
  {
    return _config.getLocalExpireTimeout();
  }
  
  public CacheImpl createIfAbsent()
  {
    init(true);
    
    return _localManager.getCache(_guid);
  }

  private void init(boolean ifAbsent)
  {
    synchronized (this) {
      if (_isInit)
        return;

      _isInit = true;

      _config.init();

      initServer();
      
      if (_config.getEngine() == null)
        _config.setEngine(_manager.getCacheEngine());

      _config.setCacheKey(_manager.createSelfHashKey(_config.getGuid(),
                                                     _config.getKeySerializer()));

      // _entryCache = new LruCache<Object,DistCacheEntry>(512);
      
      Environment.addCloseListener(this);
    }
    
    _manager.initCache(this);
    
    _admin.register();
  }

  /**
   * Returns the object with the given key without checking the backing store.
   */
  public Object peek(Object key)
  {
    DistCacheEntry cacheEntry = getDistCacheEntry(key);

    return (cacheEntry != null) ? cacheEntry.peek() : null;
  }
  
  /**
   * Returns the hash of the given key
   */
  public HashKey getKeyHash(Object key)
  {
    return getDistCacheEntry(key).getKeyHash();
  }

  /**
   * Returns the object with the given key, checking the backing
   * store if necessary.
   */
  @Override
  public V get(Object key)
  {
    DistCacheEntry entry = getDistCacheEntry(key);
    
    _getCount.incrementAndGet();
    if (! entry.isValueNull())
      _hitCount.incrementAndGet();
    else
      _missCount.incrementAndGet();
    
    V value = (V) entry.get(_config);
    
    entryRead(key, value);
    
    return value;
  }

  /**
   * Returns the object with the given key always checking the backing
   * store .
   */
  @Override
  public Object getExact(Object key)
  {
    DistCacheEntry entry = getDistCacheEntry(key);
    
    Object value = entry.getExact(_config);
    
    return value;
  }

  /**
   * Fills an output stream with the value for a key.
   */
  @Override
  public boolean get(Object key, OutputStream os)
    throws IOException
  {
    return getDistCacheEntry(key).getStream(os, _config);
  }

  /**
   * Returns the cache entry for the object with the given key.
   */
  @Override
  public ExtCacheEntry getExtCacheEntry(Object key)
  {
    return getDistCacheEntry(key).getMnodeValue(_config);
  }
  
  public ExtCacheEntry getExtCacheEntry(HashKey key)
  {
    return getDistCacheEntry(key).getMnodeValue(_config);
  }

  /**
   * Returns the cache entry for the object with the given key.
   */
  @Override
  public ExtCacheEntry peekExtCacheEntry(Object key)
  {
    return getDistCacheEntry(key).getMnodeEntry();
  }
  
  public ExtCacheEntry getStatCacheEntry(Object key)
  {
    return getDistCacheEntry(key);
  }

  /**
   * Returns the cache entry for the object with the given key.
   */
  public Cache.Entry getCacheEntry(Object key)
  {
    return getExtCacheEntry(key);
  }

  /**
   * Puts a new item in the cache.
   *
   * @param key   the key of the item to put
   * @param value the value of the item to put
   */
  @Override
  public void put(K key, V value)
  {
    getDistCacheEntry(key).put(value, _config);

    _putCount.incrementAndGet();
    entryUpdate(key, value);
  }

  /**
   * Puts a new item in the cache with a custom idle
   * timeout (used for sessions).
   *
   * @param key         the key of the item to put
   * @param is          the value of the item to put
   * @param idleTimeout the idle timeout for the item
   * @param flags       the flags value (for memcache)
   */
  @Override
  public ExtCacheEntry put(Object key,
                           InputStream is,
                           long accessedExpireTimeout,
                           long modifiedExpireTimeout,
                           int flags)
    throws IOException
  {
      return getDistCacheEntry(key).put(is, _config, 
                                        accessedExpireTimeout,
                                        modifiedExpireTimeout,
                                        flags);
  }

  /**
   * Puts a new item in the cache with a custom idle
   * timeout (used for sessions).
   *
   * @param key         the key of the item to put
   * @param is          the value of the item to put
   * @param idleTimeout the idle timeout for the item
   */
  @Override
  public ExtCacheEntry put(Object key,
                           InputStream is,
                           long accessedExpireTimeout,
                           long modifiedExpireTimeout)
    throws IOException
  {
    return getDistCacheEntry(key).put(is, _config, 
                                      accessedExpireTimeout,
                                      modifiedExpireTimeout);
  }

  /**
   * Puts a new item in the cache.
   *
   * @param key   the key of the item to put
   * @param value the value of the item to put
   */
  @Override
  public Object getAndPut(Object key, Object value)
  {
    return getDistCacheEntry(key).getAndPut(value, _config);
    
    // notifyPut(key);
  }

  /**
   * Updates the cache if the old version matches the current version.
   * A zero value for the old value hash only adds the entry if it's new.
   *
   * @param key     the key to compare
   * @param version the version of the old value, returned by getEntry
   * @param value   the new value
   * @return true if the update succeeds, false if it fails
   */
  @Override
  public boolean compareAndPut(Object key,
                               long version,
                               Object value)
  {
    put((K) key, (V) value);

    return true;
  }

  /**
   * Updates the cache if the old version matches the current value.
   * A zero value for the old version only adds the entry if it's new.
   *
   * @param key         the key to compare
   * @param version     the hash of the old version, returned by getEntry
   * @param inputStream the new value
   * @return true if the update succeeds, false if it fails
   */
  @Override
  public boolean compareAndPut(Object key,
                               long version,
                               InputStream inputStream)
    throws IOException
  {
    // put(key, inputStream);

    return true;
  }

  public void compareAndPut(HashKey key, 
                            HashKey value,
                            long valueLength,
                            long version)
  {
    getDistCacheEntry(key).compareAndPut(version, value, valueLength, _config);
  }

  @Override
  public boolean putIfAbsent(Object key, Object value) throws CacheException
  {
    HashKey NULL = MnodeEntry.NULL_KEY;
    
    HashKey result
      = getDistCacheEntry(key).compareAndPut(NULL, value, _config);
    
    return result != null && result.isNull();
  }

  @Override
  public boolean replace(Object key, Object oldValue, Object value)
    throws CacheException
  {
    DistCacheEntry entry = getDistCacheEntry(key);
    
    HashKey oldHash = entry.getValueHash(oldValue, _config);
    
    HashKey result = entry.compareAndPut(oldHash, value, _config);
    
    boolean isChanged;
    
    if (oldHash == null || oldHash.isNull()) {
      isChanged = (result == null || result.isNull());
    }
    else {
      isChanged = oldHash.equals(result);
    }
    
    return isChanged;
  }

  @Override
  public boolean replace(Object key, Object value) throws CacheException
  {
    DistCacheEntry entry = getDistCacheEntry(key);
    
    HashKey oldHash = MnodeEntry.ANY_KEY;
    
    HashKey result = entry.compareAndPut(oldHash, value, _config);
    
    boolean isChanged = result != null && ! result.isNull();
    
    if (isChanged)
      entryUpdate((K) key, (V) value);
    
    return isChanged;
  }

  @Override
  public Object getAndReplace(Object key, Object value) throws CacheException
  {
    DistCacheEntry entry = getDistCacheEntry(key);
    
    HashKey oldHash = MnodeEntry.ANY_KEY;
    
    return entry.getAndReplace(oldHash, value, _config);
  }

  /**
   * Removes the entry from the cache.
   *
   * @return true if the object existed
   */
  @Override
  public boolean remove(Object key)
  {
    boolean isRemoved = getDistCacheEntry(key).remove(_config);
    
    if (isRemoved) {
      entryRemoved(key);
      _removeCount.incrementAndGet();
    }
    
    return isRemoved;
  }

  /**
   * Removes the entry from the cache.
   *
   * @return true if the object existed
   */
  @Override
  public boolean remove(Object key, Object oldValue)
  {
    getDistCacheEntry(key).remove(_config);
    
    return true;
  }

  @Override
  public Object getAndRemove(Object key) throws CacheException
  {
    return getDistCacheEntry(key).getAndRemove(_config);
  }

  /**
   * Removes the entry from the cache if the current entry matches the version.
   */
  @Override
  public boolean compareAndRemove(Object key, long version)
  {
    DistCacheEntry cacheEntry = getDistCacheEntry(key);

    if (cacheEntry.getVersion() == version) {
      remove(key);

      return true;
    }

    return false;
  }
  
  /**
   * Returns the entry for the given key, returning the live item.
   */
  public ExtCacheEntry getLiveCacheEntry(Object key)
  {
    return getDistCacheEntry(key);
  }

  /**
   * Returns the CacheKeyEntry for the given key.
   */
  protected DistCacheEntry getDistCacheEntry(Object key)
  {
    DistCacheEntry cacheEntry = null;
    
    // cacheEntry = _entryCache.get(key);

    if (cacheEntry == null) {
      cacheEntry = _manager.getCacheEntry(key, _config);

      // _entryCache.put(key, cacheEntry);
    }

    return cacheEntry;
  }

  /**
   * Returns the CacheKeyEntry for the given key.
   */
  protected DistCacheEntry getDistCacheEntry(HashKey key)
  {
    return _manager.getCacheEntry(key, _config);
  }

  /**
   * Returns a new map of the items found in the central cache.
   *
   * @note If a cacheLoader is configured if an item is not found in the cache.
   */
  @Override
  public Map getAll(Set keys)
  {
    Map result = new HashMap();

    for (Object key : keys) {
      Object value = get(key);

      if (value != null) {
        result.put(key, value);
      }
    }

    return result;
  }

  /**
   * Adds a listener to the cache.
   */
  @Override
  public boolean registerCacheEntryListener(CacheEntryListener<? super K,? super V> listener,
                                            Filter filter)
  {
    if (listener instanceof CacheEntryReadListener<?,?>) {
      synchronized (this) {
        if (_readListeners == null)
          _readListeners = new ConcurrentArrayList(ReadListener.class);
      }
      
      _readListeners.add(new ReadListener<K,V>(listener, filter));
    }
    
    if (listener instanceof CacheEntryUpdatedListener) {
      synchronized (this) {
        if (_updatedListeners == null)
          _updatedListeners = new ConcurrentArrayList(UpdatedListener.class);
      }
      
      _updatedListeners.add(new UpdatedListener<K,V>(listener, filter));
    }
    
    if (listener instanceof CacheEntryRemovedListener) {
      synchronized (this) {
        if (_removedListeners == null)
          _removedListeners = new ConcurrentArrayList(RemovedListener.class);
        
        _removedListeners.add(new RemovedListener<K,V>(listener, filter));
      }
    }
    
    if (listener instanceof CacheEntryExpiredListener) {
      synchronized (this) {
        if (_expiredListeners == null)
          _expiredListeners = new ConcurrentArrayList<CacheEntryExpiredListener>(CacheEntryExpiredListener.class);
      }
    }
    
    return true;
  }

  /**
   * Removes a listener from the cache.
   */
  @Override
  public boolean unregisterCacheEntryListener(CacheEntryListener listener)
  {
    boolean result = false;
    
    if (unregister(_readListeners, listener))
      result = true;
    
    if (unregister(_updatedListeners, listener))
      result = true;
    
    if (unregister(_removedListeners, listener))
      result = true;
    
    return result;
  }
  
  private boolean unregister(ConcurrentArrayList<? extends Listener> listeners,
                             CacheEntryListener listener)
  {
    if (listeners != null) {
      for (Listener<K,V> testListener : listeners) {
        if (testListener.isMatch(listener)) {
          listeners.remove(testListener);
          return true;
        }
      }
    }
    
    return false;
  }

  /**
   * Returns the CacheStatistics for this cache.
   */
  @Override
  public CacheStatistics getStatistics()
  {
    return getMBean();
  }

  /**
   * Puts each item in the map into the cache.
   */
  @Override
  public void putAll(Map map)
  {
    if (map == null || map.size() == 0)
      return;
    Set entries = map.entrySet();

    for (Object item : entries) {
      Map.Entry entry = (Map.Entry) item;
      put((K) entry.getKey(), (V) entry.getValue());
    }
  }

  /**
   * Returns true if an entry for the item is found in  the cache.
   *
   * @param key
   * @return
   */
  @Override
  public boolean containsKey(Object key)
  {
    DistCacheEntry entry = getDistCacheEntry(key);
    
    return entry != null && entry.isValid();
  }

  public boolean isBackup()
  {
    return _config.isBackup();
  }

  public boolean isTriplicate()
  {
    return _config.isTriplicate();
  }
  
  public HashKey getCacheKey()
  {
    return _config.getCacheKey();
  }

  /**
   * Places an item in the cache from the loader unless the item is in cache already.
   */
  protected Object cacheLoader(Object key)
  {
    Object value = get(key);

    if (value != null)
      return value;

    CacheLoader loader = _config.getCacheLoader();
    
    Object arg = null;

    value = (loader != null) ? loader.load(key) : null;

    if (value != null)
      put((K) key, (V) value);

    return value;
  }

  @Override
  public boolean isClosed()
  {
    return _isClosed;
  }

  @Override
  public void close()
  {
    _isClosed = true;
    
    _admin.unregister();
    
    _localManager.remove(_guid);
    
    _manager.closeCache(_guid);
    
  }
  
  public boolean loadData(HashKey valueHash, WriteStream os)
    throws IOException
  {
    return getDataBacking().loadData(valueHash, os);
  }

  public boolean saveData(HashKey valueHash, StreamSource source, int length)
    throws IOException
  {
    return getDataBacking().saveData(valueHash, source, length);
  }

  public boolean isDataAvailable(HashKey valueKey)
  {
    return getDataBacking().isDataAvailable(valueKey);
  }
  
  private CacheDataBacking getDataBacking()
  {
    return _manager.getDataBacking();
  }

  //
  // QA
  //
  
  public byte []getKeyHash(String name)
  {
    return getDistCacheEntry(name).getKeyHash().getHash();
  }
  
  public byte []getValueHash(Object value)
  {
    return _manager.calculateValueHash(value, _config);
  }
  
  @SuppressWarnings("unchecked")
  public MnodeStore getMnodeStore()
  {
    return ((CacheStoreManager) _manager).getMnodeStore();
  }
  
  @SuppressWarnings("unchecked")
  public DataStore getDataStore()
  {
    return ((CacheStoreManager) _manager).getDataStore();
  }
  
  public void saveData(Object value)
  {
    ((CacheStoreManager) _manager).writeData(null, value, 
                                             _config.getValueSerializer());
  }
  
  public HashKey saveData(InputStream is)
    throws IOException
  {
    DataItem dataItem = ((CacheStoreManager) _manager).writeData(null, is);
    
    return dataItem.getValue();
  }

  public void setManager(CacheStoreManager manager)
  {
    if (_manager != null)
      throw new IllegalStateException();
    
    _manager = manager;
  }
  
  private void initServer()
    throws ConfigException
  {
    if (_manager != null)
      return;
    
    DistCacheSystem cacheService = DistCacheSystem.getCurrent();

    if (cacheService == null)
      throw new ConfigException(L.l("'{0}' cannot be initialized because it is not in a Resin environment",
                                    getClass().getSimpleName()));

    _manager = cacheService.getDistCacheManager();

    if (_manager == null)
      throw new IllegalStateException("distributed cache manager not available");
  }

  @Override
  public CacheMXBean getMBean()
  {
    return _admin;
  }

  @Override
  public CacheConfiguration getConfiguration()
  {
    return _config;
  }

  @Override
  public Object invokeEntryProcessor(Object key, 
                                     EntryProcessor entryProcessor)
  {
    DistCacheEntry entry = getDistCacheEntry(key);
    
    if (entry == null || entry.isValueNull())
      return null;
    
    return entryProcessor.process(new MutableEntry(entry));
  }

  @Override
  public Future<V> load(K key)
    throws CacheException
  {
      LoadQueueWorker<K,V> loadQueue;
      
      synchronized (this) {
        loadQueue = _loadQueue;
        
        if (loadQueue == null) {
          loadQueue = new LoadQueueWorker<K,V>(this);
          _loadQueue = loadQueue;
        }
      }
      
      LoadFuture<K,V> loadFuture = new LoadFuture<K,V>(this, key);
      
      loadQueue.offer(loadFuture);
      loadQueue.wake();

      return loadFuture;
  }

  @Override
  public Future<Map<K,? extends V>> loadAll(Set<? extends K> keys)
    throws CacheException
  {
    LoadQueueWorker<K,V> loadQueue;
    
    synchronized (this) {
      loadQueue = _loadQueue;
      
      if (loadQueue == null) {
        loadQueue = new LoadQueueWorker<K,V>(this);
        _loadQueue = loadQueue;
      }
    }
    
    LoadFuture loadFuture = new LoadFuture(this, keys);
    
    loadQueue.offer(loadFuture);
    loadQueue.wake();

    return loadFuture;
  }

  @Override
  public void removeAll(Set keys)
  {
    for (Object key : keys) {
      remove(key);
    }
  }

  @Override
  public void removeAll() throws CacheException
  {
    for (Object entryObj : this) {
      Cache.Entry entry = (Cache.Entry) entryObj;
      
      remove(entry.getKey());
    }    
  }

  @Override
  public Iterator<Cache.Entry<K, V>> iterator()
  {
    return new EntryIterator<K,V>(_manager.getEntries(), getCacheKey());
  }

  @Override
  public Status getStatus()
  {
    return Status.STARTED;
  }
  
  private void entryRead(Object key, V value)
  {
    ConcurrentArrayList<ReadListener<K,V>> readListeners = _readListeners;
    
    if (readListeners == null || readListeners.size() == 0)
      return;
    
    CacheEntryEvent<K,V> event
      = new CacheEntryEventImpl<K,V>(this, (K) key, value);
    
    for (ReadListener<K,V> listener : readListeners) {
      listener.entryRead(event);
    }
  }
  
  private void entryUpdate(Object key, V value)
  {
    ConcurrentArrayList<UpdatedListener<K,V>> updatedListeners = _updatedListeners;
    
    if (updatedListeners == null || updatedListeners.size() == 0)
      return;
    
    CacheEntryEvent<K,V> event
      = new CacheEntryEventImpl<K,V>(this, (K) key, value);
    
    for (UpdatedListener<K,V> listener : updatedListeners) {
      listener.entryUpdated(event);
    }
  }
  
  private void entryRemoved(Object key)
  {
    ConcurrentArrayList<RemovedListener<K,V>> removedListeners = _removedListeners;
    
    if (removedListeners == null || removedListeners.size() == 0)
      return;
    
    CacheEntryEvent<K,V> event
      = new CacheEntryEventImpl<K,V>(this, (K) key, null);
    
    for (RemovedListener<K,V> listener : removedListeners) {
      listener.entryRemoved(event);
    }
  }

  @Override
  public void start() throws CacheException
  {
  }

  @Override
  public void stop() throws CacheException
  {
  }

  @Override
  public Object unwrap(Class cl)
  {
    return null;
  }

  @Override
  public String toString()
  {
    return getClass().getSimpleName() + "[" + _guid + "]";
  }
  
  static class EntryImpl<K,V> implements Cache.Entry<K,V>
  {
    private final K _key;
    private final V _value;
    
    EntryImpl(K key, V value)
    {
      _key = key;
      _value = value;
    }

    @Override
    public K getKey()
    {
      return _key;
    }

    @Override
    public V getValue()
    {
      return _value;
    }
  }
  
  class MutableEntry implements Cache.MutableEntry {
    private DistCacheEntry _entry;
    
    MutableEntry(DistCacheEntry entry)
    {
      _entry = entry;
    }

    @Override
    public boolean exists()
    {
      return ! _entry.isValueNull();
    }

    @Override
    public void remove()
    {
      _entry.remove(_config);
    }

    @Override
    public void setValue(Object value)
    {
      _entry.put(value, _config);
    }

    @Override
    public Object getKey()
    {
      return _entry.getKey();
    }

    @Override
    public Object getValue()
    {
      return _entry.getValue();
    }
  }
  
  static class EntryIterator<K,V> implements Iterator<Cache.Entry<K,V>> {
    private Iterator<DistCacheEntry> _storeIterator;
    private HashKey _cacheKey;
    
    private DistCacheEntry _next;
    
    EntryIterator(Iterator<DistCacheEntry> storeIterator,
                  HashKey cacheKey)
    {
      _storeIterator = storeIterator;
      _cacheKey = cacheKey;
      
      findNext();
    }

    @Override
    public boolean hasNext()
    {
      return _next != null;
    }

    @Override
    public Entry<K, V> next()
    {
      Entry entry = _next;
      
      _next = null;
      
      findNext();
      
      return entry;
    }

    @Override
    public void remove()
    {
    }
    
    private void findNext()
    {
      while (_storeIterator.hasNext()) {
        DistCacheEntry entry = _storeIterator.next();
        
        if (_cacheKey.equals(entry.getCacheHash())) {
          _next = entry;
          return;
        }
      }
    }
  }
  
  abstract static class Listener<K,V> {
    abstract boolean isMatch(CacheEntryListener<K,V> listener);
  }
  
  static class ReadListener<K,V> extends Listener<K,V>
    implements CacheEntryReadListener<K,V> {
    private CacheEntryReadListener<K,V> _listener;
    private Filter _filter;
    
    ReadListener(CacheEntryListener<? super K,? super V> listener, Filter filter)
    {
      _listener = (CacheEntryReadListener<K,V>) listener;
      _filter = filter;
    }
    
    @Override
    public boolean isMatch(CacheEntryListener<K,V> listener)
    {
      return _listener == listener;
    }

    @Override
    public void entryRead(CacheEntryEvent<? extends K,? extends V> event)
        throws CacheEntryListenerException
    {
      if (_filter.evaluate(event)) {
        _listener.entryRead(event);
      }
    }
  }
  
  static class UpdatedListener<K,V> extends Listener<K,V>
    implements CacheEntryUpdatedListener<K,V> {
    private CacheEntryUpdatedListener<K,V> _listener;
    private Filter _filter;
    
    UpdatedListener(CacheEntryListener<? super K,? super V> listener, Filter filter)
    {
      _listener = (CacheEntryUpdatedListener<K,V>) listener;
      _filter = filter;
    }
    
    @Override
    public boolean isMatch(CacheEntryListener<K,V> listener)
    {
      return _listener == listener;
    }

    @Override
    public void entryUpdated(CacheEntryEvent<? extends K,? extends V> event)
        throws CacheEntryListenerException
    {
      if (_filter.evaluate(event)) {
        _listener.entryUpdated(event);
      }
    }
  }
  
  static class RemovedListener<K,V> extends Listener<K,V>
    implements CacheEntryRemovedListener<K,V> {
    private CacheEntryRemovedListener<K,V> _listener;
    private Filter _filter;
    
    RemovedListener(CacheEntryListener<? super K,? super V> listener, Filter filter)
    {
      _listener = (CacheEntryRemovedListener<K,V>) listener;
      _filter = filter;
    }
    
    @Override
    public boolean isMatch(CacheEntryListener<K,V> listener)
    {
      return _listener == listener;
    }

    @Override
    public void entryRemoved(CacheEntryEvent<? extends K,? extends V> event)
        throws CacheEntryListenerException
    {
      if (_filter.evaluate(event)) {
        _listener.entryRemoved(event);
      }
    }
  }

  @SuppressWarnings("serial")
  static class CacheEntryEventImpl<K,V> extends CacheEntryEvent<K,V> {
    private K _key;
    private V _value;
    
    CacheEntryEventImpl(Cache<K,V> cache, K key, V value)
    {
      super(cache);
      
      _key = key;
      _value = value;
    }
    
    @Override
    public K getKey()
    {
      return _key;
    }
    
    @Override
    public V getValue()
    {
      return _value;
    }
  }
  
  static class LoadQueueWorker<K,V> 
    extends AbstractWorkerQueue<LoadFuture<K,V>> {
    private CacheImpl<K,V> _cache;
    
    LoadQueueWorker(CacheImpl<K,V> cache)
    {
      super(256);
      
      _cache = cache;
    }
    
    @Override
    public void process(LoadFuture<K,V> item)
    {
      CacheImpl<K,V> cache = _cache;
      
      if (cache != null) {
        item.process(cache);
      }
    }
    
    void close()
    {
      _cache = null;
    }
  }
  
  static class LoadFuture<K,V> implements Future<V> {
    private CacheImpl<K,V> _cache;
    
    private K _key;
    private Set<K> _keySet;
    private V _value;
    
    private volatile boolean _isDone;
    
    LoadFuture(CacheImpl<K,V> cache, K key)
    {
      _cache = cache;
      _key = key;
    }
    
    LoadFuture(CacheImpl<K,V> cache, Set<K> keySet)
    {
      _cache = cache;
      _keySet = keySet;
    }
    
    void process(CacheImpl<K,V> cache)
    {
      try {
        if (_keySet != null) {
          _value = (V) cache.getAll(_keySet);
        }
        else {
          _value = cache.get(_key);
        }
      } finally {
        _cache = null;
        
        synchronized (this) {
          _isDone = true;
          notifyAll();
        }
      }
    }
    
    @Override
    public boolean cancel(boolean isCancel)
    {
      return false;
    }

    @Override
    public V get() throws InterruptedException, ExecutionException
    {
      synchronized (this) {
        while (! _isDone && ! _cache.isClosed()) {
          try {
            wait();
          } catch (Exception e) {
          }
        }
      }
      
      return _value;
    }

    @Override
    public V get(long time, TimeUnit timeUnit)
      throws InterruptedException,
        ExecutionException, TimeoutException
    {
      long timeout = timeUnit.toMillis(time);
      long expireTime = CurrentTime.getCurrentTimeActual() + timeout;
      
      synchronized (this) {
        long now;
        
        while (! _isDone
               && ! _cache.isClosed()
               && ((now = CurrentTime.getCurrentTimeActual()) < expireTime)) {
          try {
            long delta = expireTime - now;
            
            wait(delta);
          } catch (Exception e) {
          }
        }
      }
      
      return _value;
    }

    @Override
    public boolean isCancelled()
    {
      return false;
    }

    @Override
    public boolean isDone()
    {
      return _isDone;
    }
    
  }
  
  class CacheAdmin extends AbstractManagedObject implements CacheMXBean {
    protected void register()
    {
      super.registerSelf();
    }
    
    void unregister()
    {
      super.unregisterSelf();
    }
    
    @Override
    public String getName()
    {
      return CacheImpl.this.getName()  + "|" + CacheImpl.this.getManagerName();
    }

    @Override
    public Status getStatus()
    {
      return CacheImpl.this.getStatus();
    }

    @Override
    public Date getStartAccumulationDate()
    {
      return null;
    }

    @Override
    public long getCacheHits()
    {
      return _hitCount.get();
    }

    @Override
    public long getCacheMisses()
    {
      return _missCount.get();
    }

    @Override
    public float getCacheHitPercentage()
    {
      return 100 - getCacheMissPercentage();
    }

    @Override
    public float getCacheMissPercentage()
    {
      long hits = getCacheHits();
      long misses = getCacheMisses();
      
      if (hits == 0)
        hits = 1;
      
      return (float) (100.0 * misses / (hits + misses));
    }

    @Override
    public long getCacheGets()
    {
      return _getCount.get();
    }

    @Override
    public long getCachePuts()
    {
      return _putCount.get();
    }

    @Override
    public long getCacheRemovals()
    {
      return _removeCount.get();
    }

    @Override
    public float getAverageGetMillis()
    {
      return 0;
    }

    @Override
    public float getAveragePutMillis()
    {
      return 0;
    }

    @Override
    public float getAverageRemoveMillis()
    {
      return 0;
    }

    @Override
    public long getCacheEvictions()
    {
      return 0;
    }

    @Override
    public void clearStatistics()
    {
    }
  }
}
