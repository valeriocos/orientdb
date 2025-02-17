/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */
package com.orientechnologies.orient.core.index;

import com.orientechnologies.common.collection.OMultiValue;
import com.orientechnologies.common.concur.lock.OOneEntryPerKeyLockManager;
import com.orientechnologies.common.concur.lock.OPartitionedLockManager;
import com.orientechnologies.common.concur.lock.OReadersWriterSpinLock;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.listener.OProgressListener;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.*;
import com.orientechnologies.orient.core.index.engine.OBaseIndexEngine;
import com.orientechnologies.orient.core.intent.OIntentMassiveInsert;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.record.impl.ODocumentInternal;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.cache.OReadCache;
import com.orientechnologies.orient.core.storage.cache.OWriteCache;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperationsManager;
import com.orientechnologies.orient.core.storage.ridbag.sbtree.OIndexRIDContainer;
import com.orientechnologies.orient.core.tx.OTransactionIndexChanges;
import com.orientechnologies.orient.core.tx.OTransactionIndexChangesPerKey;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles indexing when records change. The underlying lock manager for keys can be the {@link OPartitionedLockManager}, the
 * default one, or the {@link OOneEntryPerKeyLockManager} in case of distributed. This is to avoid deadlock situation between nodes
 * where keys have the same hash code.
 *
 * @author Luca Garulli (l.garulli--(at)--orientdb.com)
 */
public abstract class OIndexAbstract<T> implements OIndexInternal<T> {

  protected static final String                    CONFIG_MAP_RID  = "mapRid";
  private static final   String                    CONFIG_CLUSTERS = "clusters";
  final                  String                    type;
  protected final        ODocument                 metadata;
  protected final        OAbstractPaginatedStorage storage;
  private final          String                    databaseName;
  private final          String                    name;
  private final          OReadersWriterSpinLock    rwLock          = new OReadersWriterSpinLock();
  private final          AtomicLong                rebuildVersion  = new AtomicLong();
  private final          int                       version;
  volatile               IndexConfiguration        configuration;
  String valueContainerAlgorithm;

  protected int indexId    = -1;
  protected int apiVersion = -1;

  protected        Set<String>         clustersToIndex  = new HashSet<>();
  private          String              algorithm;
  private volatile OIndexDefinition    indexDefinition;
  private volatile boolean             rebuilding       = false;
  private          Map<String, String> engineProperties = new HashMap<>();
  final            int                 binaryFormatVersion;

  public OIndexAbstract(String name, final String type, final String algorithm, final String valueContainerAlgorithm,
      final ODocument metadata, final int version, final OStorage storage, int binaryFormatVersion) {
    this.binaryFormatVersion = binaryFormatVersion;
    acquireExclusiveLock();
    try {
      databaseName = storage.getName();

      this.version = version;
      this.name = name;
      this.type = type;
      this.algorithm = algorithm;
      this.metadata = metadata;
      this.valueContainerAlgorithm = valueContainerAlgorithm;
      this.storage = (OAbstractPaginatedStorage) storage.getUnderlying();
    } finally {
      releaseExclusiveLock();
    }
  }

