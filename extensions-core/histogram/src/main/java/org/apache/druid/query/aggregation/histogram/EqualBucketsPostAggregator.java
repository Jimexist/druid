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

package org.apache.druid.query.aggregation.histogram;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.collect.Sets;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.HistogramAggregatorFactory;
import org.apache.druid.query.aggregation.PostAggregator;
import org.apache.druid.query.aggregation.post.PostAggregatorIds;
import org.apache.druid.query.cache.CacheKeyBuilder;
import org.apache.druid.segment.column.ColumnType;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

@JsonTypeName("equalBuckets")
public class EqualBucketsPostAggregator extends ApproximateHistogramPostAggregator
{
  private final int numBuckets;

  @JsonCreator
  public EqualBucketsPostAggregator(
      @JsonProperty("name") String name,
      @JsonProperty("fieldName") String fieldName,
      @JsonProperty("numBuckets") int numBuckets
  )
  {
    super(name, fieldName);
    this.numBuckets = numBuckets;
    if (this.numBuckets <= 1) {
      throw new IAE("Illegal number of buckets[%s], must be > 1", this.numBuckets);
    }
  }

  @Override
  public Set<String> getDependentFields()
  {
    return Sets.newHashSet(fieldName);
  }

  @Override
  public Object compute(Map<String, Object> values)
  {
    ApproximateHistogram ah = (ApproximateHistogram) values.get(fieldName);
    return ah.toHistogram(numBuckets);
  }

  /**
   * actual type is {@link Histogram}
   */
  @Override
  public ColumnType getType()
  {
    return HistogramAggregatorFactory.TYPE;
  }

  @Override
  public PostAggregator decorate(Map<String, AggregatorFactory> aggregators)
  {
    return this;
  }

  @JsonProperty
  public int getNumBuckets()
  {
    return numBuckets;
  }

  @Override
  public String toString()
  {
    return "EqualBucketsPostAggregator{" +
           "name='" + name + '\'' +
           ", fieldName='" + fieldName + '\'' +
           ", numBuckets=" + numBuckets +
           '}';
  }

  @Override
  public byte[] getCacheKey()
  {
    return new CacheKeyBuilder(PostAggregatorIds.HISTOGRAM_EQUAL_BUCKETS)
        .appendString(fieldName)
        .appendInt(numBuckets)
        .build();
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    EqualBucketsPostAggregator that = (EqualBucketsPostAggregator) o;
    return numBuckets == that.numBuckets;
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(super.hashCode(), numBuckets);
  }
}
