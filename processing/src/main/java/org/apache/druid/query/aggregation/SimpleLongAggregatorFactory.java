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

package org.apache.druid.query.aggregation;


import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import org.apache.druid.math.expr.Expr;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.math.expr.Parser;
import org.apache.druid.segment.BaseLongColumnValueSelector;
import org.apache.druid.segment.ColumnInspector;
import org.apache.druid.segment.ColumnSelectorFactory;
import org.apache.druid.segment.ColumnValueSelector;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.Types;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.vector.VectorColumnSelectorFactory;
import org.apache.druid.segment.vector.VectorValueSelector;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * This is an abstract class inherited by various {@link AggregatorFactory} implementations that consume long input
 * and produce long output on aggregation.
 * It extends "NullableAggregatorFactory<ColumnValueSelector>" instead of "NullableAggregatorFactory<BaseLongColumnValueSelector>"
 * to additionally support aggregation on single/multi value string column types.
 */
public abstract class SimpleLongAggregatorFactory extends NullableNumericAggregatorFactory<ColumnValueSelector>
{
  protected final String name;
  @Nullable
  protected final String fieldName;
  @Nullable
  protected final String expression;
  protected final ExprMacroTable macroTable;
  protected final Supplier<Expr> fieldExpression;

  public SimpleLongAggregatorFactory(
      ExprMacroTable macroTable,
      String name,
      @Nullable final String fieldName,
      @Nullable String expression
  )
  {
    this.macroTable = macroTable;
    this.name = name;
    this.fieldName = fieldName;
    this.expression = expression;
    this.fieldExpression = Parser.lazyParse(expression, macroTable);
    Preconditions.checkNotNull(name, "Must have a valid, non-null aggregator name");
    Preconditions.checkArgument(
        fieldName == null ^ expression == null,
        "Must have a valid, non-null fieldName or expression"
    );
  }

  @Override
  protected Aggregator factorize(ColumnSelectorFactory metricFactory, ColumnValueSelector selector)
  {
    if (shouldUseStringColumnAggregatorWrapper(metricFactory)) {
      return new StringColumnLongAggregatorWrapper(
          selector,
          SimpleLongAggregatorFactory.this::buildAggregator,
          nullValue()
      );
    } else {
      return buildAggregator(selector);
    }
  }

  @Override
  protected BufferAggregator factorizeBuffered(
      ColumnSelectorFactory metricFactory,
      ColumnValueSelector selector
  )
  {
    if (shouldUseStringColumnAggregatorWrapper(metricFactory)) {
      return new StringColumnLongBufferAggregatorWrapper(
          selector,
          SimpleLongAggregatorFactory.this::buildBufferAggregator,
          nullValue()
      );
    } else {
      return buildBufferAggregator(selector);
    }
  }

  @Override
  protected ColumnValueSelector selector(ColumnSelectorFactory metricFactory)
  {
    return AggregatorUtil.makeColumnValueSelectorWithLongDefault(
        metricFactory,
        fieldName,
        fieldExpression.get(),
        nullValue()
    );
  }

  @Override
  protected VectorValueSelector vectorSelector(VectorColumnSelectorFactory columnSelectorFactory)
  {
    return AggregatorUtil.makeVectorValueSelector(columnSelectorFactory, fieldName, expression, fieldExpression);
  }

  @Override
  public Object deserialize(Object object)
  {
    return object;
  }


  @Override
  public ColumnType getType()
  {
    return ColumnType.LONG;
  }

  @Override
  public int getMaxIntermediateSize()
  {
    return Long.BYTES;
  }

  @Override
  public Comparator getComparator()
  {
    return LongSumAggregator.COMPARATOR;
  }

  @Override
  @Nullable
  public Object finalizeComputation(@Nullable Object object)
  {
    return object;
  }

  @Override
  public List<String> requiredFields()
  {
    return fieldName != null
           ? Collections.singletonList(fieldName)
           : fieldExpression.get().analyzeInputs().getRequiredBindingsList();
  }

  @Override
  public AggregatorFactory getMergingFactory(AggregatorFactory other) throws AggregatorFactoryNotMergeableException
  {
    if (other.getName().equals(this.getName()) && this.getClass() == other.getClass()) {
      return getCombiningFactory();
    } else {
      throw new AggregatorFactoryNotMergeableException(this, other);
    }
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(fieldName, expression, name);
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

    SimpleLongAggregatorFactory that = (SimpleLongAggregatorFactory) o;

    if (!Objects.equals(fieldName, that.fieldName)) {
      return false;
    }
    if (!Objects.equals(expression, that.expression)) {
      return false;
    }
    if (!Objects.equals(name, that.name)) {
      return false;
    }
    return true;
  }

  @Override
  @JsonProperty
  public String getName()
  {
    return name;
  }

  @Nullable
  @JsonProperty
  public String getFieldName()
  {
    return fieldName;
  }

  @Nullable
  @JsonProperty
  public String getExpression()
  {
    return expression;
  }

  @Override
  public boolean canVectorize(ColumnInspector columnInspector)
  {
    return AggregatorUtil.canVectorize(columnInspector, fieldName, expression, fieldExpression);
  }

  private boolean shouldUseStringColumnAggregatorWrapper(ColumnSelectorFactory columnSelectorFactory)
  {
    if (fieldName != null) {
      ColumnCapabilities capabilities = columnSelectorFactory.getColumnCapabilities(fieldName);
      return Types.is(capabilities, ValueType.STRING);
    }
    return false;
  }

  protected abstract long nullValue();

  protected abstract Aggregator buildAggregator(BaseLongColumnValueSelector selector);

  protected abstract BufferAggregator buildBufferAggregator(BaseLongColumnValueSelector selector);
}
