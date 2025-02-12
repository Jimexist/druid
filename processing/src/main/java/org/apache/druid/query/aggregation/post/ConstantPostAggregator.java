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

package org.apache.druid.query.aggregation.post;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.PostAggregator;
import org.apache.druid.query.cache.CacheKeyBuilder;
import org.apache.druid.segment.column.ColumnType;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 */
public class ConstantPostAggregator implements PostAggregator
{
  private final String name;
  private final Number constantValue;

  @JsonCreator
  public ConstantPostAggregator(
      @JsonProperty("name") String name,
      @JsonProperty("value") Number constantValue
  )
  {
    this.name = name;
    this.constantValue = Preconditions.checkNotNull(constantValue, "Constant value cannot be null");
  }

  @Override
  public Set<String> getDependentFields()
  {
    return new HashSet<>();
  }

  @Override
  public Comparator getComparator()
  {
    return Comparators.alwaysEqual();
  }

  @Override
  public Object compute(Map<String, Object> combinedAggregators)
  {
    return constantValue;
  }

  @Override
  @JsonProperty
  public String getName()
  {
    return name;
  }

  @Override
  public ColumnType getType()
  {
    return constantValue instanceof Long ? ColumnType.LONG : ColumnType.DOUBLE;
  }

  @Override
  public ConstantPostAggregator decorate(Map<String, AggregatorFactory> aggregators)
  {
    return this;
  }

  @JsonProperty("value")
  public Number getConstantValue()
  {
    return constantValue;
  }

  @Override
  public String toString()
  {
    return "ConstantPostAggregator{" +
           "name='" + name + '\'' +
           ", constantValue=" + constantValue +
           '}';
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

    ConstantPostAggregator that = (ConstantPostAggregator) o;

    if (constantValue.doubleValue() != that.constantValue.doubleValue()) {
      return false;
    }

    if (name != null ? !name.equals(that.name) : that.name != null) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode()
  {
    int result = name != null ? name.hashCode() : 0;
    result = 31 * result + constantValue.hashCode();
    return result;
  }

  @Override
  public byte[] getCacheKey()
  {
    return new CacheKeyBuilder(PostAggregatorIds.CONSTANT)
        .appendDouble(constantValue.doubleValue())
        .build();
  }
}