  public static OIndexMetadata loadMetadataInternal(final ODocument config, final String type, final String algorithm,
      final String valueContainerAlgorithm) {
    final String indexName = config.field(OIndexInternal.CONFIG_NAME);

    final ODocument indexDefinitionDoc = config.field(OIndexInternal.INDEX_DEFINITION);
    OIndexDefinition loadedIndexDefinition = null;
    if (indexDefinitionDoc != null) {
      try {
        final String indexDefClassName = config.field(OIndexInternal.INDEX_DEFINITION_CLASS);
        final Class<?> indexDefClass = Class.forName(indexDefClassName);
        loadedIndexDefinition = (OIndexDefinition) indexDefClass.getDeclaredConstructor().newInstance();
        loadedIndexDefinition.fromStream(indexDefinitionDoc);

      } catch (final ClassNotFoundException | IllegalAccessException | InstantiationException | InvocationTargetException | NoSuchMethodException e) {
        throw OException.wrapException(new OIndexException("Error during deserialization of index definition"), e);
      }
    } else {
      // @COMPATIBILITY 1.0rc6 new index model was implemented
      final Boolean isAutomatic = config.field(OIndexInternal.CONFIG_AUTOMATIC);
      OIndexFactory factory = OIndexes.getFactory(type, algorithm);
      if (Boolean.TRUE.equals(isAutomatic)) {
        final int pos = indexName.lastIndexOf('.');
        if (pos < 0)
          throw new OIndexException(
              "Cannot convert from old index model to new one. " + "Invalid index name. Dot (.) separator should be present");
        final String className = indexName.substring(0, pos);
        final String propertyName = indexName.substring(pos + 1);

        final String keyTypeStr = config.field(OIndexInternal.CONFIG_KEYTYPE);
        if (keyTypeStr == null)
          throw new OIndexException("Cannot convert from old index model to new one. " + "Index key type is absent");
        final OType keyType = OType.valueOf(keyTypeStr.toUpperCase(Locale.ENGLISH));

        loadedIndexDefinition = new OPropertyIndexDefinition(className, propertyName, keyType);

        config.removeField(OIndexInternal.CONFIG_AUTOMATIC);
        config.removeField(OIndexInternal.CONFIG_KEYTYPE);
      } else if (config.field(OIndexInternal.CONFIG_KEYTYPE) != null) {
        final String keyTypeStr = config.field(OIndexInternal.CONFIG_KEYTYPE);
        final OType keyType = OType.valueOf(keyTypeStr.toUpperCase(Locale.ENGLISH));

        loadedIndexDefinition = new OSimpleKeyIndexDefinition(keyType);

        config.removeField(OIndexInternal.CONFIG_KEYTYPE);
      }
    }

    final Set<String> clusters = new HashSet<>(config.field(CONFIG_CLUSTERS, OType.EMBEDDEDSET));

    return new OIndexMetadata(indexName, loadedIndexDefinition, clusters, type, algorithm, valueContainerAlgorithm);
  }

  public void flush() {
  }

