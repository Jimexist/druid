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

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.math.expr.Expr;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.math.expr.InputBindings;
import org.apache.druid.math.expr.Parser;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.PostAggregator;
import org.apache.druid.query.cache.CacheKeyBuilder;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.utils.CollectionUtils;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ExpressionPostAggregator implements PostAggregator
{
  private static final Comparator<Comparable> DEFAULT_COMPARATOR = Comparator.nullsFirst(
      (Comparable o1, Comparable o2) -> {
        if (o1 instanceof Long && o2 instanceof Long) {
          return Long.compare((long) o1, (long) o2);
        } else if (o1 instanceof Number && o2 instanceof Number) {
          return Double.compare(((Number) o1).doubleValue(), ((Number) o2).doubleValue());
        } else {
          return o1.compareTo(o2);
        }
      }
  );

  private final String name;
  private final String expression;
  private final Comparator<Comparable> comparator;
  @Nullable
  private final String ordering;

  // type is ignored from equals and friends because it is computed by decorate, and all post-aggs should be decorated
  // prior to usage (and is currently done so in the query constructors of all queries which can have post-aggs)
  @Nullable
  private final ColumnType outputType;

  private final ExprMacroTable macroTable;
  private final Map<String, Function<Object, Object>> finalizers;

  private final Supplier<Expr> parsed;
  private final Supplier<Set<String>> dependentFields;
  private final Supplier<byte[]> cacheKey;

  /**
   * Constructor for serialization.
   */
  @JsonCreator
  public ExpressionPostAggregator(
      @JsonProperty("name") String name,
      @JsonProperty("expression") String expression,
      @JsonProperty("ordering") @Nullable String ordering,
      @JacksonInject ExprMacroTable macroTable
  )
  {
    this(
        name,
        expression,
        ordering,
        macroTable,
        ImmutableMap.of(),
        Parser.lazyParse(expression, macroTable)
    );
  }

  private ExpressionPostAggregator(
      final String name,
      final String expression,
      @Nullable final String ordering,
      final ExprMacroTable macroTable,
      final Map<String, Function<Object, Object>> finalizers,
      final Supplier<Expr> parsed
  )
  {
    this(
        name,
        expression,
        ordering,
        null, // in the future this will be computed by decorate
        macroTable,
        finalizers,
        parsed,
        Suppliers.memoize(() -> parsed.get().analyzeInputs().getRequiredBindings())
    );
  }

  private ExpressionPostAggregator(
      final String name,
      final String expression,
      @Nullable final String ordering,
      @Nullable final ColumnType outputType,
      final ExprMacroTable macroTable,
      final Map<String, Function<Object, Object>> finalizers,
      final Supplier<Expr> parsed,
      final Supplier<Set<String>> dependentFields
  )
  {
    Preconditions.checkArgument(expression != null, "expression cannot be null");

    this.name = name;
    this.expression = expression;
    this.ordering = ordering;
    // allow nulls to match previous behavior when type was never specified, however this should be non-nullable
    // in the future, when expression support type inference
    this.outputType = outputType;
    // comparator should be specialized to output type ... someday
    this.comparator = ordering == null ? DEFAULT_COMPARATOR : Ordering.valueOf(ordering);
    this.macroTable = macroTable;
    this.finalizers = finalizers;

    this.parsed = parsed;
    this.dependentFields = dependentFields;
    this.cacheKey = Suppliers.memoize(() -> {
      return new CacheKeyBuilder(PostAggregatorIds.EXPRESSION)
          .appendCacheable(parsed.get())
          .appendString(ordering)
          .build();
    });
  }


  @Override
  public Set<String> getDependentFields()
  {
    return dependentFields.get();
  }

  @Override
  public Comparator getComparator()
  {
    return comparator;
  }

  @Override
  public Object compute(Map<String, Object> values)
  {
    // Maps.transformEntries is lazy, will only finalize values we actually read.
    final Map<String, Object> finalizedValues = Maps.transformEntries(
        values,
        (String k, Object v) -> {
          final Function<Object, Object> finalizer = finalizers.get(k);
          return finalizer != null ? finalizer.apply(v) : v;
        }
    );

    return parsed.get().eval(InputBindings.withMap(finalizedValues)).value();
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
    // computed by decorate
    return outputType;
  }

  @Override
  public ExpressionPostAggregator decorate(final Map<String, AggregatorFactory> aggregators)
  {
    return new ExpressionPostAggregator(
        name,
        expression,
        ordering,
        null, // this should be computed from expression output type once it supports output type inference
        macroTable,
        CollectionUtils.mapValues(aggregators, aggregatorFactory -> aggregatorFactory::finalizeComputation),
        parsed,
        dependentFields
    );
  }

  @JsonProperty("expression")
  public String getExpression()
  {
    return expression;
  }

  @Nullable
  @JsonProperty("ordering")
  public String getOrdering()
  {
    return ordering;
  }

  @Override
  public String toString()
  {
    return "ExpressionPostAggregator{" +
           "name='" + name + '\'' +
           ", expression='" + expression + '\'' +
           ", ordering=" + ordering +
           '}';
  }

  @Override
  public byte[] getCacheKey()
  {
    return cacheKey.get();
  }

  public enum Ordering implements Comparator<Comparable>
  {
    /**
     * Ensures the following order: numeric > NaN > Infinite.
     *
     * The name may be referenced via Ordering.valueOf(String) in the constructor {@link
     * ExpressionPostAggregator#ExpressionPostAggregator(String, String, String, ColumnType, ExprMacroTable, Map, Supplier, Supplier)}.
     */
    @SuppressWarnings("unused")
    numericFirst {
      @Override
      public int compare(Comparable lhs, Comparable rhs)
      {
        if (lhs instanceof Long && rhs instanceof Long) {
          return Long.compare(((Number) lhs).longValue(), ((Number) rhs).longValue());
        } else if (lhs instanceof Number && rhs instanceof Number) {
          double d1 = ((Number) lhs).doubleValue();
          double d2 = ((Number) rhs).doubleValue();
          if (Double.isFinite(d1) && !Double.isFinite(d2)) {
            return 1;
          }
          if (!Double.isFinite(d1) && Double.isFinite(d2)) {
            return -1;
          }
          return Double.compare(d1, d2);
        } else {
          return Comparators.<Comparable>naturalNullsFirst().compare(lhs, rhs);
        }
      }
    }
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

    ExpressionPostAggregator that = (ExpressionPostAggregator) o;

    if (!comparator.equals(that.comparator)) {
      return false;
    }
    if (!Objects.equals(name, that.name)) {
      return false;
    }
    if (!Objects.equals(expression, that.expression)) {
      return false;
    }
    if (!Objects.equals(ordering, that.ordering)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(name, expression, comparator, ordering);
  }
}
