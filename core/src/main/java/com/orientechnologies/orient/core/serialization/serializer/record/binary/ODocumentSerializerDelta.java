package com.orientechnologies.orient.core.serialization.serializer.record.binary;

import com.orientechnologies.common.collection.OMultiValue;
import com.orientechnologies.common.serialization.types.ODecimalSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.common.serialization.types.OUUIDSerializer;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.record.*;
import com.orientechnologies.orient.core.db.record.ridbag.ORidBag;
import com.orientechnologies.orient.core.exception.OSerializationException;
import com.orientechnologies.orient.core.exception.OValidationException;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.record.impl.ODocumentEmbedded;
import com.orientechnologies.orient.core.record.impl.ODocumentEntry;
import com.orientechnologies.orient.core.record.impl.ODocumentInternal;
import com.orientechnologies.orient.core.serialization.ODocumentSerializable;
import com.orientechnologies.orient.core.serialization.OSerializableStream;
import com.orientechnologies.orient.core.storage.index.sbtreebonsai.local.OBonsaiBucketPointer;
import com.orientechnologies.orient.core.storage.ridbag.sbtree.Change;
import com.orientechnologies.orient.core.storage.ridbag.sbtree.ChangeSerializationHelper;
import com.orientechnologies.orient.core.storage.ridbag.sbtree.OBonsaiCollectionPointer;
import com.orientechnologies.orient.core.storage.ridbag.sbtree.OSBTreeCollectionManager;
import com.orientechnologies.orient.core.util.ODateHelper;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;

import static com.orientechnologies.orient.core.serialization.serializer.record.binary.HelperClasses.*;

public class ODocumentSerializerDelta {
  private static final byte CREATED  = 1;
  private static final byte REPLACED = 2;
  private static final byte CHANGED  = 3;
  private static final byte REMOVED  = 4;

  public byte[] serializeDelta(ODocument document) {
    BytesContainer bytes = new BytesContainer();
    serializeDelta(bytes, document);
    return bytes.fitBytes();
  }

  protected OClass serializeClass(final ODocument document, final BytesContainer bytes) {
    final OClass clazz = ODocumentInternal.getImmutableSchemaClass(document);
    String name = null;
    if (clazz != null)
      name = clazz.getName();
    if (name == null)
      name = document.getClassName();

    if (name != null)
      writeString(bytes, name);
    else
      writeEmptyString(bytes);
    return clazz;
  }

  private int writeEmptyString(final BytesContainer bytes) {
    return OVarIntSerializer.write(bytes, 0);
  }

  public void serialize(final ODocument document, final BytesContainer bytes) {
    serializeClass(document, bytes);
    OClass oClass = ODocumentInternal.getImmutableSchemaClass(document);
    final Set<Map.Entry<String, ODocumentEntry>> fields = ODocumentInternal.rawEntries(document);
    OVarIntSerializer.write(bytes, document.fields());
    for (Map.Entry<String, ODocumentEntry> entry : fields) {
      ODocumentEntry docEntry = entry.getValue();
      if (!docEntry.exists())
        continue;
      writeString(bytes, entry.getKey());
      final Object value = entry.getValue().value;
      if (value != null) {
        final OType type = getFieldType(entry.getValue());
        if (type == null) {
          throw new OSerializationException(
              "Impossible serialize value of type " + value.getClass() + " with the Result binary serializer");
        }
        writeType(bytes, type);
        serializeValue(bytes, value, type, getLinkedType(oClass, type, entry.getKey()));
      } else {
        writeType(bytes, null);
      }
    }
  }

  public void deserialize(final ODocument document, final BytesContainer bytes) {
    final String className = readString(bytes);
    if (className.length() != 0)
      ODocumentInternal.fillClassNameIfNeeded(document, className);

    String fieldName;
    OType type;
    Object value;
    int size = OVarIntSerializer.readAsInteger(bytes);
    while ((size--) > 0) {
      // PARSE FIELD NAME
      fieldName = readString(bytes);
      type = readType(bytes);
      if (type == null) {
        value = null;
      } else {
        value = deserializeValue(bytes, type, document);
      }
      document.setProperty(fieldName, value, type);
    }
  }

  public void deserializeDelta(byte[] content, ODocument toFill) {
    BytesContainer bytesContainer = new BytesContainer(content);
    deserializeDelta(bytesContainer, toFill);
  }

  public void deserializeDelta(BytesContainer bytes, ODocument toFill) {
    final String className = readString(bytes);
    if (className.length() != 0)
      ODocumentInternal.fillClassNameIfNeeded(toFill, className);
    long count = OVarIntSerializer.readAsLong(bytes);
    while (count-- > 0) {
      switch (deserializeByte(bytes)) {
      case CREATED:
        deserializeFullEntry(bytes, toFill);
        break;
      case REPLACED:
        deserializeFullEntry(bytes, toFill);
        break;
      case CHANGED:
        deserializeDeltaEntry(bytes, toFill);
        break;
      case REMOVED:
        String property = readString(bytes);
        toFill.removeProperty(property);
        break;
      }
    }
  }

  private void deserializeDeltaEntry(BytesContainer bytes, ODocument toFill) {
    String name = readString(bytes);
    OType type = readOType(bytes, false);
    Object toUpdate = toFill.getProperty(name);
    deserializeDeltaValue(bytes, type, toUpdate);
  }

