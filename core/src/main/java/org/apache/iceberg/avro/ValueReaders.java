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
package org.apache.iceberg.avro;

import static java.util.Collections.emptyIterator;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.ResolvingDecoder;
import org.apache.avro.util.Utf8;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.common.DynConstructors;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.Pair;
import org.apache.iceberg.util.UUIDUtil;
import org.apache.iceberg.variants.Variant;
import org.apache.iceberg.variants.VariantMetadata;
import org.apache.iceberg.variants.VariantValue;

public class ValueReaders {
  private ValueReaders() {}

  public static ValueReader<Object> nulls() {
    return NullReader.INSTANCE;
  }

  public static <T> ValueReader<T> constant(T value) {
    return new ConstantReader<>(value);
  }

  public static <T> ValueReader<T> replaceWithConstant(ValueReader<?> reader, T value) {
    return new ReplaceWithConstantReader<>(reader, value);
  }

  public static ValueReader<Boolean> booleans() {
    return BooleanReader.INSTANCE;
  }

  public static ValueReader<Integer> ints() {
    return IntegerReader.INSTANCE;
  }

  public static ValueReader<Long> intsAsLongs() {
    return IntegerAsLongReader.INSTANCE;
  }

  public static ValueReader<Long> longs() {
    return LongReader.INSTANCE;
  }

  public static ValueReader<Float> floats() {
    return FloatReader.INSTANCE;
  }

  public static ValueReader<Double> floatsAsDoubles() {
    return FloatAsDoubleReader.INSTANCE;
  }

  public static ValueReader<Double> doubles() {
    return DoubleReader.INSTANCE;
  }

  public static ValueReader<String> strings() {
    return StringReader.INSTANCE;
  }

  public static ValueReader<Utf8> utf8s() {
    return Utf8Reader.INSTANCE;
  }

  public static ValueReader<String> enums(List<String> symbols) {
    return new EnumReader(symbols);
  }

  public static ValueReader<UUID> uuids() {
    return UUIDReader.INSTANCE;
  }

  public static ValueReader<byte[]> fixed(int length) {
    return new FixedReader(length);
  }

  public static ValueReader<GenericData.Fixed> fixed(Schema schema) {
    return new GenericFixedReader(schema);
  }

  public static ValueReader<byte[]> bytes() {
    return BytesReader.INSTANCE;
  }

  public static ValueReader<ByteBuffer> byteBuffers() {
    return ByteBufferReader.INSTANCE;
  }

  public static ValueReader<BigDecimal> decimal(ValueReader<byte[]> unscaledReader, int scale) {
    return new DecimalReader(unscaledReader, scale);
  }

  public static ValueReader<byte[]> decimalBytesReader(Schema schema) {
    switch (schema.getType()) {
      case FIXED:
        return ValueReaders.fixed(schema.getFixedSize());
      case BYTES:
        return ValueReaders.bytes();
      default:
        throw new IllegalArgumentException(
            "Invalid primitive type for decimal: " + schema.getType());
    }
  }

  public static ValueReader<Variant> variants() {
    return VariantReader.INSTANCE;
  }

  public static ValueReader<Object> union(List<ValueReader<?>> readers) {
    return new UnionReader(readers);
  }

  public static ValueReader<Long> positions() {
    return new PositionReader();
  }

  public static <T> ValueReader<Collection<T>> array(ValueReader<T> elementReader) {
    return new ArrayReader<>(elementReader);
  }

  public static <K, V> ValueReader<Map<K, V>> arrayMap(
      ValueReader<K> keyReader, ValueReader<V> valueReader) {
    return new ArrayMapReader<>(keyReader, valueReader);
  }

  public static <K, V> ValueReader<Map<K, V>> map(
      ValueReader<K> keyReader, ValueReader<V> valueReader) {
    return new MapReader<>(keyReader, valueReader);
  }

  public static ValueReader<GenericData.Record> record(
      List<ValueReader<?>> readers, Schema recordSchema) {
    return new RecordReader(readers, recordSchema);
  }

  public static <R extends IndexedRecord> ValueReader<R> record(
      List<ValueReader<?>> readers, Class<R> recordClass, Schema recordSchema) {
    return new IndexedRecordReader<>(readers, recordClass, recordSchema);
  }

  public static ValueReader<?> record(
      Schema recordSchema, List<Pair<Integer, ValueReader<?>>> readPlan) {
    return new PlannedRecordReader(recordSchema, readPlan);
  }

  public static <R extends IndexedRecord> ValueReader<R> record(
      Schema recordSchema, Class<R> recordClass, List<Pair<Integer, ValueReader<?>>> readPlan) {
    return new PlannedIndexedReader<>(recordSchema, recordClass, readPlan);
  }

  public static ValueReader<Void> skipStruct(List<ValueReader<?>> readers) {
    return new SkipStructReader(readers);
  }

