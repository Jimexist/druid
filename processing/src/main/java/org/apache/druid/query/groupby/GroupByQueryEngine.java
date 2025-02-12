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

package org.apache.druid.query.groupby;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import org.apache.druid.collections.NonBlockingPool;
import org.apache.druid.collections.ResourceHolder;
import org.apache.druid.data.input.MapBasedRow;
import org.apache.druid.data.input.Row;
import org.apache.druid.guice.annotations.Global;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.guava.BaseSequence;
import org.apache.druid.java.util.common.guava.CloseQuietly;
import org.apache.druid.java.util.common.guava.FunctionalIterator;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.BufferAggregator;
import org.apache.druid.query.aggregation.PostAggregator;
import org.apache.druid.query.dimension.DimensionSpec;
import org.apache.druid.query.filter.Filter;
import org.apache.druid.segment.Cursor;
import org.apache.druid.segment.DimensionDictionarySelector;
import org.apache.druid.segment.DimensionSelector;
import org.apache.druid.segment.StorageAdapter;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.data.IndexedInts;
import org.apache.druid.segment.filter.Filters;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

/**
 */
public class GroupByQueryEngine
{
  private static final int MISSING_VALUE = -1;

  private final Supplier<GroupByQueryConfig> config;
  private final NonBlockingPool<ByteBuffer> intermediateResultsBufferPool;

  @Inject
  public GroupByQueryEngine(
      Supplier<GroupByQueryConfig> config,
      @Global NonBlockingPool<ByteBuffer> intermediateResultsBufferPool
  )
  {
    this.config = config;
    this.intermediateResultsBufferPool = intermediateResultsBufferPool;
  }

  public Sequence<Row> process(final GroupByQuery query, final StorageAdapter storageAdapter)
  {
    if (storageAdapter == null) {
      throw new ISE(
          "Null storage adapter found. Probably trying to issue a query against a segment being memory unmapped."
      );
    }

    final List<Interval> intervals = query.getQuerySegmentSpec().getIntervals();
    if (intervals.size() != 1) {
      throw new IAE("Should only have one interval, got[%s]", intervals);
    }

    Filter filter = Filters.convertToCNFFromQueryContext(query, Filters.toFilter(query.getDimFilter()));

    final Sequence<Cursor> cursors = storageAdapter.makeCursors(
        filter,
        intervals.get(0),
        query.getVirtualColumns(),
        query.getGranularity(),
        false,
        null
    );

    final ResourceHolder<ByteBuffer> bufferHolder = intermediateResultsBufferPool.take();

    return Sequences.concat(
        Sequences.withBaggage(
            Sequences.map(
                cursors,
                new Function<Cursor, Sequence<Row>>()
                {
                  @Override
                  public Sequence<Row> apply(final Cursor cursor)
                  {
                    return new BaseSequence<>(
                        new BaseSequence.IteratorMaker<Row, RowIterator>()
                        {
                          @Override
                          public RowIterator make()
                          {
                            return new RowIterator(query, cursor, bufferHolder.get(), config.get());
                          }

                          @Override
                          public void cleanup(RowIterator iterFromMake)
                          {
                            CloseQuietly.close(iterFromMake);
                          }
                        }
                    );
                  }
                }
            ),
            new Closeable()
            {
              @Override
              public void close()
              {
                CloseQuietly.close(bufferHolder);
              }
            }
        )
    );
  }

  private static class RowUpdater
  {
    private final ByteBuffer metricValues;
    private final BufferAggregator[] aggregators;
    private final PositionMaintainer positionMaintainer;

    private final Map<ByteBuffer, Integer> positions = new TreeMap<>();
    // GroupBy queries tend to do a lot of reads from this. We co-store a hash map to make those reads go faster.
    private final Map<ByteBuffer, Integer> positionsHash = new HashMap<>();

    public RowUpdater(
        ByteBuffer metricValues,
        BufferAggregator[] aggregators,
        PositionMaintainer positionMaintainer
    )
    {
      this.metricValues = metricValues;
      this.aggregators = aggregators;
      this.positionMaintainer = positionMaintainer;
    }

    public int getNumRows()
    {
      return positions.size();
    }

    public Map<ByteBuffer, Integer> getPositions()
    {
      return positions;
    }