  private void deserializeDeltaValue(BytesContainer bytes, OType type, Object toUpdate) {
    switch (type) {
    case EMBEDDEDLIST:
      deserializeDeltaEmbeddedList(bytes, (OTrackedList) toUpdate);
      break;
    case EMBEDDEDSET:
      deserializeDeltaEmbeddedSet(bytes, (OTrackedSet) toUpdate);
      break;
    case EMBEDDEDMAP:
      deserializeDeltaEmbeddedMap(bytes, (OTrackedMap) toUpdate);
      break;
    case EMBEDDED:
      deserializeDelta(bytes, (ODocument) toUpdate);
      break;
    case LINKLIST:
      deserializeDeltaLinkList(bytes, (ORecordLazyList) toUpdate);
      break;
    case LINKSET:
      deserializeDeltaLinkSet(bytes, (ORecordLazySet) toUpdate);
      break;
    case LINKMAP:
      deserializeDeltaLinkMap(bytes, (ORecordLazyMap) toUpdate);
      break;
    case LINKBAG:
      deserializeDeltaLinkBag(bytes, (ORidBag) toUpdate);
      break;
    default:
      throw new OSerializationException("delta not supported for type:" + type);
    }
  }

  private void deserializeDeltaLinkMap(BytesContainer bytes, ORecordLazyMap toUpdate) {
    long rootChanges = OVarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      byte change = deserializeByte(bytes);
      switch (change) {
      case CREATED: {
        String key = readString(bytes);
        ORecordId link = readOptimizedLink(bytes, false);
        toUpdate.put(key, link);
        break;
      }
      case CHANGED: {
        String key = readString(bytes);
        ORecordId link = readOptimizedLink(bytes, false);
        toUpdate.put(key, link);
        break;
      }
      case REMOVED: {
        String key = readString(bytes);
        toUpdate.remove(key);
        break;
      }
      }
    }
  }

  private void deserializeDeltaLinkBag(BytesContainer bytes, ORidBag toUpdate) {
    long rootChanges = OVarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      byte change = deserializeByte(bytes);
      switch (change) {
      case CREATED: {
        ORecordId link = readOptimizedLink(bytes, false);
        toUpdate.add(link);
        break;
      }
      case CHANGED: {
        break;
      }
      case REMOVED: {
        ORecordId link = readOptimizedLink(bytes, false);
        toUpdate.remove(link);
        break;
      }
      }
    }
  }

  private void deserializeDeltaLinkList(BytesContainer bytes, ORecordLazyList toUpdate) {
    long rootChanges = OVarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      byte change = deserializeByte(bytes);
      switch (change) {
      case CREATED: {
        ORecordId link = readOptimizedLink(bytes, false);
        toUpdate.add(link);
        break;
      }
      case CHANGED: {
        long position = OVarIntSerializer.readAsLong(bytes);
        ORecordId link = readOptimizedLink(bytes, false);
        toUpdate.set((int) position, link);
        break;
      }
      case REMOVED: {
        ORecordId link = readOptimizedLink(bytes, false);
        toUpdate.remove(link);
        break;
      }
      }
    }
  }

  private void deserializeDeltaLinkSet(BytesContainer bytes, ORecordLazySet toUpdate) {
    long rootChanges = OVarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      byte change = deserializeByte(bytes);
      switch (change) {
      case CREATED: {
        ORecordId link = readOptimizedLink(bytes, false);
        toUpdate.add(link);
        break;
      }
      case CHANGED: {
        break;
      }
      case REMOVED: {
        ORecordId link = readOptimizedLink(bytes, false);
        toUpdate.remove(link);
        break;
      }
      }
    }
  }

  private void deserializeDeltaEmbeddedMap(BytesContainer bytes, OTrackedMap toUpdate) {
    long rootChanges = OVarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      byte change = deserializeByte(bytes);
      switch (change) {
      case CREATED: {
        String key = readString(bytes);
        OType type = readType(bytes);
        Object value = deserializeValue(bytes, type, toUpdate);
        toUpdate.put(key, value);
        break;
      }
      case REPLACED: {
        String key = readString(bytes);
        OType type = readType(bytes);
        Object value = deserializeValue(bytes, type, toUpdate);
        toUpdate.put(key, value);
        break;
      }
      case REMOVED:
        String key = readString(bytes);
        toUpdate.remove(key);
        break;
      }
    }
    long nestedChanges = OVarIntSerializer.readAsLong(bytes);
    while (nestedChanges-- > 0) {
      byte other = deserializeByte(bytes);
      assert other == CHANGED;
      String key = readString(bytes);
      Object nested = toUpdate.get(key);
      OType type = readType(bytes);
      deserializeDeltaValue(bytes, type, nested);
    }
  }

  private void deserializeDeltaEmbeddedSet(BytesContainer bytes, OTrackedSet toUpdate) {
    long rootChanges = OVarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      byte change = deserializeByte(bytes);
      switch (change) {
      case CREATED: {
        OType type = readType(bytes);
        Object value = deserializeValue(bytes, type, toUpdate);
        toUpdate.add(value);
        break;
      }
      case REPLACED:
        assert false : "this can't ever come";
      case REMOVED:
        OType type = readType(bytes);
        Object value = deserializeValue(bytes, type, toUpdate);
        toUpdate.remove(value);
        break;
      }
    }
    long nestedChanges = OVarIntSerializer.readAsLong(bytes);
    while (nestedChanges-- > 0) {
      byte other = deserializeByte(bytes);
      assert other == CHANGED;
      long position = OVarIntSerializer.readAsLong(bytes);
      OType type = readType(bytes);
      Iterator iter = toUpdate.iterator();
      for (int i = 0; i < position; i++) {
        iter.next();
      }
      Object nested = iter.next();
      deserializeDeltaValue(bytes, type, nested);
    }
  }

  private void deserializeDeltaEmbeddedList(BytesContainer bytes, OTrackedList toUpdate) {
    long rootChanges = OVarIntSerializer.readAsLong(bytes);
    while (rootChanges-- > 0) {
      byte change = deserializeByte(bytes);
      switch (change) {
      case CREATED: {
        OType type = readType(bytes);
        Object value = deserializeValue(bytes, type, toUpdate);
        toUpdate.add(value);
        break;
      }
      case REPLACED: {
        long pos = OVarIntSerializer.readAsLong(bytes);
        OType type = readType(bytes);
        Object value = deserializeValue(bytes, type, toUpdate);
        toUpdate.set((int) pos, value);
        break;
      }
      case REMOVED: {
        long pos = OVarIntSerializer.readAsLong(bytes);
        toUpdate.remove((int) pos);
        break;
      }
      }
    }
    long nestedChanges = OVarIntSerializer.readAsLong(bytes);
    while (nestedChanges-- > 0) {
      byte other = deserializeByte(bytes);
      assert other == CHANGED;
      long position = OVarIntSerializer.readAsLong(bytes);
      Object nested = toUpdate.get((int) position);
      OType type = readType(bytes);
      deserializeDeltaValue(bytes, type, nested);
    }
  }

  private void deserializeFullEntry(BytesContainer bytes, ODocument toFill) {
    String name = readString(bytes);
    OType type = readOType(bytes, false);
    Object value = deserializeValue(bytes, type, toFill);
    toFill.setProperty(name, value, type);
  }

  public void serializeDelta(BytesContainer bytes, ODocument document) {
    serializeClass(document, bytes);
    OClass oClass = ODocumentInternal.getImmutableSchemaClass(document);
    long count = ODocumentInternal.rawEntries(document).stream().filter((e) -> {
      ODocumentEntry entry = e.getValue();
      return entry.isCreated() || entry.isChanged() || entry.isTrackedModified() || !entry.exists();
    }).count();
    Set<Map.Entry<String, ODocumentEntry>> entries = ODocumentInternal.rawEntries(document);

    OVarIntSerializer.write(bytes, count);
    for (Map.Entry<String, ODocumentEntry> entry : entries) {
      ODocumentEntry docEntry = entry.getValue();
      if (!docEntry.exists()) {
        serializeByte(bytes, REMOVED);
        writeString(bytes, entry.getKey());
      } else if (docEntry.isCreated()) {
        serializeByte(bytes, CREATED);
        serializeFullEntry(bytes, oClass, entry.getKey(), docEntry);
      } else if (docEntry.isChanged()) {
        serializeByte(bytes, REPLACED);
        serializeFullEntry(bytes, oClass, entry.getKey(), docEntry);
      } else if (docEntry.isTrackedModified()) {
        serializeByte(bytes, CHANGED);
        serializeDeltaEntry(bytes, oClass, entry.getKey(), docEntry);
      } else {
        continue;
      }
    }
  }

  private void serializeDeltaEntry(BytesContainer bytes, OClass oClass, String name, ODocumentEntry entry) {
    final Object value = entry.value;
    if (value != null) {
      final OType type = getFieldType(entry);
      if (type == null) {
        throw new OSerializationException("Impossible serialize value of type " + value.getClass() + " with the delta serializer");
      }
      writeString(bytes, name);
      writeType(bytes, type);
      serializeDeltaValue(bytes, value, type, getLinkedType(oClass, type, name));
    }
  }

  private void serializeDeltaValue(BytesContainer bytes, Object value, OType type, OType linkedType) {
    switch (type) {
    case EMBEDDEDLIST:
      serializeDeltaEmbeddedList(bytes, (OTrackedList) value);
      break;
    case EMBEDDEDSET:
      serializeDeltaEmbeddedSet(bytes, (OTrackedSet) value);
      break;
    case EMBEDDEDMAP:
      serializeDeltaEmbeddedMap(bytes, (OTrackedMap) value);
      break;
    case EMBEDDED:
      serializeDelta(bytes, (ODocument) value);
      break;
    case LINKLIST:
      serializeDeltaLinkList(bytes, (ORecordLazyList) value);
      break;
    case LINKSET:
      serializeDeltaLinkSet(bytes, (ORecordLazySet) value);
      break;
    case LINKMAP:
      serializeDeltaLinkMap(bytes, (ORecordLazyMap) value);
      break;
    case LINKBAG:
      serializeDeltaLinkSet(bytes, (ORidBag) value);
      break;
    default:
      throw new OSerializationException("delta not supported for type:" + type);
    }
  }

  private void serializeDeltaLinkSet(BytesContainer bytes, OTrackedMultiValue<OIdentifiable, OIdentifiable> value) {
    OMultiValueChangeTimeLine<OIdentifiable, OIdentifiable> timeline = value.getTransactionTimeLine();
    assert timeline != null : "Collection timeline required for link* types serialization";
    OVarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
    for (OMultiValueChangeEvent<OIdentifiable, OIdentifiable> event : timeline.getMultiValueChangeEvents()) {
      switch (event.getChangeType()) {
      case ADD:
        serializeByte(bytes, CREATED);
        writeOptimizedLink(bytes, event.getValue());
        break;
      case UPDATE:
        throw new UnsupportedOperationException("update do not happen in sets, it will be like and add");
      case REMOVE:
        serializeByte(bytes, REMOVED);
        writeOptimizedLink(bytes, event.getOldValue());
        break;
      }
    }
  }

  private void serializeDeltaLinkList(BytesContainer bytes, ORecordLazyList value) {
    OMultiValueChangeTimeLine<Integer, OIdentifiable> timeline = value.getTransactionTimeLine();
    assert timeline != null : "Collection timeline required for link* types serialization";
    OVarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
    for (OMultiValueChangeEvent<Integer, OIdentifiable> event : timeline.getMultiValueChangeEvents()) {
      switch (event.getChangeType()) {
      case ADD:
        serializeByte(bytes, CREATED);
        writeOptimizedLink(bytes, event.getValue());
        break;
      case UPDATE:
        serializeByte(bytes, REPLACED);
        OVarIntSerializer.write(bytes, event.getKey().longValue());
        writeOptimizedLink(bytes, event.getValue());
        break;
      case REMOVE:
        serializeByte(bytes, REMOVED);
        writeOptimizedLink(bytes, event.getOldValue());
        break;
      }
    }
  }

  private void serializeDeltaLinkMap(BytesContainer bytes, ORecordLazyMap value) {
    OMultiValueChangeTimeLine<Object, OIdentifiable> timeline = value.getTransactionTimeLine();
    assert timeline != null : "Collection timeline required for link* types serialization";
    OVarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
    for (OMultiValueChangeEvent<Object, OIdentifiable> event : timeline.getMultiValueChangeEvents()) {
      switch (event.getChangeType()) {
      case ADD:
        serializeByte(bytes, CREATED);
        writeString(bytes, event.getKey().toString());
        writeOptimizedLink(bytes, event.getValue());
        break;
      case UPDATE:
        serializeByte(bytes, REPLACED);
        writeString(bytes, event.getKey().toString());
        writeOptimizedLink(bytes, event.getValue());
        break;
      case REMOVE:
        serializeByte(bytes, REMOVED);
        writeString(bytes, event.getKey().toString());
        break;
      }
    }
  }

  private void serializeDeltaEmbeddedMap(BytesContainer bytes, OTrackedMap value) {
    OMultiValueChangeTimeLine<Object, Object> timeline = value.getTransactionTimeLine();
    if (timeline != null) {
      OVarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
      for (OMultiValueChangeEvent<Object, Object> event : timeline.getMultiValueChangeEvents()) {
        switch (event.getChangeType()) {
        case ADD: {
          serializeByte(bytes, CREATED);
          writeString(bytes, event.getKey().toString());
          OType type = OType.getTypeByValue(event.getValue());
          writeType(bytes, type);
          serializeValue(bytes, event.getValue(), type, null);
          break;
        }
        case UPDATE: {
          serializeByte(bytes, REPLACED);
          writeString(bytes, event.getKey().toString());
          OType type = OType.getTypeByValue(event.getValue());
          writeType(bytes, type);
          serializeValue(bytes, event.getValue(), type, null);
          break;
        }
        case REMOVE:
          serializeByte(bytes, REMOVED);
          writeString(bytes, event.getKey().toString());
          break;
        }
      }
    } else {
      OVarIntSerializer.write(bytes, 0);
    }
    long count = value.values().stream().filter((v) -> {
      return v instanceof OTrackedMultiValue && ((OTrackedMultiValue) v).isModified() || v instanceof ODocument && ((ODocument) v)
          .isEmbedded() && ((ODocument) v).isDirty();
    }).count();
    OVarIntSerializer.write(bytes, count);
    Iterator<Map.Entry<Object, Object>> iterator = value.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<Object, Object> singleEntry = iterator.next();
      Object singleValue = singleEntry.getValue();
      if (singleValue instanceof OTrackedMultiValue && ((OTrackedMultiValue) singleValue).isModified()) {
        serializeByte(bytes, CHANGED);
        writeString(bytes, singleEntry.getKey().toString());
        OType type = OType.getTypeByValue(singleValue);
        writeType(bytes, type);
        serializeDeltaValue(bytes, singleValue, type, null);
      } else if (singleValue instanceof ODocument && ((ODocument) singleValue).isEmbedded() && ((ODocument) singleValue)
          .isDirty()) {
        serializeByte(bytes, CHANGED);
        writeString(bytes, singleEntry.getKey().toString());
        OType type = OType.getTypeByValue(singleValue);
        writeType(bytes, type);
        serializeDeltaValue(bytes, singleValue, type, null);
      }
    }
  }

  private void serializeDeltaEmbeddedList(BytesContainer bytes, OTrackedList value) {
    OMultiValueChangeTimeLine<Integer, Object> timeline = value.getTransactionTimeLine();
    if (timeline != null) {
      OVarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
      for (OMultiValueChangeEvent<Integer, Object> event : timeline.getMultiValueChangeEvents()) {
        switch (event.getChangeType()) {
        case ADD: {
          serializeByte(bytes, CREATED);
          OType type = OType.getTypeByValue(event.getValue());
          writeType(bytes, type);
          serializeValue(bytes, event.getValue(), type, null);
          break;
        }
        case UPDATE: {
          serializeByte(bytes, REPLACED);
          OVarIntSerializer.write(bytes, event.getKey().longValue());
          OType type = OType.getTypeByValue(event.getValue());
          writeType(bytes, type);
          serializeValue(bytes, event.getValue(), type, null);
          break;
        }
        case REMOVE: {
          serializeByte(bytes, REMOVED);
          OVarIntSerializer.write(bytes, event.getKey().longValue());
          break;
        }
        }
      }
    } else {
      OVarIntSerializer.write(bytes, 0);
    }
    long count = value.stream().filter((v) -> {
      return v instanceof OTrackedMultiValue && ((OTrackedMultiValue) v).isModified() || v instanceof ODocument && ((ODocument) v)
          .isEmbedded() && ((ODocument) v).isDirty();
    }).count();
    OVarIntSerializer.write(bytes, count);
    for (int i = 0; i < value.size(); i++) {
      Object singleValue = value.get(i);
      if (singleValue instanceof OTrackedMultiValue && ((OTrackedMultiValue) singleValue).isModified()) {
        serializeByte(bytes, CHANGED);
        OVarIntSerializer.write(bytes, i);
        OType type = OType.getTypeByValue(singleValue);
        writeType(bytes, type);
        serializeDeltaValue(bytes, singleValue, type, null);
      } else if (singleValue instanceof ODocument && ((ODocument) singleValue).isEmbedded() && ((ODocument) singleValue)
          .isDirty()) {
        serializeByte(bytes, CHANGED);
        OVarIntSerializer.write(bytes, i);
        OType type = OType.getTypeByValue(singleValue);
        writeType(bytes, type);
        serializeDeltaValue(bytes, singleValue, type, null);
      }
    }
  }

  private void serializeDeltaEmbeddedSet(BytesContainer bytes, OTrackedSet value) {
    OMultiValueChangeTimeLine<Object, Object> timeline = value.getTransactionTimeLine();
    if (timeline != null) {
      OVarIntSerializer.write(bytes, timeline.getMultiValueChangeEvents().size());
      for (OMultiValueChangeEvent<Object, Object> event : timeline.getMultiValueChangeEvents()) {
        switch (event.getChangeType()) {
        case ADD: {
          serializeByte(bytes, CREATED);
          OType type = OType.getTypeByValue(event.getValue());
          writeType(bytes, type);
          serializeValue(bytes, event.getValue(), type, null);
          break;
        }
        case UPDATE:
          throw new UnsupportedOperationException("update do not happen in sets, it will be like and add");
        case REMOVE: {
          serializeByte(bytes, REMOVED);
          OType type = OType.getTypeByValue(event.getOldValue());
          writeType(bytes, type);
          serializeValue(bytes, event.getOldValue(), type, null);
          break;
        }
        }
      }
    } else {
      OVarIntSerializer.write(bytes, 0);
    }
    long count = value.stream().filter((v) -> {
      return v instanceof OTrackedMultiValue && ((OTrackedMultiValue) v).isModified() || v instanceof ODocument && ((ODocument) v)
          .isEmbedded() && ((ODocument) v).isDirty();
    }).count();
    OVarIntSerializer.write(bytes, count);
    int i = 0;
    Iterator<Object> iterator = value.iterator();
    while (iterator.hasNext()) {
      Object singleValue = iterator.next();
      if (singleValue instanceof OTrackedMultiValue && ((OTrackedMultiValue) singleValue).isModified()) {
        serializeByte(bytes, CHANGED);
        OVarIntSerializer.write(bytes, i);
        OType type = OType.getTypeByValue(singleValue);
        writeType(bytes, type);
        serializeDeltaValue(bytes, singleValue, type, null);
      } else if (singleValue instanceof ODocument && ((ODocument) singleValue).isEmbedded() && ((ODocument) singleValue)
          .isDirty()) {
        serializeByte(bytes, CHANGED);
        OVarIntSerializer.write(bytes, i);
        OType type = OType.getTypeByValue(singleValue);
        writeType(bytes, type);
        serializeDeltaValue(bytes, singleValue, type, null);
      }
      i++;
    }
  }

  protected OType getFieldType(final ODocumentEntry entry) {
    OType type = entry.type;
    if (type == null) {
      final OProperty prop = entry.property;
      if (prop != null)
        type = prop.getType();

    }
    if (type == null || OType.ANY == type)
      type = OType.getTypeByValue(entry.value);
    return type;
  }

  private void serializeFullEntry(BytesContainer bytes, OClass oClass, String name, ODocumentEntry entry) {
    final Object value = entry.value;
    if (value != null) {
      final OType type = getFieldType(entry);
      if (type == null) {
        throw new OSerializationException("Impossible serialize value of type " + value.getClass() + " with the delta serializer");
      }
      writeString(bytes, name);
      writeType(bytes, type);
      serializeValue(bytes, value, type, getLinkedType(oClass, type, name));
    }
  }

  private byte deserializeByte(BytesContainer bytes) {
    int pos = bytes.offset;
    bytes.skip(1);
    return bytes.bytes[pos];
  }

  private void serializeByte(BytesContainer bytes, byte value) {
    int pointer = bytes.alloc(1);
    bytes.bytes[pointer] = value;
  }

  public void serializeValue(final BytesContainer bytes, Object value, final OType type, final OType linkedType) {
    int pointer = 0;
    switch (type) {
    case INTEGER:
    case LONG:
    case SHORT:
      OVarIntSerializer.write(bytes, ((Number) value).longValue());
      break;
    case STRING:
      writeString(bytes, value.toString());
      break;
    case DOUBLE:
      long dg = Double.doubleToLongBits((Double) value);
      pointer = bytes.alloc(OLongSerializer.LONG_SIZE);
      OLongSerializer.INSTANCE.serializeLiteral(dg, bytes.bytes, pointer);
      break;
    case FLOAT:
      int fg = Float.floatToIntBits((Float) value);
      pointer = bytes.alloc(OIntegerSerializer.INT_SIZE);
      OIntegerSerializer.INSTANCE.serializeLiteral(fg, bytes.bytes, pointer);
      break;
    case BYTE:
      pointer = bytes.alloc(1);
      bytes.bytes[pointer] = (Byte) value;
      break;
    case BOOLEAN:
      pointer = bytes.alloc(1);
      bytes.bytes[pointer] = ((Boolean) value) ? (byte) 1 : (byte) 0;
      break;
    case DATETIME:
      if (value instanceof Long) {
        OVarIntSerializer.write(bytes, (Long) value);
      } else
        OVarIntSerializer.write(bytes, ((Date) value).getTime());
      break;
    case DATE:
      long dateValue;
      if (value instanceof Long) {
        dateValue = (Long) value;
      } else
        dateValue = ((Date) value).getTime();
      dateValue = convertDayToTimezone(ODateHelper.getDatabaseTimeZone(), TimeZone.getTimeZone("GMT"), dateValue);
      OVarIntSerializer.write(bytes, dateValue / MILLISEC_PER_DAY);
      break;
    case EMBEDDED:
      if (value instanceof ODocumentSerializable) {
        ODocument cur = ((ODocumentSerializable) value).toDocument();
        cur.field(ODocumentSerializable.CLASS_NAME, value.getClass().getName());
        serializeDelta(bytes, cur);
      } else {
        serializeDelta(bytes, (ODocument) value);
      }
      break;
    case EMBEDDEDSET:
    case EMBEDDEDLIST:
      if (value.getClass().isArray())
        writeEmbeddedCollection(bytes, Arrays.asList(OMultiValue.array(value)), linkedType);
      else
        writeEmbeddedCollection(bytes, (Collection<?>) value, linkedType);
      break;
    case DECIMAL:
      BigDecimal decimalValue = (BigDecimal) value;
      pointer = bytes.alloc(ODecimalSerializer.INSTANCE.getObjectSize(decimalValue));
      ODecimalSerializer.INSTANCE.serialize(decimalValue, bytes.bytes, pointer);
      break;
    case BINARY:
      pointer = writeBinary(bytes, (byte[]) (value));
      break;
    case LINKSET:
    case LINKLIST:
      Collection<OIdentifiable> ridCollection = (Collection<OIdentifiable>) value;
      writeLinkCollection(bytes, ridCollection);
      break;
    case LINK:
      if (!(value instanceof OIdentifiable))
        throw new OValidationException("Value '" + value + "' is not a OIdentifiable");

      writeOptimizedLink(bytes, (OIdentifiable) value);
      break;
    case LINKMAP:
      writeLinkMap(bytes, (Map<Object, OIdentifiable>) value);
      break;
    case EMBEDDEDMAP:
      writeEmbeddedMap(bytes, (Map<Object, Object>) value);
      break;
    case LINKBAG:
      writeRidBag(bytes, (ORidBag) value);
      break;
    case CUSTOM:
      if (!(value instanceof OSerializableStream))
        value = new OSerializableWrapper((Serializable) value);
      writeString(bytes, value.getClass().getName());
      writeBinary(bytes, ((OSerializableStream) value).toStream());
      break;
    case TRANSIENT:
      break;
    case ANY:
      break;
    }
  }

  private int writeEmbeddedCollection(final BytesContainer bytes, final Collection<?> value, final OType linkedType) {
    final int pos = OVarIntSerializer.write(bytes, value.size());
    // TODO manage embedded type from schema and auto-determined.
    for (Object itemValue : value) {
      // TODO:manage in a better way null entry
      if (itemValue == null) {
        writeType(bytes, null);
        continue;
      }
      OType type;
      if (linkedType == null)
        type = getTypeFromValueEmbedded(itemValue);
      else
        type = linkedType;
      if (type != null) {
        writeType(bytes, type);
        serializeValue(bytes, itemValue, type, null);
      } else {
        throw new OSerializationException(
            "Impossible serialize value of type " + value.getClass() + " with the ODocument binary serializer");
      }
    }
    return pos;
  }

  private int writeEmbeddedMap(BytesContainer bytes, Map<Object, Object> map) {
    final int fullPos = OVarIntSerializer.write(bytes, map.size());
    for (Map.Entry<Object, Object> entry : map.entrySet()) {
      writeString(bytes, entry.getKey().toString());
      final Object value = entry.getValue();
      if (value != null) {
        final OType type = getTypeFromValueEmbedded(value);
        if (type == null) {
          throw new OSerializationException(
              "Impossible serialize value of type " + value.getClass() + " with the Result binary serializer");
        }
        writeType(bytes, type);
        serializeValue(bytes, value, type, null);
      } else {
        writeType(bytes, null);
      }
    }
    return fullPos;
  }

  public Object deserializeValue(BytesContainer bytes, OType type, ORecordElement owner) {
    Object value = null;
    switch (type) {
    case INTEGER:
      value = OVarIntSerializer.readAsInteger(bytes);
      break;
    case LONG:
      value = OVarIntSerializer.readAsLong(bytes);
      break;
    case SHORT:
      value = OVarIntSerializer.readAsShort(bytes);
      break;
    case STRING:
      value = readString(bytes);
      break;
    case DOUBLE:
      value = Double.longBitsToDouble(readLong(bytes));
      break;
    case FLOAT:
      value = Float.intBitsToFloat(readInteger(bytes));
      break;
    case BYTE:
      value = readByte(bytes);
      break;
    case BOOLEAN:
      value = readByte(bytes) == 1;
      break;
    case DATETIME:
      value = new Date(OVarIntSerializer.readAsLong(bytes));
      break;
    case DATE:
      long savedTime = OVarIntSerializer.readAsLong(bytes) * MILLISEC_PER_DAY;
      savedTime = convertDayToTimezone(TimeZone.getTimeZone("GMT"), ODateHelper.getDatabaseTimeZone(), savedTime);
      value = new Date(savedTime);
      break;
    case EMBEDDED:
      value = new ODocumentEmbedded();
      deserializeDelta(bytes, (ODocument) value);
      if (((ODocument) value).containsField(ODocumentSerializable.CLASS_NAME)) {
        String className = ((ODocument) value).field(ODocumentSerializable.CLASS_NAME);
        try {
          Class<?> clazz = Class.forName(className);
          ODocumentSerializable newValue = (ODocumentSerializable) clazz.newInstance();
          newValue.fromDocument((ODocument) value);
          value = newValue;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      } else
        ODocumentInternal.addOwner((ODocument) value, owner);

      break;
    case EMBEDDEDSET:
      value = readEmbeddedSet(bytes, owner);
      break;
    case EMBEDDEDLIST:
      value = readEmbeddedList(bytes, owner);
      break;
    case LINKSET:
      value = readLinkSet(bytes, owner);
      break;
    case LINKLIST:
      value = readLinkList(bytes, owner);
      break;
    case BINARY:
      value = readBinary(bytes);
      break;
    case LINK:
      value = readOptimizedLink(bytes, false);
      break;
    case LINKMAP:
      value = readLinkMap(bytes, owner);
      break;
    case EMBEDDEDMAP:
      value = readEmbeddedMap(bytes, owner);
      break;
    case DECIMAL:
      value = ODecimalSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
      bytes.skip(ODecimalSerializer.INSTANCE.getObjectSize(bytes.bytes, bytes.offset));
      break;
    case LINKBAG:
      ORidBag bag = readRidBag(bytes);
      bag.setOwner(owner);
      value = bag;
      break;
    case TRANSIENT:
      break;
    case CUSTOM:
      try {
        String className = readString(bytes);
        Class<?> clazz = Class.forName(className);
        OSerializableStream stream = (OSerializableStream) clazz.newInstance();
        stream.fromStream(readBinary(bytes));
        if (stream instanceof OSerializableWrapper)
          value = ((OSerializableWrapper) stream).getSerializable();
        else
          value = stream;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      break;
    case ANY:
      break;

    }
    return value;
  }

  private Collection<?> readEmbeddedList(final BytesContainer bytes, final ORecordElement owner) {
    OTrackedList<Object> found = new OTrackedList<>(owner);
    final int items = OVarIntSerializer.readAsInteger(bytes);
    for (int i = 0; i < items; i++) {
      OType itemType = readOType(bytes, false);
      if (itemType == null)
        found.addInternal(null);
      else
        found.addInternal(deserializeValue(bytes, itemType, found));
    }
    return found;
  }

  private Collection<?> readEmbeddedSet(final BytesContainer bytes, final ORecordElement owner) {
    OTrackedSet<Object> found = new OTrackedSet<>(owner);
    final int items = OVarIntSerializer.readAsInteger(bytes);
    for (int i = 0; i < items; i++) {
      OType itemType = readOType(bytes, false);
      if (itemType == null)
        found.addInternal(null);
      else
        found.addInternal(deserializeValue(bytes, itemType, found));
    }
    return found;
  }

  private Collection<OIdentifiable> readLinkList(BytesContainer bytes, ORecordElement owner) {
    ORecordLazyList found = new ORecordLazyList(owner);
    final int items = OVarIntSerializer.readAsInteger(bytes);
    for (int i = 0; i < items; i++) {
      OIdentifiable id = readOptimizedLink(bytes, false);
      if (id.equals(NULL_RECORD_ID))
        found.addInternal(null);
      else
        found.addInternal(id);
    }
    return found;
  }

  private Collection<OIdentifiable> readLinkSet(BytesContainer bytes, ORecordElement owner) {
    ORecordLazySet found = new ORecordLazySet(owner);
    final int items = OVarIntSerializer.readAsInteger(bytes);
    for (int i = 0; i < items; i++) {
      OIdentifiable id = readOptimizedLink(bytes, false);
      if (id.equals(NULL_RECORD_ID))
        found.addInternal(null);
      else
        found.addInternal(id);
    }
    return found;
  }

  private Map<Object, OIdentifiable> readLinkMap(final BytesContainer bytes, final ORecordElement owner) {
    int size = OVarIntSerializer.readAsInteger(bytes);
    ORecordLazyMap result = new ORecordLazyMap(owner);
    while ((size--) > 0) {
      OType keyType = readOType(bytes, false);
      Object key = deserializeValue(bytes, keyType, result);
      OIdentifiable value = readOptimizedLink(bytes, false);
      if (value.equals(NULL_RECORD_ID))
        result.putInternal(key, null);
      else
        result.putInternal(key, value);
    }

    return result;
  }

  private Object readEmbeddedMap(final BytesContainer bytes, final ORecordElement owner) {
    int size = OVarIntSerializer.readAsInteger(bytes);
    final OTrackedMap result = new OTrackedMap<Object>(owner);
    while ((size--) > 0) {
      String key = readString(bytes);
      OType valType = readOType(bytes, false);
      Object value = null;
      if (valType != null) {
        value = deserializeValue(bytes, valType, result);
      }
      result.putInternal(key, value);
    }
    return result;
  }

  private ORidBag readRidBag(BytesContainer bytes) {
    UUID uuid = OUUIDSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
    bytes.skip(OUUIDSerializer.UUID_SIZE);
    if (uuid.getMostSignificantBits() == -1 && uuid.getLeastSignificantBits() == -1)
      uuid = null;
    byte b = bytes.bytes[bytes.offset];
    bytes.skip(1);
    if (b == 1) {
      ORidBag bag = new ORidBag(uuid);
      int size = OVarIntSerializer.readAsInteger(bytes);
      for (int i = 0; i < size; i++) {
        OIdentifiable id = readOptimizedLink(bytes, false);
        if (id.equals(NULL_RECORD_ID))
          bag.add(null);
        else
          bag.add(id);
      }
      return bag;
    } else {
      long fileId = OVarIntSerializer.readAsLong(bytes);
      long pageIndex = OVarIntSerializer.readAsLong(bytes);
      int pageOffset = OVarIntSerializer.readAsInteger(bytes);
      int bagSize = OVarIntSerializer.readAsInteger(bytes);

      Map<OIdentifiable, Change> changes = new HashMap<>();
      int size = OVarIntSerializer.readAsInteger(bytes);
      while (size-- > 0) {
        OIdentifiable link = readOptimizedLink(bytes, false);
        byte type = bytes.bytes[bytes.offset];
        bytes.skip(1);
        int change = OVarIntSerializer.readAsInteger(bytes);
        changes.put(link, ChangeSerializationHelper.createChangeInstance(type, change));
      }

      OBonsaiCollectionPointer pointer = null;
      if (fileId != -1)
        pointer = new OBonsaiCollectionPointer(fileId, new OBonsaiBucketPointer(pageIndex, pageOffset));
      return new ORidBag(pointer, changes, uuid);
    }
  }

  private void writeRidBag(BytesContainer bytes, ORidBag bag) {
    final OSBTreeCollectionManager sbTreeCollectionManager = ODatabaseRecordThreadLocal.instance().get()
        .getSbTreeCollectionManager();
    UUID uuid = null;
    if (sbTreeCollectionManager != null)
      uuid = sbTreeCollectionManager.listenForChanges(bag);
    if (uuid == null)
      uuid = new UUID(-1, -1);
    int uuidPos = bytes.alloc(OUUIDSerializer.UUID_SIZE);
    OUUIDSerializer.INSTANCE.serialize(uuid, bytes.bytes, uuidPos);
    if (bag.isEmbedded() || OGlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.getValueAsInteger() >= bag.size()) {
      int pos = bytes.alloc(1);
      bytes.bytes[pos] = 1;
      OVarIntSerializer.write(bytes, bag.size());
      Iterator<OIdentifiable> iterator = bag.rawIterator();
      while (iterator.hasNext()) {
        OIdentifiable itemValue = iterator.next();
        if (itemValue == null)
          writeNullLink(bytes);
        else
          writeOptimizedLink(bytes, itemValue);
      }
    } else {
      int pos = bytes.alloc(1);
      bytes.bytes[pos] = 2;
      OBonsaiCollectionPointer pointer = bag.getPointer();
      if (pointer == null)
        pointer = OBonsaiCollectionPointer.INVALID;
      OVarIntSerializer.write(bytes, pointer.getFileId());
      OVarIntSerializer.write(bytes, pointer.getRootPointer().getPageIndex());
      OVarIntSerializer.write(bytes, pointer.getRootPointer().getPageOffset());
      OVarIntSerializer.write(bytes, bag.size());
      NavigableMap<OIdentifiable, Change> changes = bag.getChanges();
      if (changes != null) {
        OVarIntSerializer.write(bytes, changes.size());
        for (Map.Entry<OIdentifiable, Change> change : changes.entrySet()) {
          writeOptimizedLink(bytes, change.getKey());
          int posAll = bytes.alloc(1);
          bytes.bytes[posAll] = change.getValue().getType();
          OVarIntSerializer.write(bytes, change.getValue().getValue());
        }
      } else {
        OVarIntSerializer.write(bytes, 0);
      }
    }
  }

}