  /**
   * Builds a read plan for record classes that use planned reads instead of a ResolvingDecoder.
   *
   * @param expected expected StructType
   * @param record Avro record schema
   * @param fieldReaders list of readers for each field in the Avro record schema
   * @param idToConstant a map of field ID to constants values
   * @return a read plan that is a list of (position, reader) pairs
   */
  public static List<Pair<Integer, ValueReader<?>>> buildReadPlan(
      Types.StructType expected,
      Schema record,
      List<ValueReader<?>> fieldReaders,
      Map<Integer, ?> idToConstant) {
    return buildReadPlan(expected, record, fieldReaders, idToConstant, (type, value) -> value);
  }

  /**
   * Builds a read plan for record classes that use planned reads instead of a ResolvingDecoder.
   *
   * @param expected expected StructType
   * @param record Avro record schema
   * @param fieldReaders list of readers for each field in the Avro record schema
   * @param idToConstant a map of field ID to constants values
   * @param convert function to convert from internal classes to the target object model
   * @return a read plan that is a list of (position, reader) pairs
   */
  public static List<Pair<Integer, ValueReader<?>>> buildReadPlan(
      Types.StructType expected,
      Schema record,
      List<ValueReader<?>> fieldReaders,
      Map<Integer, ?> idToConstant,
      BiFunction<Type, Object, Object> convert) {
    Map<Integer, Integer> idToPos = idToPos(expected);
    List<Pair<Integer, ValueReader<?>>> readPlan = Lists.newArrayList();
    addFileFieldReadersToPlan(readPlan, record.getFields(), fieldReaders, idToPos, idToConstant);
    addMissingFileReadersToPlan(readPlan, idToPos, expected, idToConstant, convert);
    return readPlan;
  }

  private static void addFileFieldReadersToPlan(
      List<Pair<Integer, ValueReader<?>>> readPlan,
      List<Schema.Field> fileFields,
      List<ValueReader<?>> fieldReaders,
      Map<Integer, Integer> idToPos,
      Map<Integer, ?> idToConstant) {
    for (int pos = 0; pos < fileFields.size(); pos++) {
      Schema.Field field = fileFields.get(pos);
      ValueReader<?> fieldReader = fieldReaders.get(pos);
      Integer fieldId = AvroSchemaUtil.fieldId(field);
      Integer projectionPos = idToPos.remove(fieldId);
      readPlan.add(fileFieldReader(fieldId, projectionPos, fieldReader, idToConstant));
    }
  }

  private static Pair<Integer, ValueReader<?>> fileFieldReader(
      Integer fieldId,
      Integer projectionPos,
      ValueReader<?> fieldReader,
      Map<Integer, ?> idToConstant) {
    if (Objects.equals(fieldId, MetadataColumns.ROW_ID.fieldId())) {
      Long firstRowId = (Long) idToConstant.get(fieldId);
      return Pair.of(projectionPos, ValueReaders.rowIds(firstRowId, fieldReader));
    } else if (Objects.equals(fieldId, MetadataColumns.LAST_UPDATED_SEQUENCE_NUMBER.fieldId())) {
      Long firstRowId = (Long) idToConstant.get(MetadataColumns.ROW_ID.fieldId());
      Long fileSeqNumber = (Long) idToConstant.get(fieldId);
      return Pair.of(
          projectionPos, ValueReaders.lastUpdated(firstRowId, fileSeqNumber, fieldReader));
    } else {
      return fieldReader(fieldId, projectionPos, fieldReader, idToConstant);
    }
  }

  private static Pair<Integer, ValueReader<?>> fieldReader(
      Integer fieldId,
      Integer projectionPos,
      ValueReader<?> fieldReader,
      Map<Integer, ?> idToConstant) {
    Object constant = idToConstant.get(fieldId);
    if (projectionPos != null && constant != null) {
      return Pair.of(projectionPos, ValueReaders.replaceWithConstant(fieldReader, constant));
    }

    return Pair.of(projectionPos, fieldReader);
  }

  // Handles expected fields not found in data file
  private static void addMissingFileReadersToPlan(
      List<Pair<Integer, ValueReader<?>>> readPlan,
      Map<Integer, Integer> idToPos,
      Types.StructType expected,
      Map<Integer, ?> idToConstant,
      BiFunction<Type, Object, Object> convert) {
    for (Map.Entry<Integer, Integer> idAndPos : idToPos.entrySet()) {
      int fieldId = idAndPos.getKey();
      int pos = idAndPos.getValue();
      readPlan.add(
          Pair.of(pos, createMissingFieldReader(fieldId, expected, idToConstant, convert)));
    }
  }