    @Nullable
    private List<ByteBuffer> updateValues(ByteBuffer key, List<DimensionSelector> dims)
    {
      if (dims.size() > 0) {
        final DimensionSelector dimSelector = dims.get(0);
        final IndexedInts row = dimSelector.getRow();
        final int rowSize = row.size();
        if (rowSize == 0) {
          ByteBuffer newKey = key.duplicate();
          newKey.putInt(MISSING_VALUE);
          return updateValues(newKey, dims.subList(1, dims.size()));
        } else {
          List<ByteBuffer> retVal = null;
          for (int i = 0; i < rowSize; i++) {
            ByteBuffer newKey = key.duplicate();
            int dimValue = row.get(i);
            newKey.putInt(dimValue);
            List<ByteBuffer> unaggregatedBuffers = updateValues(newKey, dims.subList(1, dims.size()));
            if (unaggregatedBuffers != null) {
              if (retVal == null) {
                retVal = new ArrayList<>();
              }
              retVal.addAll(unaggregatedBuffers);
            }
          }
          return retVal;
        }
      } else {
        key.clear();
        Integer position = positionsHash.get(key);
        int[] increments = positionMaintainer.getIncrements();
        int thePosition;

        if (position == null) {
          ByteBuffer keyCopy = ByteBuffer.allocate(key.limit());
          keyCopy.put(key.asReadOnlyBuffer());
          keyCopy.clear();

          position = positionMaintainer.getNext();
          if (position == null) {
            return Collections.singletonList(keyCopy);
          }

          positions.put(keyCopy, position);
          positionsHash.put(keyCopy, position);
          thePosition = position;
          for (int i = 0; i < aggregators.length; ++i) {
            aggregators[i].init(metricValues, thePosition);
            thePosition += increments[i];
          }
        }

        thePosition = position;
        for (int i = 0; i < aggregators.length; ++i) {
          aggregators[i].aggregate(metricValues, thePosition);
          thePosition += increments[i];
        }
        return null;
      }
    }
  }

  private static class PositionMaintainer
  {
    private final int[] increments;
    private final int increment;
    private final int max;

    private long nextVal;

    public PositionMaintainer(
        int start,
        int[] increments,
        int max
    )
    {
      this.nextVal = (long) start;
      this.increments = increments;

      int theIncrement = 0;
      for (int inc : increments) {
        theIncrement += inc;
      }
      increment = theIncrement;

      this.max = max - increment; // Make sure there is enough room for one more increment
    }

    @Nullable
    public Integer getNext()
    {
      if (nextVal > max) {
        return null;
      } else {
        int retVal = (int) nextVal;
        nextVal += increment;
        return retVal;
      }
    }

    public int getIncrement()
    {
      return increment;
    }

    public int[] getIncrements()
    {
      return increments;
    }
  }

  private static class RowIterator implements CloseableIterator<Row>
  {
    private final GroupByQuery query;
    private final Cursor cursor;
    private final ByteBuffer metricsBuffer;
    private final int maxIntermediateRows;

    private final List<DimensionSelector> dimensions;
    private final ArrayList<String> dimNames;
    private final BufferAggregator[] aggregators;
    private final String[] metricNames;
    private final int[] sizesRequired;

    @Nullable
    private List<ByteBuffer> unprocessedKeys;
    private Iterator<Row> delegate;

