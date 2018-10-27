package de.metas.ui.web.document.filter;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.compiere.Adempiere;
import org.elasticsearch.client.Client;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;

import de.metas.elasticsearch.indexer.IESModelIndexer;
import de.metas.elasticsearch.indexer.IESModelIndexersRegistry;
import de.metas.i18n.IMsgBL;
import de.metas.i18n.ITranslatableString;
import de.metas.ui.web.document.filter.DocumentFilterParam.Operator;
import de.metas.ui.web.window.descriptor.DocumentFieldDefaultFilterDescriptor;
import de.metas.ui.web.window.descriptor.DocumentFieldDescriptor;
import de.metas.ui.web.window.descriptor.DocumentFieldDescriptor.Characteristic;
import de.metas.ui.web.window.descriptor.DocumentFieldWidgetType;
import de.metas.ui.web.window.descriptor.FullTextSearchFilterContext;
import de.metas.ui.web.window.descriptor.FullTextSearchSqlDocumentFilterConverter;
import de.metas.ui.web.window.descriptor.LookupDescriptor;
import de.metas.ui.web.window.descriptor.LookupDescriptorProvider;
import de.metas.util.Services;

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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

/**
 * {@link DocumentFilterDescriptorsProvider}s factory.
 *
 * @author metas-dev <dev@metasfresh.com>
 *
 */
public final class DocumentFilterDescriptorsProviderFactory
{
	private static final String FILTER_ID_Default = "default";

	private static final String FILTER_ID_DefaultDate = "default-date";
	private static final String MSG_DefaultFilterName = "default";

	private static final String MSG_FULL_TEXT_SEARCH_CAPTION = "Search";

	public static final transient DocumentFilterDescriptorsProviderFactory instance = new DocumentFilterDescriptorsProviderFactory();

	// services
	private final transient IMsgBL msgBL = Services.get(IMsgBL.class);

	private DocumentFilterDescriptorsProviderFactory()
	{
		super();
	}

	public DocumentFilterDescriptorsProvider createFiltersProvider(final int adTabId, @Nullable final String tableName, final Collection<DocumentFieldDescriptor> fields)
	{
		return CompositeDocumentFilterDescriptorsProvider.compose(
				createUserQueryDocumentFilterDescriptorsProvider(adTabId, tableName, fields),
				createFiltersProvider_Defaults(fields),
				createFullTextSeachFilterDescriptorProvider(tableName));
	}

	/**
	 * Creates standard filters, i.e. from document fields which are flagged with {@link Characteristic#AllowFiltering}.
	 *
	 * @param fields
	 */
	private ImmutableDocumentFilterDescriptorsProvider createFiltersProvider_Defaults(final Collection<DocumentFieldDescriptor> fields)
	{
		final DocumentFilterDescriptor.Builder defaultFilter = DocumentFilterDescriptor.builder()
				.setFilterId(FILTER_ID_Default)
				.setDisplayName(msgBL.getTranslatableMsgText(MSG_DefaultFilterName))
				.setFrequentUsed(false);
		final DocumentFilterDescriptor.Builder defaultDateFilter = DocumentFilterDescriptor.builder()
				.setFilterId(FILTER_ID_DefaultDate)
				.setFrequentUsed(true);

		final List<DocumentFieldDescriptor> filteringFields = fields.stream()
				.filter(DocumentFieldDescriptor::isDefaultFilterField)
				.sorted(Ordering.natural().onResultOf(field -> field.getDefaultFilterInfo().getSeqNo()))
				.collect(ImmutableList.toImmutableList());

		for (final DocumentFieldDescriptor field : filteringFields)
		{
			final DocumentFilterParamDescriptor.Builder filterParam = createFilterParam(field);
			if (!defaultDateFilter.hasParameters() && filterParam.getWidgetType().isDateOrTime())
			{
				defaultDateFilter.setDisplayName(filterParam.getDisplayName());
				defaultDateFilter.addParameter(filterParam);
			}
			else
			{
				defaultFilter.addParameter(filterParam);
			}
		}

		return Stream.of(defaultDateFilter, defaultFilter)
				.filter(filterBuilder -> filterBuilder.hasParameters())
				.map(filterBuilder -> filterBuilder.build())
				.collect(ImmutableDocumentFilterDescriptorsProvider.collector());
	}