  // Creates reader for missing field based on default/constant rules
  private static ValueReader<?> createMissingFieldReader(
      int fieldId,
      Types.StructType expected,
      Map<Integer, ?> idToConstant,
      BiFunction<Type, Object, Object> convert) {
    Object constant = idToConstant.get(fieldId);
    Types.NestedField field = expected.field(fieldId);

    if (constant != null) {
      return ValueReaders.constant(constant);
    } else if (field.initialDefault() != null) {
      return ValueReaders.constant(convert.apply(field.type(), field.initialDefault()));
    } else if (fieldId == MetadataColumns.IS_DELETED.fieldId()) {
      return ValueReaders.constant(false);
    } else if (fieldId == MetadataColumns.ROW_POSITION.fieldId()) {
      return ValueReaders.positions();
    } else if (field.isOptional()) {
      return ValueReaders.constant(null);
    }

    throw new IllegalArgumentException(String.format("Missing required field: %s", field.name()));
  }

  public static ValueReader<Long> rowIds(Long baseRowId, ValueReader<?> idReader) {
    if (baseRowId != null) {
      return new RowIdReader(baseRowId, (ValueReader<Long>) idReader);
    } else {
      return ValueReaders.constant(null);
    }
  }

  public static ValueReader<Long> lastUpdated(
      Long baseRowId, Long fileSeqNumber, ValueReader<?> seqReader) {
    if (fileSeqNumber != null && baseRowId != null) {
      return new LastUpdatedSeqReader(fileSeqNumber, (ValueReader<Long>) seqReader);
    } else {
      return ValueReaders.constant(null);
    }
  }

  private static Map<Integer, Integer> idToPos(Types.StructType struct) {
    Map<Integer, Integer> idToPos = Maps.newHashMap();

    if (struct != null) {
      List<Types.NestedField> fields = struct.fields();
      for (int pos = 0; pos < fields.size(); pos += 1) {
        Types.NestedField field = fields.get(pos);
        idToPos.put(field.fieldId(), pos);
      }
    }

    return idToPos;
  }

  private static class NullReader implements ValueReader<Object> {
    private static final NullReader INSTANCE = new NullReader();

    private NullReader() {}

    @Override
    public Object read(Decoder decoder, Object ignored) throws IOException {
      decoder.readNull();
      return null;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.readNull();
    }
  }

  private static class ConstantReader<T> implements ValueReader<T> {
    private final T constant;

    private ConstantReader(T constant) {
      this.constant = constant;
    }

    @Override
    public T read(Decoder decoder, Object reuse) throws IOException {
      return constant;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {}
  }

  private static class ReplaceWithConstantReader<T> extends ConstantReader<T> {
    private final ValueReader<?> replaced;

    private ReplaceWithConstantReader(ValueReader<?> replaced, T constant) {
      super(constant);
      this.replaced = replaced;
    }

    @Override
    public T read(Decoder decoder, Object reuse) throws IOException {
      replaced.read(decoder, reuse);
      return super.read(decoder, reuse);
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      replaced.skip(decoder);
    }
  }

  private static class BooleanReader implements ValueReader<Boolean> {
    private static final BooleanReader INSTANCE = new BooleanReader();

    private BooleanReader() {}

    @Override
    public Boolean read(Decoder decoder, Object ignored) throws IOException {
      return decoder.readBoolean();
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.readBoolean();
    }
  }

  private static class IntegerReader implements ValueReader<Integer> {
    private static final IntegerReader INSTANCE = new IntegerReader();

    private IntegerReader() {}

    @Override
    public Integer read(Decoder decoder, Object ignored) throws IOException {
      return decoder.readInt();
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.readInt();
    }
  }

  private static class IntegerAsLongReader implements ValueReader<Long> {
    private static final IntegerAsLongReader INSTANCE = new IntegerAsLongReader();

    private IntegerAsLongReader() {}

    @Override
    public Long read(Decoder decoder, Object ignored) throws IOException {
      return (long) decoder.readInt();
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.readInt();
    }
  }

  private static class LongReader implements ValueReader<Long> {
    private static final LongReader INSTANCE = new LongReader();

    private LongReader() {}

    @Override
    public Long read(Decoder decoder, Object ignored) throws IOException {
      return decoder.readLong();
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.readLong();
    }
  }

  private static class FloatReader implements ValueReader<Float> {
    private static final FloatReader INSTANCE = new FloatReader();

    private FloatReader() {}

    @Override
    public Float read(Decoder decoder, Object ignored) throws IOException {
      return decoder.readFloat();
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.skipFixed(4);
    }
  }

  private static class FloatAsDoubleReader implements ValueReader<Double> {
    private static final FloatAsDoubleReader INSTANCE = new FloatAsDoubleReader();

    private FloatAsDoubleReader() {}

    @Override
    public Double read(Decoder decoder, Object ignored) throws IOException {
      return (double) decoder.readFloat();
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.skipFixed(4);
    }
  }

  private static class DoubleReader implements ValueReader<Double> {
    private static final DoubleReader INSTANCE = new DoubleReader();

    private DoubleReader() {}