    public RowIterator(GroupByQuery query, final Cursor cursor, ByteBuffer metricsBuffer, GroupByQueryConfig config)
    {
      final GroupByQueryConfig querySpecificConfig = config.withOverrides(query);

      this.query = query;
      this.cursor = cursor;
      this.metricsBuffer = metricsBuffer;
      this.maxIntermediateRows = querySpecificConfig.getMaxIntermediateRows();

      unprocessedKeys = null;
      delegate = Collections.emptyIterator();
      List<DimensionSpec> dimensionSpecs = query.getDimensions();
      dimensions = Lists.newArrayListWithExpectedSize(dimensionSpecs.size());
      dimNames = Lists.newArrayListWithExpectedSize(dimensionSpecs.size());

      for (final DimensionSpec dimSpec : dimensionSpecs) {
        if (!dimSpec.getOutputType().is(ValueType.STRING)) {
          throw new UnsupportedOperationException(
              "GroupBy v1 only supports dimensions with an outputType of STRING."
          );
        }

        final DimensionSelector selector = cursor.getColumnSelectorFactory().makeDimensionSelector(dimSpec);
        if (selector.getValueCardinality() == DimensionDictionarySelector.CARDINALITY_UNKNOWN) {
          throw new UnsupportedOperationException(
              "GroupBy v1 does not support dimension selectors with unknown cardinality.");
        }
        dimensions.add(selector);
        dimNames.add(dimSpec.getOutputName());
      }

      List<AggregatorFactory> aggregatorSpecs = query.getAggregatorSpecs();
      aggregators = new BufferAggregator[aggregatorSpecs.size()];
      metricNames = new String[aggregatorSpecs.size()];
      sizesRequired = new int[aggregatorSpecs.size()];
      for (int i = 0; i < aggregatorSpecs.size(); ++i) {
        AggregatorFactory aggregatorSpec = aggregatorSpecs.get(i);
        aggregators[i] = aggregatorSpec.factorizeBuffered(cursor.getColumnSelectorFactory());
        metricNames[i] = aggregatorSpec.getName();
        sizesRequired[i] = aggregatorSpec.getMaxIntermediateSizeWithNulls();
      }
    }

    @Override
    public boolean hasNext()
    {
      return delegate.hasNext() || !cursor.isDone();
    }

    @Override
    public Row next()
    {
      if (delegate.hasNext()) {
        return delegate.next();
      }

      if (unprocessedKeys == null && cursor.isDone()) {
        throw new NoSuchElementException();
      }

      final PositionMaintainer positionMaintainer = new PositionMaintainer(0, sizesRequired, metricsBuffer.remaining());
      final RowUpdater rowUpdater = new RowUpdater(metricsBuffer, aggregators, positionMaintainer);
      if (unprocessedKeys != null) {
        for (ByteBuffer key : unprocessedKeys) {
          final List<ByteBuffer> unprocUnproc = rowUpdater.updateValues(key, ImmutableList.of());
          if (unprocUnproc != null) {
            throw new ISE("Not enough memory to process the request.");
          }
        }
        cursor.advance();
      }
      while (!cursor.isDone() && rowUpdater.getNumRows() < maxIntermediateRows) {
        ByteBuffer key = ByteBuffer.allocate(dimensions.size() * Integer.BYTES);

        unprocessedKeys = rowUpdater.updateValues(key, dimensions);
        if (unprocessedKeys != null) {
          break;
        }

        cursor.advance();
      }

      if (rowUpdater.getPositions().isEmpty() && unprocessedKeys != null) {
        throw new ISE(
            "Not enough memory to process even a single item.  Required [%,d] memory, but only have[%,d]",
            positionMaintainer.getIncrement(), metricsBuffer.remaining()
        );
      }

      delegate = FunctionalIterator
          .create(rowUpdater.getPositions().entrySet().iterator())
          .transform(
              new Function<Map.Entry<ByteBuffer, Integer>, Row>()
              {
                private final DateTime timestamp = cursor.getTime();
                private final int[] increments = positionMaintainer.getIncrements();

                @Override
                public Row apply(@Nullable Map.Entry<ByteBuffer, Integer> input)
                {
                  Map<String, Object> theEvent = Maps.newLinkedHashMap();

                  ByteBuffer keyBuffer = input.getKey().duplicate();
                  for (int i = 0; i < dimensions.size(); ++i) {
                    final DimensionSelector dimSelector = dimensions.get(i);
                    final int dimVal = keyBuffer.getInt();
                    if (MISSING_VALUE != dimVal) {
                      theEvent.put(dimNames.get(i), dimSelector.lookupName(dimVal));
                    }
                  }

                  int position = input.getValue();
                  for (int i = 0; i < aggregators.length; ++i) {
                    theEvent.put(metricNames[i], aggregators[i].get(metricsBuffer, position));
                    position += increments[i];
                  }

                  for (PostAggregator postAggregator : query.getPostAggregatorSpecs()) {
                    theEvent.put(postAggregator.getName(), postAggregator.compute(theEvent));
                  }

                  return new MapBasedRow(timestamp, theEvent);
                }
              }
          );

      return delegate.next();
    }

    @Override
    public void remove()
    {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close()
    {
      // cleanup
      for (BufferAggregator agg : aggregators) {
        agg.close();
      }
    }
  }
}