	private final DocumentFilterParamDescriptor.Builder createFilterParam(final DocumentFieldDescriptor field)
	{
		final ITranslatableString displayName = field.getCaption();
		final String fieldName = field.getFieldName();
		final DocumentFieldWidgetType widgetType = extractFilterWidgetType(field);
		final DocumentFieldDefaultFilterDescriptor filteringInfo = field.getDefaultFilterInfo();

		final LookupDescriptor lookupDescriptor = field.getLookupDescriptor(LookupDescriptorProvider.LookupScope.DocumentFilter);

		final Operator operator;
		if (widgetType.isText())
		{
			operator = Operator.LIKE_I;
		}
		else if (filteringInfo.isRangeFilter())
		{
			operator = Operator.BETWEEN;
		}
		else
		{
			operator = Operator.EQUAL;
		}

		return DocumentFilterParamDescriptor.builder()
				.setDisplayName(displayName)
				.setFieldName(fieldName)
				.setWidgetType(widgetType)
				.setOperator(operator)
				.setLookupDescriptor(lookupDescriptor)
				.setMandatory(false)
				.setShowIncrementDecrementButtons(filteringInfo.isShowFilterIncrementButtons())
				.setAutoFilterInitialValue(filteringInfo.getAutoFilterInitialValue());
	}

	private DocumentFieldWidgetType extractFilterWidgetType(final DocumentFieldDescriptor field)
	{
		final DocumentFieldWidgetType widgetType = field.getWidgetType();
		if (widgetType == DocumentFieldWidgetType.DateTime
				|| widgetType == DocumentFieldWidgetType.ZonedDateTime)
		{
			return DocumentFieldWidgetType.Date;
		}

		return widgetType;

	}

	private static DocumentFilterDescriptorsProvider createUserQueryDocumentFilterDescriptorsProvider(
			final int adTabId,
			@Nullable final String tableName,
			final Collection<DocumentFieldDescriptor> fields)
	{
		if (tableName != null && adTabId > 0)
		{
			return new UserQueryDocumentFilterDescriptorsProvider(adTabId, tableName, fields);
		}
		else
		{
			return NullDocumentFilterDescriptorsProvider.instance;
		}
	}

	private DocumentFilterDescriptorsProvider createFullTextSeachFilterDescriptorProvider(@Nullable final String modelTableName)
	{
		if (modelTableName == null)
		{
			return NullDocumentFilterDescriptorsProvider.instance;
		}

		final IESModelIndexersRegistry esModelIndexersRegistry = Services.get(IESModelIndexersRegistry.class);
		final IESModelIndexer modelIndexer = esModelIndexersRegistry.getFullTextSearchModelIndexer(modelTableName)
				.orElse(null);
		if (modelIndexer == null)
		{
			return NullDocumentFilterDescriptorsProvider.instance;
		}

		final ITranslatableString caption = msgBL.getTranslatableMsgText(MSG_FULL_TEXT_SEARCH_CAPTION);
		final FullTextSearchFilterContext context = createFullTextSearchFilterContext(modelIndexer);

		final DocumentFilterDescriptor filterDescriptor = DocumentFilterDescriptor.builder()
				.setFilterId(FullTextSearchSqlDocumentFilterConverter.FILTER_ID)
				.setDisplayName(caption)
				.setFrequentUsed(true)
				.setInlineRenderMode(DocumentFilterInlineRenderMode.INLINE_PARAMETERS)
				.addParameter(DocumentFilterParamDescriptor.builder()
						.setFieldName(FullTextSearchSqlDocumentFilterConverter.PARAM_SearchText)
						.setDisplayName(caption)
						.setWidgetType(DocumentFieldWidgetType.Text))
				.addInternalParameter(DocumentFilterParam.ofNameEqualsValue(FullTextSearchSqlDocumentFilterConverter.PARAM_Context, context))
				.build();

		return ImmutableDocumentFilterDescriptorsProvider.of(filterDescriptor);
	}

	private FullTextSearchFilterContext createFullTextSearchFilterContext(final IESModelIndexer modelIndexer)
	{
		final Client elasticsearchClient = Adempiere.getBean(org.elasticsearch.client.Client.class);

		return FullTextSearchFilterContext.builder()
				.elasticsearchClient(elasticsearchClient)
				.modelTableName(modelIndexer.getModelTableName())
				.esIndexName(modelIndexer.getIndexName())
				.esSearchFieldNames(modelIndexer.getFullTextSearchFieldNames())
				.build();
	}
}