    @Override
    public Double read(Decoder decoder, Object ignored) throws IOException {
      return decoder.readDouble();
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.skipFixed(8);
    }
  }

  private static class StringReader implements ValueReader<String> {
    private static final StringReader INSTANCE = new StringReader();
    private final ThreadLocal<Utf8> reusedTempUtf8 = ThreadLocal.withInitial(Utf8::new);

    private StringReader() {}

    @Override
    public String read(Decoder decoder, Object ignored) throws IOException {
      // use the decoder's readString(Utf8) method because it may be a resolving decoder
      this.reusedTempUtf8.set(decoder.readString(reusedTempUtf8.get()));
      return reusedTempUtf8.get().toString();
      //      int length = decoder.readInt();
      //      byte[] bytes = new byte[length];
      //      decoder.readFixed(bytes, 0, length);
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.skipString();
    }
  }

  private static class Utf8Reader implements ValueReader<Utf8> {
    private static final Utf8Reader INSTANCE = new Utf8Reader();

    private Utf8Reader() {}

    @Override
    public Utf8 read(Decoder decoder, Object reuse) throws IOException {
      // use the decoder's readString(Utf8) method because it may be a resolving decoder
      if (reuse instanceof Utf8) {
        return decoder.readString((Utf8) reuse);
      } else {
        return decoder.readString(null);
      }
      //      int length = decoder.readInt();
      //      byte[] bytes = new byte[length];
      //      decoder.readFixed(bytes, 0, length);
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.skipString();
    }
  }

  private static class UUIDReader implements ValueReader<UUID> {
    private static final ThreadLocal<ByteBuffer> BUFFER =
        ThreadLocal.withInitial(
            () -> {
              ByteBuffer buffer = ByteBuffer.allocate(16);
              buffer.order(ByteOrder.BIG_ENDIAN);
              return buffer;
            });

    private static final UUIDReader INSTANCE = new UUIDReader();

    private UUIDReader() {}

    @Override
    @SuppressWarnings("ByteBufferBackingArray")
    public UUID read(Decoder decoder, Object ignored) throws IOException {
      ByteBuffer buffer = BUFFER.get();
      buffer.rewind();

      decoder.readFixed(buffer.array(), 0, 16);

      return UUIDUtil.convert(buffer);
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.skipFixed(16);
    }
  }

  private static class FixedReader implements ValueReader<byte[]> {
    private final int length;

    private FixedReader(int length) {
      this.length = length;
    }

    @Override
    public byte[] read(Decoder decoder, Object reuse) throws IOException {
      if (reuse instanceof byte[]) {
        byte[] reusedBytes = (byte[]) reuse;
        if (reusedBytes.length == length) {
          decoder.readFixed(reusedBytes, 0, length);
          return reusedBytes;
        }
      }

      byte[] bytes = new byte[length];
      decoder.readFixed(bytes, 0, length);
      return bytes;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.skipFixed(length);
    }
  }

  private static class GenericFixedReader implements ValueReader<GenericData.Fixed> {
    private final Schema schema;
    private final int length;

    private GenericFixedReader(Schema schema) {
      this.schema = schema;
      this.length = schema.getFixedSize();
    }

    @Override
    public GenericData.Fixed read(Decoder decoder, Object reuse) throws IOException {
      if (reuse instanceof GenericData.Fixed) {
        GenericData.Fixed reusedFixed = (GenericData.Fixed) reuse;
        if (reusedFixed.bytes().length == length) {
          decoder.readFixed(reusedFixed.bytes(), 0, length);
          return reusedFixed;
        }
      }

      byte[] bytes = new byte[length];
      decoder.readFixed(bytes, 0, length);
      return new GenericData.Fixed(schema, bytes);
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.skipFixed(length);
    }
  }

  private static class BytesReader implements ValueReader<byte[]> {
    private static final BytesReader INSTANCE = new BytesReader();

    private BytesReader() {}

    @Override
    public byte[] read(Decoder decoder, Object reuse) throws IOException {
      // use the decoder's readBytes method because it may be a resolving decoder
      // the only time the previous value could be reused is when its length matches the next array,
      // but there is no way to know this with the readBytes call, which uses a ByteBuffer. it is
      // possible to wrap the reused array in a ByteBuffer, but this may still result in allocating
      // a new buffer. since the usual case requires an allocation anyway to get the size right,
      // just allocate every time.
      return decoder.readBytes(null).array();
      //      int length = decoder.readInt();
      //      byte[] bytes = new byte[length];
      //      decoder.readFixed(bytes, 0, length);
      //      return bytes;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.skipBytes();
    }
  }

  private static class ByteBufferReader implements ValueReader<ByteBuffer> {
    private static final ByteBufferReader INSTANCE = new ByteBufferReader();

    private ByteBufferReader() {}