  @Override
  public boolean hasRangeQuerySupport() {

    acquireSharedLock();
    try {
      while (true)
        try {
          return storage.hasIndexRangeQuerySupport(indexId);
        } catch (OInvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
    } finally {
      releaseSharedLock();
    }

  }

  /**
   * Creates the index.
   *
   * @param clusterIndexName Cluster name where to place the TreeMap
   */
  public OIndexInternal<?> create(final OIndexDefinition indexDefinition, final String clusterIndexName,
      final Set<String> clustersToIndex, boolean rebuild, final OProgressListener progressListener,
      final OBinarySerializer valueSerializer) {
    acquireExclusiveLock();
    try {
      configuration = indexConfigurationInstance(new ODocument().setTrackingChanges(false));

      this.indexDefinition = indexDefinition;

      if (clustersToIndex != null)
        this.clustersToIndex = new HashSet<>(clustersToIndex);
      else
        this.clustersToIndex = new HashSet<>();

      // do not remove this, it is needed to remove index garbage if such one exists
      try {
        if (apiVersion == 0) {
          removeValuesContainer();
        }
      } catch (Exception e) {
        OLogManager.instance().error(this, "Error during deletion of index '%s'", e, name);
      }

      indexId = storage.addIndexEngine(name, algorithm, type, indexDefinition, valueSerializer, isAutomatic(), true, version, 1,
          this instanceof OIndexMultiValues, getEngineProperties(), clustersToIndex, metadata);
      apiVersion = OAbstractPaginatedStorage.extractEngineAPIVersion(indexId);

      assert indexId >= 0;
      assert apiVersion >= 0;

      onIndexEngineChange(indexId);

      if (rebuild)
        fillIndex(progressListener, false);

      updateConfiguration();
    } catch (Exception e) {
      OLogManager.instance().error(this, "Exception during index '%s' creation", e, name);

      while (true)
        try {
          if (indexId >= 0)
            storage.deleteIndexEngine(indexId);
          break;
        } catch (OInvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        } catch (Exception ex) {
          OLogManager.instance().error(this, "Exception during index '%s' deletion", ex, name);
        }

      if (e instanceof OIndexException)
        throw (OIndexException) e;

      throw OException.wrapException(new OIndexException("Cannot create the index '" + name + "'"), e);

    } finally {
      releaseExclusiveLock();
    }

    return this;
  }

  protected void doReloadIndexEngine() {
    indexId = storage.loadIndexEngine(name);
    apiVersion = OAbstractPaginatedStorage.extractEngineAPIVersion(indexId);

    if (indexId < 0) {
      throw new IllegalStateException("Index " + name + " can not be loaded");
    }
  }

  public long count(final Object iKey) {
    final Object result = get(iKey);
    if (result == null)
      return 0;
    else if (OMultiValue.isMultiValue(result))
      return OMultiValue.getSize(result);
    return 1;
  }

  public boolean loadFromConfiguration(final ODocument config) {
    acquireExclusiveLock();
    try {
      configuration = indexConfigurationInstance(config);
      clustersToIndex.clear();

      final OIndexMetadata indexMetadata = loadMetadata(config);
      indexDefinition = indexMetadata.getIndexDefinition();
      clustersToIndex.addAll(indexMetadata.getClustersToIndex());
      algorithm = indexMetadata.getAlgorithm();
      valueContainerAlgorithm = indexMetadata.getValueContainerAlgorithm();

      try {
        indexId = storage.loadIndexEngine(name);
        apiVersion = OAbstractPaginatedStorage.extractEngineAPIVersion(indexId);

        if (indexId == -1) {
          indexId = storage
              .loadExternalIndexEngine(name, algorithm, type, indexDefinition, determineValueSerializer(), isAutomatic(), true,
                  version, 1, this instanceof OIndexMultiValues, getEngineProperties(), metadata);
          apiVersion = OAbstractPaginatedStorage.extractEngineAPIVersion(indexId);
        }

        if (indexId == -1) {
          return false;
        }

        onIndexEngineChange(indexId);

      } catch (Exception e) {
        OLogManager.instance().error(this, "Error during load of index '%s'", e, name != null ? name : "null");

        if (isAutomatic()) {
          // AUTOMATIC REBUILD IT
          OLogManager.instance().warn(this, "Cannot load index '%s' rebuilt it from scratch", getName());
          try {
            rebuild();
          } catch (Exception t) {
            OLogManager.instance()
                .error(this, "Cannot rebuild index '%s' because '" + t + "'. The index will be removed in configuration", e,
                    getName());
            // REMOVE IT
            return false;
          }
        }
      }

      return true;
    } finally {
      releaseExclusiveLock();
    }
  }

  private Map<String, String> getEngineProperties() {
    return engineProperties;
  }

  @Override
  public OIndexMetadata loadMetadata(final ODocument config) {
    return loadMetadataInternal(config, type, algorithm, valueContainerAlgorithm);
  }

  public boolean contains(Object key) {
    key = getCollatingValue(key);

    acquireSharedLock();
    try {
      assert indexId >= 0;

      while (true)
        try {
          return storage.indexContainsKey(indexId, key);
        } catch (OInvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
    } finally {
      releaseSharedLock();
    }
  }

  /**
   * {@inheritDoc}
   */
  public long rebuild() {
    return rebuild(new OIndexRebuildOutputListener(this));
  }

  @Override
  public void setRebuildingFlag() {
    rebuilding = true;
  }

  @Override
  public void close() {

  }

  @Override
  public Object getFirstKey() {
    acquireSharedLock();
    try {
      while (true)
        try {
          return storage.getIndexFirstKey(indexId);
        } catch (OInvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public Object getLastKey() {
    acquireSharedLock();
    try {
      while (true)
        try {
          return storage.getIndexLastKey(indexId);
        } catch (OInvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
    } finally {
      releaseSharedLock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long getRebuildVersion() {
    return 0;
  }

  /**
   * {@inheritDoc}
   */
  public long rebuild(final OProgressListener iProgressListener) {
    long documentIndexed;

    final boolean intentInstalled = getDatabase().declareIntent(new OIntentMassiveInsert());

    acquireExclusiveLock();
    try {
      // DO NOT REORDER 2 assignments bellow
      // see #getRebuildVersion()
      rebuilding = true;
      rebuildVersion.incrementAndGet();

      try {
        if (indexId >= 0) {
          storage.deleteIndexEngine(indexId);
        }
      } catch (Exception e) {
        OLogManager.instance().error(this, "Error during index '%s' delete", e, name);
      }

      removeValuesContainer();

      indexId = storage
          .addIndexEngine(name, algorithm, type, indexDefinition, determineValueSerializer(), isAutomatic(), true, version, 1,
              this instanceof OIndexMultiValues, getEngineProperties(), clustersToIndex, metadata);
      apiVersion = OAbstractPaginatedStorage.extractEngineAPIVersion(indexId);

      onIndexEngineChange(indexId);
    } catch (Exception e) {
      try {
        if (indexId >= 0)
          storage.clearIndex(indexId);
      } catch (Exception e2) {
        OLogManager.instance().error(this, "Error during index rebuild", e2);
        // IGNORE EXCEPTION: IF THE REBUILD WAS LAUNCHED IN CASE OF RID INVALID CLEAR ALWAYS GOES IN ERROR
      }

      rebuilding = false;
      throw OException.wrapException(new OIndexException("Error on rebuilding the index for clusters: " + clustersToIndex), e);
    } finally {
      releaseExclusiveLock();
    }

    acquireSharedLock();
    try {
      documentIndexed = fillIndex(iProgressListener, true);
    } catch (final Exception e) {
      OLogManager.instance().error(this, "Error during index rebuild", e);
      try {
        if (indexId >= 0)
          storage.clearIndex(indexId);
      } catch (Exception e2) {
        OLogManager.instance().error(this, "Error during index rebuild", e2);
        // IGNORE EXCEPTION: IF THE REBUILD WAS LAUNCHED IN CASE OF RID INVALID CLEAR ALWAYS GOES IN ERROR
      }

      throw OException.wrapException(new OIndexException("Error on rebuilding the index for clusters: " + clustersToIndex), e);
    } finally {
      rebuilding = false;

      if (intentInstalled)
        getDatabase().declareIntent(null);

      releaseSharedLock();
    }

    return documentIndexed;
  }

  private long fillIndex(final OProgressListener iProgressListener, final boolean rebuild) {
    long documentIndexed = 0;
    try {
      long documentNum = 0;
      long documentTotal = 0;

      for (final String cluster : clustersToIndex)
        documentTotal += storage.count(storage.getClusterIdByName(cluster));

      if (iProgressListener != null)
        iProgressListener.onBegin(this, documentTotal, rebuild);

      // INDEX ALL CLUSTERS
      for (final String clusterName : clustersToIndex) {
        final long[] metrics = indexCluster(clusterName, iProgressListener, documentNum, documentIndexed, documentTotal);
        documentNum = metrics[0];
        documentIndexed = metrics[1];
      }

      if (iProgressListener != null)
        iProgressListener.onCompletition(this, true);
    } catch (final RuntimeException e) {
      if (iProgressListener != null)
        iProgressListener.onCompletition(this, false);
      throw e;
    }
    return documentIndexed;
  }

  public boolean remove(Object key, final OIdentifiable value) {
    return remove(key);
  }

  public boolean remove(Object key) {
    key = getCollatingValue(key);

    acquireSharedLock();
    try {
      while (true)
        try {
          return storage.removeKeyFromIndex(indexId, key);
        } catch (OInvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
    } finally {
      releaseSharedLock();
    }
  }

  /**
   * {@inheritDoc}
   *
   * @deprecated Manual indexes are deprecated and will be removed
   */
  @Override
  @Deprecated
  public OIndex<T> clear() {
    acquireSharedLock();
    try {
      while (true)
        try {
          final boolean manualIndexesAreUsed =
              indexDefinition == null || indexDefinition.getClassName() == null || indexDefinition.getFields() == null
                  || indexDefinition.getFields().isEmpty();
          if (manualIndexesAreUsed) {
            OIndexAbstract.manualIndexesWarning();
          }

          storage.clearIndex(indexId);
          break;
        } catch (OInvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
      return this;
    } finally {
      releaseSharedLock();
    }
  }

  public OIndexInternal<T> delete() {
    acquireExclusiveLock();

    try {
      while (true)
        try {
          final OIndexKeyCursor indexCursor = keyCursor();

          Object key = indexCursor.next(-1);
          while (key != null) {
            Object entry = get(key);
            if (entry instanceof Collection) {
              //noinspection unchecked
              for (final OIdentifiable entryItem : (Collection<OIdentifiable>) entry) {
                remove(key, entryItem);
              }
            } else {
              remove(key, (OIdentifiable) entry);
            }

            key = indexCursor.next(-1);
          }

          storage.deleteIndexEngine(indexId);
          break;
        } catch (OInvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }

      // REMOVE THE INDEX ALSO FROM CLASS MAP
      if (getDatabase().getMetadata() != null)
        getDatabase().getMetadata().getIndexManagerInternal().removeClassPropertyIndex(this);

      removeValuesContainer();
      return this;
    } finally {
      releaseExclusiveLock();
    }
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }

  @Override
  public void setType(OType type) {
    indexDefinition = new OSimpleKeyIndexDefinition(type);
    updateConfiguration();
  }

  @Override
  public String getAlgorithm() {
    acquireSharedLock();
    try {
      return algorithm;
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public String toString() {
    acquireSharedLock();
    try {
      return name;
    } finally {
      releaseSharedLock();
    }
  }

  public OIndexInternal<T> getInternal() {
    return this;
  }

  public Set<String> getClusters() {
    acquireSharedLock();
    try {
      return Collections.unmodifiableSet(clustersToIndex);
    } finally {
      releaseSharedLock();
    }
  }

  public OIndexAbstract<T> addCluster(final String clusterName) {
    acquireExclusiveLock();
    try {
      if (clustersToIndex.add(clusterName)) {
        updateConfiguration();

        // INDEX SINGLE CLUSTER
        indexCluster(clusterName, null, 0, 0, 0);
      }

      return this;
    } finally {
      releaseExclusiveLock();
    }
  }

  public OIndexAbstract<T> removeCluster(String iClusterName) {
    acquireExclusiveLock();
    try {
      if (clustersToIndex.remove(iClusterName)) {
        updateConfiguration();
        rebuild();
      }

      return this;
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public int getVersion() {
    return version;
  }

  public ODocument updateConfiguration() {
    configuration.updateConfiguration(type, name, version, indexDefinition, clustersToIndex, algorithm, valueContainerAlgorithm);
    if (metadata != null)
      configuration.document.field(OIndexInternal.METADATA, metadata, OType.EMBEDDED);
    return configuration.getDocument();
  }

  public void addTxOperation(IndexTxSnapshot snapshots, final OTransactionIndexChanges changes) {
    acquireSharedLock();
    try {
      if (changes.cleared)
        clearSnapshot(snapshots);
      final Map<Object, Object> snapshot = snapshots.indexSnapshot;
      for (final OTransactionIndexChangesPerKey entry : changes.changesPerKey.values()) {
        applyIndexTxEntry(snapshot, entry);
      }
      applyIndexTxEntry(snapshot, changes.nullKeyChanges);

    } finally {
      releaseSharedLock();
    }
  }

  /**
   * Interprets transaction index changes for a certain key. Override it to customize index behaviour on interpreting index changes.
   * This may be viewed as an optimization, but in some cases this is a requirement. For example, if you put multiple values under
   * the same key during the transaction for single-valued/unique index, but remove all of them except one before commit, there is
   * no point in throwing {@link com.orientechnologies.orient.core.storage.ORecordDuplicatedException} while applying index
   * changes.
   *
   * @param changes the changes to interpret.
   *
   * @return the interpreted index key changes.
   */
  protected Iterable<OTransactionIndexChangesPerKey.OTransactionIndexEntry> interpretTxKeyChanges(
      OTransactionIndexChangesPerKey changes) {
    return changes.entries;
  }

  private void applyIndexTxEntry(Map<Object, Object> snapshot, OTransactionIndexChangesPerKey entry) {
    for (OTransactionIndexChangesPerKey.OTransactionIndexEntry op : interpretTxKeyChanges(entry)) {
      switch (op.operation) {
      case PUT:
        putInSnapshot(entry.key, op.value, snapshot);
        break;
      case REMOVE:
        if (op.value != null)
          removeFromSnapshot(entry.key, op.value, snapshot);
        else
          removeFromSnapshot(entry.key, snapshot);
        break;
      case CLEAR:
        // SHOULD NEVER BE THE CASE HANDLE BY cleared FLAG
        break;
      }
    }
  }

  public void commit(IndexTxSnapshot snapshots) {
    acquireSharedLock();
    try {
      if (snapshots.clear)
        clear();

      commitSnapshot(snapshots.indexSnapshot);
    } finally {
      releaseSharedLock();
    }
  }

  public void preCommit(IndexTxSnapshot snapshots) {
  }

  public void postCommit(IndexTxSnapshot snapshots) {
  }

  public ODocument getConfiguration() {
    return configuration.getDocument();
  }

  @Override
  public ODocument getMetadata() {
    return metadata;
  }

  @Override
  public boolean isUnique() {
    return false;
  }

  public boolean isAutomatic() {
    acquireSharedLock();
    try {
      return indexDefinition != null && indexDefinition.getClassName() != null;
    } finally {
      releaseSharedLock();
    }
  }

  public OType[] getKeyTypes() {
    acquireSharedLock();
    try {
      if (indexDefinition == null)
        return null;

      return indexDefinition.getTypes();
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public OIndexKeyCursor keyCursor() {
    acquireSharedLock();
    try {
      while (true)
        try {
          return storage.getIndexKeyCursor(indexId);
        } catch (OInvalidIndexEngineIdException ignore) {
          doReloadIndexEngine();
        }
    } finally {
      releaseSharedLock();
    }
  }

  public OIndexDefinition getDefinition() {
    return indexDefinition;
  }

  @Override
  public boolean equals(final Object o) {
    acquireSharedLock();
    try {
      if (this == o)
        return true;
      if (o == null || getClass() != o.getClass())
        return false;

      final OIndexAbstract<?> that = (OIndexAbstract<?>) o;

      return name.equals(that.name);
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public int hashCode() {
    acquireSharedLock();
    try {
      return name.hashCode();
    } finally {
      releaseSharedLock();
    }
  }

  public int getIndexId() {
    return indexId;
  }

  public String getDatabaseName() {
    return databaseName;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isRebuilding() {
    return rebuilding;
  }

  protected abstract OBinarySerializer determineValueSerializer();

  private void populateIndex(ODocument doc, Object fieldValue) {
    if (fieldValue instanceof Collection) {
      for (final Object fieldValueItem : (Collection<?>) fieldValue) {
        put(fieldValueItem, doc);
      }
    } else
      put(fieldValue, doc);
  }

  public Object getCollatingValue(final Object key) {
    if (key != null && getDefinition() != null)
      return getDefinition().getCollate().transform(key);
    return key;
  }

  protected void commitSnapshot(Map<Object, Object> snapshot) {
    // do nothing by default
    // storage will delay real operations till the end of tx
  }

  protected void putInSnapshot(Object key, OIdentifiable value, Map<Object, Object> snapshot) {
    // storage will delay real operations till the end of tx
    put(key, value);
  }

  protected void removeFromSnapshot(Object key, OIdentifiable value, Map<Object, Object> snapshot) {
    // storage will delay real operations till the end of tx
    remove(key, value);
  }

  private void removeFromSnapshot(Object key, Map<Object, Object> snapshot) {
    // storage will delay real operations till the end of tx
    remove(key);
  }

  protected void clearSnapshot(IndexTxSnapshot indexTxSnapshot) {
    // storage will delay real operations till the end of tx
    clear();
  }

  @Override
  public int compareTo(OIndex<T> index) {
    acquireSharedLock();
    try {
      final String name = index.getName();
      return this.name.compareTo(name);
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  public String getIndexNameByKey(final Object key) {
    OBaseIndexEngine engine;

    while (true) {
      try {
        engine = storage.getIndexEngine(indexId);
        break;
      } catch (OInvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }
    return engine.getIndexNameByKey(key);
  }

  @Override
  public boolean acquireAtomicExclusiveLock(Object key) {
    OBaseIndexEngine engine;

    while (true) {
      try {
        engine = storage.getIndexEngine(indexId);
        break;
      } catch (OInvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }

    return engine.acquireAtomicExclusiveLock(key);
  }

  protected static ODatabaseDocumentInternal getDatabase() {
    return ODatabaseRecordThreadLocal.instance().get();
  }

  private long[] indexCluster(final String clusterName, final OProgressListener iProgressListener, long documentNum,
      long documentIndexed, long documentTotal) {
    try {
      for (final ORecord record : getDatabase().browseCluster(clusterName)) {
        if (Thread.interrupted())
          throw new OCommandExecutionException("The index rebuild has been interrupted");

        if (record instanceof ODocument) {
          final ODocument doc = (ODocument) record;

          if (indexDefinition == null)
            throw new OConfigurationException(
                "Index '" + name + "' cannot be rebuilt because has no a valid definition (" + indexDefinition + ")");

          final Object fieldValue = indexDefinition.getDocumentValueToIndex(doc);

          if (fieldValue != null || !indexDefinition.isNullValuesIgnored()) {
            try {
              populateIndex(doc, fieldValue);
            } catch (OTooBigIndexKeyException | OIndexException e) {
              OLogManager.instance().error(this,
                  "Exception during index rebuild. Exception was caused by following key/ value pair - key %s, value %s."
                      + " Rebuild will continue from this point", e, fieldValue, doc.getIdentity());
            }

            ++documentIndexed;
          }
        }
        documentNum++;

        if (iProgressListener != null)
          iProgressListener.onProgress(this, documentNum, (float) (documentNum * 100.0 / documentTotal));
      }
    } catch (NoSuchElementException ignore) {
      // END OF CLUSTER REACHED, IGNORE IT
    }

    return new long[] { documentNum, documentIndexed };
  }

  protected void releaseExclusiveLock() {
    rwLock.releaseWriteLock();
  }

  protected void acquireExclusiveLock() {
    rwLock.acquireWriteLock();
  }

  protected void releaseSharedLock() {
    rwLock.releaseReadLock();
  }

  protected void acquireSharedLock() {
    rwLock.acquireReadLock();
  }

  private void removeValuesContainer() {
    if (valueContainerAlgorithm.equals(ODefaultIndexFactory.SBTREE_BONSAI_VALUE_CONTAINER)) {

      final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

      final OReadCache readCache = storage.getReadCache();
      final OWriteCache writeCache = storage.getWriteCache();

      if (atomicOperation == null) {
        try {
          final String fileName = getName() + OIndexRIDContainer.INDEX_FILE_EXTENSION;
          if (writeCache.exists(fileName)) {
            final long fileId = writeCache.loadFile(fileName);
            readCache.deleteFile(fileId, writeCache);
          }
        } catch (IOException e) {
          OLogManager.instance().error(this, "Cannot delete file for value containers", e);
        }
      } else {
        try {
          final String fileName = getName() + OIndexRIDContainer.INDEX_FILE_EXTENSION;
          if (atomicOperation.isFileExists(fileName)) {
            final long fileId = atomicOperation.loadFile(fileName);
            atomicOperation.deleteFile(fileId);
          }
        } catch (IOException e) {
          OLogManager.instance().error(this, "Cannot delete file for value containers", e);
        }
      }

    }
  }

  protected void onIndexEngineChange(final int indexId) {
    while (true)
      try {
        storage.callIndexEngine(false, false, indexId, engine -> {
          engine.init(getName(), getType(), getDefinition(), isAutomatic(), getMetadata());
          return null;
        });
        break;
      } catch (OInvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
  }

  IndexConfiguration indexConfigurationInstance(final ODocument document) {
    return new IndexConfiguration(document);
  }

  public static final class IndexTxSnapshot {
    public Map<Object, Object> indexSnapshot = new HashMap<>();
    public boolean             clear         = false;
  }

  protected static class IndexConfiguration {
    final ODocument document;

    public IndexConfiguration(ODocument document) {
      this.document = document;
    }

    ODocument getDocument() {
      return document;
    }

    synchronized void updateConfiguration(String type, String name, int version, OIndexDefinition indexDefinition,
        Set<String> clustersToIndex, String algorithm, String valueContainerAlgorithm) {
      document.field(OIndexInternal.CONFIG_TYPE, type);
      document.field(OIndexInternal.CONFIG_NAME, name);
      document.field(OIndexInternal.INDEX_VERSION, version);

      if (indexDefinition != null) {

        final ODocument indexDefDocument = indexDefinition.toStream();
        if (!indexDefDocument.hasOwners())
          ODocumentInternal.addOwner(indexDefDocument, document);

        document.field(OIndexInternal.INDEX_DEFINITION, indexDefDocument, OType.EMBEDDED);
        document.field(OIndexInternal.INDEX_DEFINITION_CLASS, indexDefinition.getClass().getName());
      } else {
        document.removeField(OIndexInternal.INDEX_DEFINITION);
        document.removeField(OIndexInternal.INDEX_DEFINITION_CLASS);
      }

      document.field(CONFIG_CLUSTERS, clustersToIndex, OType.EMBEDDEDSET);
      document.field(ALGORITHM, algorithm);
      document.field(VALUE_CONTAINER_ALGORITHM, valueContainerAlgorithm);

    }
  }

  public static void manualIndexesWarning() {
    if (!OGlobalConfiguration.INDEX_ALLOW_MANUAL_INDEXES.getValueAsBoolean()) {
      throw new OManualIndexesAreProhibited(
          "Manual indexes are deprecated , not supported any more and will be removed in next versions if you still want to use them, "
              + "please set global property `" + OGlobalConfiguration.INDEX_ALLOW_MANUAL_INDEXES.getKey() + "` to `true`");
    }

    if (OGlobalConfiguration.INDEX_ALLOW_MANUAL_INDEXES_WARNING.getValueAsBoolean()) {
      OLogManager.instance().warn(OIndexAbstract.class, "Seems you use manual indexes. "
          + "Manual indexes are deprecated , not supported any more and will be removed in next versions if you do not want "
          + "to see warning, please set global property `" + OGlobalConfiguration.INDEX_ALLOW_MANUAL_INDEXES_WARNING.getKey()
          + "` to `false`");
    }
  }
}
