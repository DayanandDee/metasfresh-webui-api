package de.metas.ui.web.window.model;

import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.concurrent.Immutable;

import org.adempiere.util.Check;
import org.adempiere.util.GuavaCollectors;

import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2016 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@Immutable
@SuppressWarnings("serial")
public final class DocumentQueryOrderBy implements Serializable
{
	public static final DocumentQueryOrderBy byFieldName(final String fieldName, final boolean ascending)
	{
		return new DocumentQueryOrderBy(fieldName, ascending);
	}

	/**
	 * @param orderBysListStr Command separated field names. Use +/- prefix for ascending/descending. e.g. +C_BPartner_ID,-DateOrdered
	 */
	public static final List<DocumentQueryOrderBy> parseOrderBysList(final String orderBysListStr)
	{
		if (Check.isEmpty(orderBysListStr, true))
		{
			return ImmutableList.of();
		}

		return Splitter.on(',')
				.trimResults()
				.omitEmptyStrings()
				.splitToList(orderBysListStr)
				.stream()
				.map(orderByStr -> parseOrderBy(orderByStr))
				.collect(GuavaCollectors.toImmutableList());
	}

	/**
	 * @param orderByStr field name with optional +/- prefix for ascending/descending. e.g. +C_BPartner_ID
	 */
	private static final DocumentQueryOrderBy parseOrderBy(final String orderByStr)
	{
		if (orderByStr.charAt(0) == '+')
		{
			final String fieldName = orderByStr.substring(1);
			return DocumentQueryOrderBy.byFieldName(fieldName, true);
		}
		else if (orderByStr.charAt(0) == '-')
		{
			final String fieldName = orderByStr.substring(1);
			return DocumentQueryOrderBy.byFieldName(fieldName, false);
		}
		else
		{
			final String fieldName = orderByStr;
			return DocumentQueryOrderBy.byFieldName(fieldName, true);
		}
	}

	private final String fieldName;
	private final boolean ascending;

	private DocumentQueryOrderBy(final String fieldName, final boolean ascending)
	{
		super();
		Check.assumeNotEmpty(fieldName, "fieldName is not empty");
		this.fieldName = fieldName;
		this.ascending = ascending;
	}

	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.omitNullValues()
				.add("fieldName", fieldName)
				.add("ascending", ascending)
				.toString();
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(fieldName, ascending);
	}

	@Override
	public boolean equals(final Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		if (obj instanceof DocumentQueryOrderBy)
		{
			final DocumentQueryOrderBy other = (DocumentQueryOrderBy)obj;
			return Objects.equals(fieldName, other.fieldName)
					&& ascending == other.ascending;
		}
		return false;
	}

	public String getFieldName()
	{
		return fieldName;
	}

	public boolean isAscending()
	{
		return ascending;
	}
	
	public <T> Comparator<T> asComparator(final BiFunction<T, String, Object> fieldValueExtractor)
	{
		final Function<T, Object> keyExtractor = obj -> fieldValueExtractor.apply(obj, fieldName);
		Comparator<T> cmp = Comparator.comparing(keyExtractor, ValueComparator.instance);

		if (!ascending)
		{
			cmp = cmp.reversed();
		}

		return cmp;
	}

	private static final class ValueComparator implements Comparator<Object>
	{
		public static final transient ValueComparator instance = new ValueComparator();

		private ValueComparator()
		{
			super();
		}

		@Override
		public int compare(final Object o1, final Object o2)
		{
			if (o1 instanceof Comparable)
			{
				@SuppressWarnings("unchecked")
				final Comparable<Object> o1cmp = (Comparable<Object>)o1;
				return o1cmp.compareTo(o2);
			}
			else if (o1 == null)
			{
				return o2 == null ? 0 : -1;
			}
			else if (o2 == null)
			{
				return +1;
			}
			else
			{
				return o1.toString().compareTo(o2.toString());
			}
		}

	}
}