    @Override
    public ByteBuffer read(Decoder decoder, Object reuse) throws IOException {
      // use the decoder's readBytes method because it may be a resolving decoder
      if (reuse instanceof ByteBuffer) {
        return decoder.readBytes((ByteBuffer) reuse);
      } else {
        return decoder.readBytes(null);
      }
      //      int length = decoder.readInt();
      //      byte[] bytes = new byte[length];
      //      decoder.readFixed(bytes, 0, length);
      //      return bytes;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.skipBytes();
    }
  }

  private static class DecimalReader implements ValueReader<BigDecimal> {
    private final ValueReader<byte[]> bytesReader;
    private final int scale;

    private DecimalReader(ValueReader<byte[]> bytesReader, int scale) {
      this.bytesReader = bytesReader;
      this.scale = scale;
    }

    @Override
    public BigDecimal read(Decoder decoder, Object ignored) throws IOException {
      // there isn't a way to get the backing buffer out of a BigInteger, so this can't reuse.
      byte[] bytes = bytesReader.read(decoder, null);
      return new BigDecimal(new BigInteger(bytes), scale);
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      bytesReader.skip(decoder);
    }
  }

  private static class VariantReader implements ValueReader<Variant> {
    private static final VariantReader INSTANCE = new VariantReader();

    private final ValueReader<ByteBuffer> metadataReader;
    private final ValueReader<ByteBuffer> valueReader;

    private VariantReader() {
      this.metadataReader = ByteBufferReader.INSTANCE;
      this.valueReader = ByteBufferReader.INSTANCE;
    }

    @Override
    public Variant read(Decoder decoder, Object reuse) throws IOException {
      VariantMetadata metadata =
          VariantMetadata.from(metadataReader.read(decoder, null).order(ByteOrder.LITTLE_ENDIAN));
      VariantValue value =
          VariantValue.from(
              metadata, metadataReader.read(decoder, null).order(ByteOrder.LITTLE_ENDIAN));
      return Variant.of(metadata, value);
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      metadataReader.skip(decoder);
      valueReader.skip(decoder);
    }
  }

  private static class UnionReader implements ValueReader<Object> {
    private final ValueReader[] readers;

    private UnionReader(List<ValueReader<?>> readers) {
      this.readers = new ValueReader[readers.size()];
      for (int i = 0; i < this.readers.length; i += 1) {
        this.readers[i] = readers.get(i);
      }
    }

    @Override
    public Object read(Decoder decoder, Object reuse) throws IOException {
      int index = decoder.readIndex();
      return readers[index].read(decoder, reuse);
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      int index = decoder.readIndex();
      readers[index].skip(decoder);
    }
  }

  private static class EnumReader implements ValueReader<String> {
    private final String[] symbols;

    private EnumReader(List<String> symbols) {
      this.symbols = new String[symbols.size()];
      for (int i = 0; i < this.symbols.length; i += 1) {
        this.symbols[i] = symbols.get(i);
      }
    }

    @Override
    public String read(Decoder decoder, Object ignored) throws IOException {
      int index = decoder.readEnum();
      return symbols[index];
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      decoder.readEnum();
    }
  }

  private static class ArrayReader<T> implements ValueReader<Collection<T>> {
    private final ValueReader<T> elementReader;
    private Deque<?> lastList = null;

    private ArrayReader(ValueReader<T> elementReader) {
      this.elementReader = elementReader;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<T> read(Decoder decoder, Object reused) throws IOException {
      Deque<T> resultList;
      if (lastList != null) {
        lastList.clear();
        resultList = (LinkedList<T>) lastList;
      } else {
        resultList = Lists.newLinkedList();
      }

      if (reused instanceof LinkedList) {
        this.lastList = (LinkedList<?>) reused;
      } else {
        this.lastList = null;
      }

      long chunkLength = decoder.readArrayStart();
      Iterator<?> elIter = lastList != null ? lastList.iterator() : emptyIterator();

      while (chunkLength > 0) {
        for (long i = 0; i < chunkLength; i += 1) {
          Object lastValue = elIter.hasNext() ? elIter.next() : null;
          resultList.addLast(elementReader.read(decoder, lastValue));
        }

        chunkLength = decoder.arrayNext();
      }

      return resultList;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      long itemsToSkip;
      while ((itemsToSkip = decoder.skipArray()) != 0) {
        for (int i = 0; i < itemsToSkip; i += 1) {
          elementReader.skip(decoder);
        }
      }
    }
  }

  private static class ArrayMapReader<K, V> implements ValueReader<Map<K, V>> {
    private final ValueReader<K> keyReader;
    private final ValueReader<V> valueReader;
    private Map lastMap = null;

    private ArrayMapReader(ValueReader<K> keyReader, ValueReader<V> valueReader) {
      this.keyReader = keyReader;
      this.valueReader = valueReader;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<K, V> read(Decoder decoder, Object reuse) throws IOException {
      if (reuse instanceof Map) {
        this.lastMap = (Map<?, ?>) reuse;
      } else {
        this.lastMap = null;
      }

      Map<K, V> resultMap;
      if (lastMap != null) {
        lastMap.clear();
        resultMap = (Map<K, V>) lastMap;
      } else {
        resultMap = Maps.newLinkedHashMap();
      }

      long chunkLength = decoder.readArrayStart();
      Iterator<Map.Entry<?, ?>> kvIter =
          lastMap != null ? lastMap.entrySet().iterator() : emptyIterator();

      while (chunkLength > 0) {
        for (long i = 0; i < chunkLength; i += 1) {
          K key;
          V value;
          if (kvIter.hasNext()) {
            Map.Entry<?, ?> last = kvIter.next();
            key = keyReader.read(decoder, last.getKey());
            value = valueReader.read(decoder, last.getValue());
          } else {
            key = keyReader.read(decoder, null);
            value = valueReader.read(decoder, null);
          }
          resultMap.put(key, value);
        }

        chunkLength = decoder.arrayNext();
      }

      return resultMap;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      long itemsToSkip;
      while ((itemsToSkip = decoder.skipArray()) != 0) {
        for (int i = 0; i < itemsToSkip; i += 1) {
          keyReader.skip(decoder);
          valueReader.skip(decoder);
        }
      }
    }
  }

  private static class MapReader<K, V> implements ValueReader<Map<K, V>> {
    private final ValueReader<K> keyReader;
    private final ValueReader<V> valueReader;
    private Map lastMap = null;

    private MapReader(ValueReader<K> keyReader, ValueReader<V> valueReader) {
      this.keyReader = keyReader;
      this.valueReader = valueReader;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<K, V> read(Decoder decoder, Object reuse) throws IOException {
      if (reuse instanceof Map) {
        this.lastMap = (Map<?, ?>) reuse;
      } else {
        this.lastMap = null;
      }

      Map<K, V> resultMap;
      if (lastMap != null) {
        lastMap.clear();
        resultMap = (Map<K, V>) lastMap;
      } else {
        resultMap = Maps.newLinkedHashMap();
      }

      long chunkLength = decoder.readMapStart();
      Iterator<Map.Entry<?, ?>> kvIter =
          lastMap != null ? lastMap.entrySet().iterator() : emptyIterator();

      while (chunkLength > 0) {
        for (long i = 0; i < chunkLength; i += 1) {
          K key;
          V value;
          if (kvIter.hasNext()) {
            Map.Entry<?, ?> last = kvIter.next();
            key = keyReader.read(decoder, last.getKey());
            value = valueReader.read(decoder, last.getValue());
          } else {
            key = keyReader.read(decoder, null);
            value = valueReader.read(decoder, null);
          }
          resultMap.put(key, value);
        }

        chunkLength = decoder.mapNext();
      }

      return resultMap;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      long itemsToSkip;
      while ((itemsToSkip = decoder.skipMap()) != 0) {
        for (int i = 0; i < itemsToSkip; i += 1) {
          keyReader.skip(decoder);
          valueReader.skip(decoder);
        }
      }
    }
  }

  private static class SkipStructReader implements ValueReader<Void> {
    private final ValueReader<?>[] readers;

    private SkipStructReader(List<ValueReader<?>> readers) {
      this.readers = readers.toArray(ValueReader[]::new);
    }

    @Override
    public Void read(Decoder decoder, Object reuse) throws IOException {
      skip(decoder);
      return null;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      for (ValueReader<?> reader : readers) {
        reader.skip(decoder);
      }
    }
  }

  public abstract static class PlannedStructReader<S>
      implements ValueReader<S>, SupportsRowPosition {
    private final ValueReader<?>[] readers;
    private final Integer[] positions;

    protected PlannedStructReader(List<Pair<Integer, ValueReader<?>>> readPlan) {
      this.readers = readPlan.stream().map(Pair::second).toArray(ValueReader[]::new);
      this.positions = readPlan.stream().map(Pair::first).toArray(Integer[]::new);
    }

    @Override
    public void setRowPositionSupplier(Supplier<Long> posSupplier) {
      for (ValueReader<?> reader : readers) {
        if (reader instanceof SupportsRowPosition) {
          ((SupportsRowPosition) reader).setRowPositionSupplier(posSupplier);
        }
      }
    }

    protected abstract S reuseOrCreate(Object reuse);

    protected abstract Object get(S struct, int pos);

    protected abstract void set(S struct, int pos, Object value);

    @Override
    public S read(Decoder decoder, Object reuse) throws IOException {
      S struct = reuseOrCreate(reuse);

      for (int i = 0; i < readers.length; i += 1) {
        if (positions[i] != null) {
          Object reusedValue = get(struct, positions[i]);
          set(struct, positions[i], readers[i].read(decoder, reusedValue));
        } else {
          // if pos is null, the value is not projected
          readers[i].skip(decoder);
        }
      }

      return struct;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      for (int i = 0; i < readers.length; i += 1) {
        readers[i].skip(decoder);
      }
    }
  }

  private static class PlannedRecordReader extends PlannedStructReader<GenericData.Record> {
    private final Schema recordSchema;

    private PlannedRecordReader(Schema recordSchema, List<Pair<Integer, ValueReader<?>>> readPlan) {
      super(readPlan);
      this.recordSchema = recordSchema;
    }

    @Override
    protected GenericData.Record reuseOrCreate(Object reuse) {
      if (reuse instanceof GenericData.Record) {
        return (GenericData.Record) reuse;
      } else {
        return new GenericData.Record(recordSchema);
      }
    }

    @Override
    protected Object get(GenericData.Record struct, int pos) {
      return struct.get(pos);
    }

    @Override
    protected void set(GenericData.Record struct, int pos, Object value) {
      struct.put(pos, value);
    }
  }

  private static class PlannedIndexedReader<R extends IndexedRecord>
      extends PlannedStructReader<R> {
    private final Class<R> recordClass;
    private final DynConstructors.Ctor<R> ctor;
    private final Schema schema;

    PlannedIndexedReader(
        Schema recordSchema, Class<R> recordClass, List<Pair<Integer, ValueReader<?>>> readPlan) {
      super(readPlan);
      this.recordClass = recordClass;
      this.ctor =
          DynConstructors.builder(IndexedRecord.class)
              .hiddenImpl(recordClass, Schema.class)
              .hiddenImpl(recordClass)
              .build();
      this.schema = recordSchema;
    }

    @Override
    protected R reuseOrCreate(Object reuse) {
      if (recordClass.isInstance(reuse)) {
        return recordClass.cast(reuse);
      } else {
        return ctor.newInstance(schema);
      }
    }

    @Override
    protected Object get(R struct, int pos) {
      return struct.get(pos);
    }

    @Override
    protected void set(R struct, int pos, Object value) {
      struct.put(pos, value);
    }
  }

  public abstract static class StructReader<S> implements ValueReader<S>, SupportsRowPosition {
    private final ValueReader<?>[] readers;
    private final int[] positions;
    private final Object[] constants;
    private int posField = -1;

    protected StructReader(List<ValueReader<?>> readers, Schema schema) {
      this.readers = readers.toArray(new ValueReader[0]);
      Integer isDeletedColumnPos = null;

      List<Schema.Field> fields = schema.getFields();
      for (int pos = 0; pos < fields.size(); pos += 1) {
        Schema.Field field = fields.get(pos);
        if (Objects.equals(AvroSchemaUtil.fieldId(field), MetadataColumns.ROW_POSITION.fieldId())) {
          // track where the _pos field is located for setRowPositionSupplier
          this.posField = pos;
        } else if (Objects.equals(
            AvroSchemaUtil.fieldId(field), MetadataColumns.IS_DELETED.fieldId())) {
          isDeletedColumnPos = pos;
        }
      }

      if (isDeletedColumnPos == null) {
        this.positions = new int[0];
        this.constants = new Object[0];
      } else {
        this.positions = new int[] {isDeletedColumnPos};
        this.constants = new Object[] {false};
      }
    }

    protected StructReader(
        List<ValueReader<?>> readers, Types.StructType struct, Map<Integer, ?> idToConstant) {
      this.readers = readers.toArray(new ValueReader[0]);

      List<Types.NestedField> fields = struct.fields();
      List<Integer> positionList = Lists.newArrayListWithCapacity(fields.size());
      List<Object> constantList = Lists.newArrayListWithCapacity(fields.size());
      for (int pos = 0; pos < fields.size(); pos += 1) {
        Types.NestedField field = fields.get(pos);
        if (idToConstant.containsKey(field.fieldId())) {
          positionList.add(pos);
          constantList.add(idToConstant.get(field.fieldId()));
        } else if (field.fieldId() == MetadataColumns.ROW_POSITION.fieldId()) {
          // track where the _pos field is located for setRowPositionSupplier
          this.posField = pos;
        } else if (field.fieldId() == MetadataColumns.IS_DELETED.fieldId()) {
          positionList.add(pos);
          constantList.add(false);
        }
      }

      this.positions = positionList.stream().mapToInt(Integer::intValue).toArray();
      this.constants = constantList.toArray();
    }

    @Override
    public void setRowPositionSupplier(Supplier<Long> posSupplier) {
      if (posField >= 0) {
        this.readers[posField] = new PositionReader();
      }

      for (ValueReader<?> reader : readers) {
        if (reader instanceof SupportsRowPosition) {
          ((SupportsRowPosition) reader).setRowPositionSupplier(posSupplier);
        }
      }
    }

    protected abstract S reuseOrCreate(Object reuse);

    protected abstract Object get(S struct, int pos);

    protected abstract void set(S struct, int pos, Object value);

    public ValueReader<?> reader(int pos) {
      return readers[pos];
    }

    @Override
    public S read(Decoder decoder, Object reuse) throws IOException {
      S struct = reuseOrCreate(reuse);

      if (decoder instanceof ResolvingDecoder) {
        // this may not set all of the fields. nulls are set by default.
        for (Schema.Field field : ((ResolvingDecoder) decoder).readFieldOrder()) {
          Object reusedValue = get(struct, field.pos());
          set(struct, field.pos(), readers[field.pos()].read(decoder, reusedValue));
        }

      } else {
        for (int i = 0; i < readers.length; i += 1) {
          Object reusedValue = get(struct, i);
          set(struct, i, readers[i].read(decoder, reusedValue));
        }
      }

      for (int i = 0; i < positions.length; i += 1) {
        set(struct, positions[i], constants[i]);
      }

      return struct;
    }
  }

  private static class RecordReader extends StructReader<GenericData.Record> {
    private final Schema recordSchema;

    private RecordReader(List<ValueReader<?>> readers, Schema recordSchema) {
      super(readers, recordSchema);
      this.recordSchema = recordSchema;
    }

    @Override
    protected GenericData.Record reuseOrCreate(Object reuse) {
      if (reuse instanceof GenericData.Record) {
        return (GenericData.Record) reuse;
      } else {
        return new GenericData.Record(recordSchema);
      }
    }

    @Override
    protected Object get(GenericData.Record struct, int pos) {
      return struct.get(pos);
    }

    @Override
    protected void set(GenericData.Record struct, int pos, Object value) {
      struct.put(pos, value);
    }
  }

  static class IndexedRecordReader<R extends IndexedRecord> extends StructReader<R> {
    private final Class<R> recordClass;
    private final DynConstructors.Ctor<R> ctor;
    private final Schema schema;

    IndexedRecordReader(List<ValueReader<?>> readers, Class<R> recordClass, Schema schema) {
      super(readers, schema);
      this.recordClass = recordClass;
      this.ctor =
          DynConstructors.builder(IndexedRecord.class)
              .hiddenImpl(recordClass, Schema.class)
              .hiddenImpl(recordClass)
              .build();
      this.schema = schema;
    }

    @Override
    protected R reuseOrCreate(Object reuse) {
      if (recordClass.isInstance(reuse)) {
        return recordClass.cast(reuse);
      } else {
        return ctor.newInstance(schema);
      }
    }

    @Override
    protected Object get(R struct, int pos) {
      return struct.get(pos);
    }

    @Override
    protected void set(R struct, int pos, Object value) {
      struct.put(pos, value);
    }
  }

  static class PositionReader implements ValueReader<Long>, SupportsRowPosition {
    private long currentPosition = 0;

    @Override
    public Long read(Decoder ignored, Object reuse) throws IOException {
      this.currentPosition += 1;
      return currentPosition;
    }

    @Override
    public void skip(Decoder ignored) throws IOException {}

    @Override
    public void setRowPositionSupplier(Supplier<Long> posSupplier) {
      this.currentPosition = posSupplier.get() - 1;
    }
  }

  static class RowIdReader implements ValueReader<Long>, SupportsRowPosition {
    private final long firstRowId;
    private final ValueReader<Long> idReader;
    private final ValueReader<Long> posReader;

    RowIdReader(Long firstRowId, ValueReader<Long> idReader) {
      this.firstRowId = firstRowId;
      this.idReader = idReader != null ? idReader : ValueReaders.constant(null);
      this.posReader = positions();
    }

    @Override
    public Long read(Decoder ignored, Object reuse) throws IOException {
      Long idFromFile = idReader.read(ignored, reuse);
      long pos = posReader.read(ignored, reuse);

      if (idFromFile != null) {
        return idFromFile;
      }

      return firstRowId + pos;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      idReader.skip(decoder);
      posReader.skip(decoder);
    }

    @Override
    public void setRowPositionSupplier(Supplier<Long> posSupplier) {
      ((SupportsRowPosition) posReader).setRowPositionSupplier(posSupplier);
    }
  }

  static class LastUpdatedSeqReader implements ValueReader<Long> {
    private final long fileSeqNumber;
    private final ValueReader<Long> seqReader;

    LastUpdatedSeqReader(long fileSeqNumber, ValueReader<Long> seqReader) {
      this.fileSeqNumber = fileSeqNumber;
      this.seqReader = seqReader;
    }

    @Override
    public Long read(Decoder ignored, Object reuse) throws IOException {
      Long rowLastUpdatedSeqNumber = seqReader.read(ignored, reuse);
      if (rowLastUpdatedSeqNumber != null) {
        return rowLastUpdatedSeqNumber;
      }

      return fileSeqNumber;
    }

    @Override
    public void skip(Decoder decoder) throws IOException {
      seqReader.skip(decoder);
    }
  }
}
