package de.metas.ui.web.window.descriptor.sql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import javax.annotation.Nullable;

import org.adempiere.ad.table.api.IADTableDAO;
import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.exceptions.DBException;
import org.adempiere.mm.attributes.api.ImmutableAttributeSet;
import org.adempiere.model.I_M_FreightCost;
import org.adempiere.service.ISysConfigBL;
import org.compiere.model.I_M_ProductPrice;
import org.compiere.model.MLookupFactory;
import org.compiere.model.MLookupFactory.LanguageInfo;
import org.compiere.util.CtxName;
import org.compiere.util.CtxNames;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.TimeUtil;
import org.slf4j.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import de.metas.bpartner.BPartnerId;
import de.metas.i18n.ITranslatableString;
import de.metas.i18n.TranslatableStrings;
import de.metas.logging.LogManager;
import de.metas.material.dispo.commons.repository.atp.AvailableToPromiseQuery;
import de.metas.material.dispo.commons.repository.atp.BPartnerClassifier;
import de.metas.pricing.PriceListId;
import de.metas.pricing.PriceListVersionId;
import de.metas.pricing.service.IPriceListDAO;
import de.metas.product.ProductId;
import de.metas.product.model.I_M_Product;
import de.metas.quantity.Quantity;
import de.metas.ui.web.document.filter.sql.SqlParamsCollector;
import de.metas.ui.web.material.adapter.AvailableToPromiseAdapter;
import de.metas.ui.web.material.adapter.AvailableToPromiseResultForWebui;
import de.metas.ui.web.material.adapter.AvailableToPromiseResultForWebui.Group;
import de.metas.ui.web.window.WindowConstants;
import de.metas.ui.web.window.datatypes.LookupValue;
import de.metas.ui.web.window.datatypes.LookupValue.IntegerLookupValue;
import de.metas.ui.web.window.datatypes.LookupValuesList;
import de.metas.ui.web.window.datatypes.WindowId;
import de.metas.ui.web.window.descriptor.DocumentLayoutElementFieldDescriptor.LookupSource;
import de.metas.ui.web.window.descriptor.LookupDescriptor;
import de.metas.ui.web.window.model.lookup.LookupDataSourceContext;
import de.metas.ui.web.window.model.lookup.LookupDataSourceFetcher;
import de.metas.util.Check;
import de.metas.util.Services;
import de.metas.util.StringUtils;
import de.metas.util.lang.CoalesceUtil;
import de.metas.util.time.SystemTime;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2017 metas GmbH
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
 * Product lookup.
 *
 * It is searching by product's Value, Name, UPC and bpartner's ProductNo.
 *
 * @author metas-dev <dev@metasfresh.com>
 * @task https://github.com/metasfresh/metasfresh/issues/2484
 */
public class ProductLookupDescriptor implements LookupDescriptor, LookupDataSourceFetcher
{
	private static final Logger logger = LogManager.getLogger(ProductLookupDescriptor.class);

	private static final String SYSCONFIG_ATP_QUERY_ENABLED = //
			"de.metas.ui.web.window.descriptor.sql.ProductLookupDescriptor.ATP.QueryEnabled";

	private static final String SYSCONFIG_DISPLAY_ATP_ONLY_IF_POSITIVE = //
			"de.metas.ui.web.window.descriptor.sql.ProductLookupDescriptor.ATP.DisplayOnlyPositive";

	private static final String SYSCONFIG_DisableFullTextSearch = //
			"de.metas.ui.web.window.descriptor.sql.ProductLookupDescriptor.DisableFullTextSearch";

	private static final Optional<String> LookupTableName = Optional.of(I_M_Product.Table_Name);
	private static final String CONTEXT_LookupTableName = LookupTableName.get();

	private static final String COLUMNNAME_ProductDisplayName = "ProductDisplayName";

	private final CtxName param_C_BPartner_ID;
	private final CtxName param_PricingDate;
	private final CtxName param_AvailableStockDate;

	private static final CtxName param_M_PriceList_ID = CtxNames.ofNameAndDefaultValue("M_PriceList_ID", "-1");
	private static final CtxName param_AD_Org_ID = CtxNames.ofNameAndDefaultValue(WindowConstants.FIELDNAME_AD_Org_ID, "-1");

	private final Set<CtxName> ctxNamesNeededForQuery;

	private final AvailableToPromiseAdapter availableToPromiseAdapter;

	private static final String ATTRIBUTE_ASI = "asi";

	private final boolean excludeBOMProducts;

	@Getter
	private final int searchStringMinLength;

	@Builder(builderClassName = "BuilderWithStockInfo", builderMethodName = "builderWithStockInfo")
	private ProductLookupDescriptor(
			@NonNull final String bpartnerParamName,
			@NonNull final String pricingDateParamName,
			@NonNull final String availableStockDateParamName,
			@NonNull final AvailableToPromiseAdapter availableToPromiseAdapter,
			final boolean excludeBOMProducts)
	{
		param_C_BPartner_ID = CtxNames.ofNameAndDefaultValue(bpartnerParamName, "-1");
		param_PricingDate = CtxNames.ofNameAndDefaultValue(pricingDateParamName, "NULL");

		param_AvailableStockDate = CtxNames.ofNameAndDefaultValue(availableStockDateParamName, "NULL");
		this.availableToPromiseAdapter = availableToPromiseAdapter;

		this.excludeBOMProducts = excludeBOMProducts;

		ctxNamesNeededForQuery = ImmutableSet.of(param_C_BPartner_ID, param_M_PriceList_ID, param_PricingDate, param_AvailableStockDate, param_AD_Org_ID);

		searchStringMinLength = Services.get(IADTableDAO.class).getTypeaheadMinLength(org.compiere.model.I_M_Product.Table_Name);
	}

	@Builder(builderClassName = "BuilderWithoutStockInfo", builderMethodName = "builderWithoutStockInfo")
	private ProductLookupDescriptor(
			@NonNull final String bpartnerParamName,
			@NonNull final String pricingDateParamName,
			final boolean excludeBOMProducts)
	{
		param_C_BPartner_ID = CtxNames.ofNameAndDefaultValue(bpartnerParamName, "-1");
		param_PricingDate = CtxNames.ofNameAndDefaultValue(pricingDateParamName, "NULL");

		param_AvailableStockDate = null;
		availableToPromiseAdapter = null;

		this.excludeBOMProducts = excludeBOMProducts;

		ctxNamesNeededForQuery = ImmutableSet.of(param_C_BPartner_ID, param_M_PriceList_ID, param_PricingDate, param_AD_Org_ID);

		searchStringMinLength = Services.get(IADTableDAO.class).getTypeaheadMinLength(org.compiere.model.I_M_Product.Table_Name);
	}

	@Override
	public LookupDataSourceContext.Builder newContextForFetchingById(final Object id)
	{
		return LookupDataSourceContext.builder(CONTEXT_LookupTableName).requiresAD_Language().putFilterById(id);
	}

	@Override
	public LookupValue retrieveLookupValueById(final LookupDataSourceContext evalCtx)
	{
		final int id = evalCtx.getIdToFilterAsInt(-1);
		if (id <= 0)
		{
			throw new IllegalStateException("No ID provided in " + evalCtx);
		}

		throw new UnsupportedOperationException();
	}

	@Override
	public LookupDataSourceContext.Builder newContextForFetchingList()
	{
		return LookupDataSourceContext.builder(CONTEXT_LookupTableName)
				.setRequiredParameters(ctxNamesNeededForQuery)
				.requiresAD_Language();
	}

	@Override
	public LookupValuesList retrieveEntities(final LookupDataSourceContext evalCtx)
	{
		if (!isStartSearchForString(evalCtx.getFilter()))
		{
			return LookupValuesList.EMPTY;
		}

		final SqlParamsCollector sqlParams = SqlParamsCollector.newInstance();
		final String sql = buildSql(sqlParams, evalCtx);

		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement(sql, ITrx.TRXNAME_None);
			DB.setParameters(pstmt, sqlParams.toList());
			rs = pstmt.executeQuery();

			final Map<Integer, LookupValue> valuesById = new LinkedHashMap<>();
			while (rs.next())
			{
				final LookupValue value = loadLookupValue(rs);
				valuesById.putIfAbsent(value.getIdAsInt(), value);
			}

			final LookupValuesList unexplodedLookupValues = LookupValuesList.fromCollection(valuesById.values());

			final Date stockdateOrNull = getEffectiveStockDateOrNull(evalCtx);
			if (stockdateOrNull == null || availableToPromiseAdapter == null)
			{
				return unexplodedLookupValues;
			}
			final BPartnerId bpartnerId = BPartnerId.ofRepoIdOrNull(param_C_BPartner_ID.getValueAsInteger(evalCtx));
			return explodeRecordsWithStockQuantities(
					unexplodedLookupValues,
					bpartnerId,
					stockdateOrNull);
		}
		catch (final SQLException ex)
		{
			throw new DBException(ex, sql, sqlParams.toList());
		}
		finally
		{
			DB.close(rs, pstmt);
		}
	}

	private boolean isStartSearchForString(final String filter)
	{
		final int searchMinLength = getSearchStringMinLength();
		if (searchMinLength <= 0)
		{
			return true;
		}

		if (filter == null || filter.isEmpty())
		{
			return false;
		}

		if (filter == LookupDataSourceContext.FILTER_Any)
		{
			return false;
		}

		return filter.trim().length() >= searchMinLength;
	}

	private Date getEffectiveStockDateOrNull(final LookupDataSourceContext evalCtx)
	{
		if (param_AvailableStockDate == null)
		{
			return null;
		}
		final Date stockdateOrNull = param_AvailableStockDate.getValueAsDate(evalCtx);
		return stockdateOrNull;
	}

	private String buildSql(
			@NonNull final SqlParamsCollector sqlParams,
			@NonNull final LookupDataSourceContext evalCtx)
	{
		//
		// Build the SQL filter
		final StringBuilder sqlWhereClause = new StringBuilder();
		final SqlParamsCollector sqlWhereClauseParams = SqlParamsCollector.newInstance();
		appendFilterByIsActive(sqlWhereClause, sqlWhereClauseParams);
		appendFilterBySearchString(sqlWhereClause, sqlWhereClauseParams, evalCtx.getFilter(), isFullTextSearchEnabled());
		appendFilterById(sqlWhereClause, sqlWhereClauseParams, evalCtx);
		appendFilterByBPartner(sqlWhereClause, sqlWhereClauseParams, evalCtx);
		appendFilterByPriceList(sqlWhereClause, sqlWhereClauseParams, evalCtx);
		appendFilterByNotFreightCostProduct(sqlWhereClause, sqlWhereClauseParams, evalCtx);
		appendFilterByOrg(sqlWhereClause, sqlWhereClauseParams, evalCtx);
		appendFilterBOMProducts(sqlWhereClause, sqlWhereClauseParams, evalCtx);

		//
		// SQL: SELECT ... FROM
		final String sqlDisplayName = MLookupFactory.getLookup_TableDirEmbed(
				LanguageInfo.ofSpecificLanguage(evalCtx.getAD_Language()),
				org.compiere.model.I_M_Product.COLUMNNAME_M_Product_ID, // columnName
				null, // baseTable
				"p." + I_M_Product_Lookup_V.COLUMNNAME_M_Product_ID);
		final StringBuilder sql = new StringBuilder("SELECT"
				+ "\n p." + I_M_Product_Lookup_V.COLUMNNAME_M_Product_ID
				+ "\n, (" + sqlDisplayName + ") AS " + COLUMNNAME_ProductDisplayName
				+ "\n, p." + I_M_Product_Lookup_V.COLUMNNAME_UPC
				+ "\n, p." + I_M_Product_Lookup_V.COLUMNNAME_C_BPartner_ID
				+ "\n, p." + I_M_Product_Lookup_V.COLUMNNAME_BPartnerProductNo
				+ "\n, p." + I_M_Product_Lookup_V.COLUMNNAME_BPartnerProductName
				+ "\n, p." + I_M_Product_Lookup_V.COLUMNNAME_AD_Org_ID
				+ "\n, p." + I_M_Product_Lookup_V.COLUMNNAME_IsActive
				+ "\n, p." + I_M_Product_Lookup_V.COLUMNNAME_IsBOM
				+ "\n, p." + I_M_Product_Lookup_V.COLUMNNAME_Value
				+ "\n, p." + I_M_Product_Lookup_V.COLUMNNAME_Name
				+ "\n FROM " + I_M_Product_Lookup_V.Table_Name + " p ");
		sql.insert(0, "SELECT * FROM (").append(") p");

		//
		// SQL: WHERE
		sql.append("\n WHERE ").append(sqlWhereClause);
		sqlParams.collect(sqlWhereClauseParams);

		//
		// SQL: ORDER BY
		sql.append("\n ORDER BY ")
				.append("p." + COLUMNNAME_ProductDisplayName)
				.append(", p." + I_M_Product_Lookup_V.COLUMNNAME_C_BPartner_ID + " DESC NULLS LAST");

		// SQL: LIMIT and OFFSET
		sql.append("\n LIMIT ").append(sqlParams.placeholder(evalCtx.getLimit(100)));
		sql.append("\n OFFSET ").append(sqlParams.placeholder(evalCtx.getOffset(0)));

		return sql.toString();
	}

	private static StringBuilder appendFilterByIsActive(final StringBuilder sqlWhereClause, final SqlParamsCollector sqlWhereClauseParams)
	{
		return sqlWhereClause.append("\n p.").append(I_M_Product_Lookup_V.COLUMNNAME_IsActive).append("=").append(sqlWhereClauseParams.placeholder(true));
	}

	private static void appendFilterBySearchString(
			final StringBuilder sqlWhereClause,
			final SqlParamsCollector sqlWhereClauseParams,
			final String filter,
			final boolean fullTextSearchEnabled)
	{
		if (filter == LookupDataSourceContext.FILTER_Any)
		{
			// no filtering, we are matching everything
			return;
		}
		if (Check.isEmpty(filter, true))
		{
			// same, consider it as no filtering
			return;
		}

		final String sqlFilter = convertFilterToSql(filter);

		if (fullTextSearchEnabled)
		{
			sqlWhereClause.append("\n AND (")
					.append(" ").append("p." + COLUMNNAME_ProductDisplayName + " ILIKE ").append(sqlWhereClauseParams.placeholder(sqlFilter))
					.append(" OR ").append("p." + I_M_Product_Lookup_V.COLUMNNAME_UPC + " ILIKE ").append(sqlWhereClauseParams.placeholder(sqlFilter))
					.append(" OR ").append("p." + I_M_Product_Lookup_V.COLUMNNAME_BPartnerProductNo + " ILIKE ").append(sqlWhereClauseParams.placeholder(sqlFilter))
					.append(" OR ").append("p." + I_M_Product_Lookup_V.COLUMNNAME_BPartnerProductName + " ILIKE ").append(sqlWhereClauseParams.placeholder(sqlFilter))
					.append(")");

		}
		else
		{
			sqlWhereClause.append("\n AND (")
					.append(" p." + I_M_Product_Lookup_V.COLUMNNAME_Value + " ILIKE ").append(sqlWhereClauseParams.placeholder(sqlFilter))
					.append(" OR p." + I_M_Product_Lookup_V.COLUMNNAME_Name + " ILIKE ").append(sqlWhereClauseParams.placeholder(sqlFilter))
					.append(")");
		}
	}

	private static void appendFilterById(final StringBuilder sqlWhereClause, final SqlParamsCollector sqlWhereClauseParams, final LookupDataSourceContext evalCtx)
	{
		final Integer idToFilter = evalCtx.getIdToFilterAsInt(-1);
		if (idToFilter != null && idToFilter > 0)
		{
			sqlWhereClause.append("\n AND p.").append(I_M_Product_Lookup_V.COLUMNNAME_M_Product_ID).append(sqlWhereClauseParams.placeholder(idToFilter));
		}
	}

	private void appendFilterByBPartner(final StringBuilder sqlWhereClause, final SqlParamsCollector sqlWhereClauseParams, final LookupDataSourceContext evalCtx)
	{
		final int bpartnerId = param_C_BPartner_ID.getValueAsInteger(evalCtx);
		if (bpartnerId > 0)
		{
			sqlWhereClause.append("\n AND (p." + I_M_Product_Lookup_V.COLUMNNAME_C_BPartner_ID + "=").append(sqlWhereClauseParams.placeholder(bpartnerId))
					.append(" OR p." + I_M_Product_Lookup_V.COLUMNNAME_C_BPartner_ID + " IS NULL)");
		}
	}

	private void appendFilterByPriceList(
			@NonNull final StringBuilder sqlWhereClause,
			@NonNull final SqlParamsCollector sqlWhereClauseParams,
			@NonNull final LookupDataSourceContext evalCtx)
	{
		final PriceListVersionId priceListVersionId = getPriceListVersionId(evalCtx);
		if (priceListVersionId == null)
		{
			return;
		}

		final IPriceListDAO priceListsRepo = Services.get(IPriceListDAO.class);
		final List<PriceListVersionId> allPriceListVersionIds = priceListsRepo.getPriceListVersionIdsUpToBase(priceListVersionId);

		sqlWhereClause.append("\n AND EXISTS (")
				.append("SELECT 1 FROM " + I_M_ProductPrice.Table_Name + " pp WHERE pp.M_Product_ID=p." + I_M_Product_Lookup_V.COLUMNNAME_M_Product_ID)
				.append(" AND pp.").append(I_M_ProductPrice.COLUMNNAME_M_PriceList_Version_ID).append(" IN ").append(DB.buildSqlList(allPriceListVersionIds, sqlWhereClauseParams::collectAll))
				.append(" AND pp.IsActive=").append(sqlWhereClauseParams.placeholder(true))
				.append(")");
	}

	private static void appendFilterByNotFreightCostProduct(final StringBuilder sqlWhereClause, final SqlParamsCollector sqlWhereClauseParams, final LookupDataSourceContext evalCtx)
	{
		final Integer adOrgId = param_AD_Org_ID.getValueAsInteger(evalCtx);

		sqlWhereClause.append("\n AND NOT EXISTS (")
				.append("SELECT 1 FROM " + I_M_FreightCost.Table_Name + " fc WHERE fc.M_Product_ID=p." + I_M_Product_Lookup_V.COLUMNNAME_M_Product_ID)
				.append(" AND fc.AD_Org_ID IN (0, ").append(sqlWhereClauseParams.placeholder(adOrgId)).append(")")
				.append(")");
	}

	private static void appendFilterByOrg(final StringBuilder sqlWhereClause, final SqlParamsCollector sqlWhereClauseParams, final LookupDataSourceContext evalCtx)
	{
		final Integer adOrgId = param_AD_Org_ID.getValueAsInteger(evalCtx);
		sqlWhereClause.append("\n AND p.AD_Org_ID IN (0, ").append(sqlWhereClauseParams.placeholder(adOrgId)).append(")");
	}

	private void appendFilterBOMProducts(final StringBuilder sqlWhereClause, final SqlParamsCollector sqlWhereClauseParams, final LookupDataSourceContext evalCtx)
	{
		if (!excludeBOMProducts)
		{
			return;
		}

		sqlWhereClause.append("\n AND p." + I_M_Product_Lookup_V.COLUMNNAME_IsBOM + "=" + sqlWhereClauseParams.placeholder(false));
	}

	private static final String convertFilterToSql(final String filter)
	{
		String sqlFilter = filter.trim();
		if (sqlFilter.contains("%"))
		{
			return sqlFilter;
		}

		if (!sqlFilter.startsWith("%"))
		{
			sqlFilter = "%" + sqlFilter;
		}
		if (!sqlFilter.endsWith("%"))
		{
			sqlFilter = sqlFilter + "%";
		}

		return sqlFilter;
	}

	private static LookupValue loadLookupValue(final ResultSet rs) throws SQLException
	{
		final int productId = rs.getInt(I_M_Product_Lookup_V.COLUMNNAME_M_Product_ID);

		final String name = rs.getString(COLUMNNAME_ProductDisplayName);
		final String bpartnerProductNo = rs.getString(I_M_Product_Lookup_V.COLUMNNAME_BPartnerProductNo);
		final String displayName = Joiner.on("_").skipNulls().join(name, bpartnerProductNo);

		final boolean active = StringUtils.toBoolean(rs.getString(I_M_Product_Lookup_V.COLUMNNAME_IsActive));

		return IntegerLookupValue.builder()
				.id(productId)
				.displayName(TranslatableStrings.anyLanguage(displayName))
				.active(active)
				.build();
	}

	private final PriceListVersionId getPriceListVersionId(final LookupDataSourceContext evalCtx)
	{
		final PriceListId priceListId = PriceListId.ofRepoIdOrNull(param_M_PriceList_ID.getValueAsInteger(evalCtx));
		if (priceListId == null)
		{
			return null;
		}

		final LocalDate date = getEffectivePricingDate(evalCtx);
		return Services.get(IPriceListDAO.class).retrievePriceListVersionIdOrNull(priceListId, date);
	}

	private LocalDate getEffectivePricingDate(@NonNull final LookupDataSourceContext evalCtx)
	{
		return CoalesceUtil.coalesceSuppliers(
				() -> param_PricingDate.getValueAsLocalDate(evalCtx),
				() -> SystemTime.asLocalDate());
	}

	@Override
	public boolean isCached()
	{
		return true;
	}

	@Override
	public String getCachePrefix()
	{
		return null; // not relevant
	}

	@Override
	public void cacheInvalidate()
	{
	}

	@Override
	public Optional<String> getLookupTableName()
	{
		return LookupTableName;
	}

	@Override
	public LookupDataSourceFetcher getLookupDataSourceFetcher()
	{
		return this;
	}

	@Override
	public boolean isHighVolume()
	{
		return true;
	}

	@Override
	public LookupSource getLookupSourceType()
	{
		return LookupSource.lookup;
	}

	@Override
	public boolean isNumericKey()
	{
		return true;
	}

	@Override
	public boolean hasParameters()
	{
		return true;
	}

	@Override
	public Set<String> getDependsOnFieldNames()
	{
		return CtxNames.toNames(ctxNamesNeededForQuery);
	}

	@Override
	public Optional<WindowId> getZoomIntoWindowId()
	{
		return Optional.empty();
	}

	private final LookupValuesList explodeRecordsWithStockQuantities(
			@NonNull final LookupValuesList productLookupValues,
			@Nullable final BPartnerId bpartnerId,
			@NonNull final Date dateOrNull)
	{
		if (productLookupValues.isEmpty() || !isAvailableStockQueryActivatedInSysConfig())
		{
			return productLookupValues;
		}

		final AvailableToPromiseQuery query = AvailableToPromiseQuery.builder()
				.productIds(productLookupValues.getKeysAsInt())
				.storageAttributesKeys(availableToPromiseAdapter.getPredefinedStorageAttributeKeys())
				.date(TimeUtil.asZonedDateTime(dateOrNull))
				.bpartner(BPartnerClassifier.specificOrNone(bpartnerId))
				.build();
		final AvailableToPromiseResultForWebui availableStock = availableToPromiseAdapter.retrieveAvailableStock(query);
		final List<Group> availableStockGroups = availableStock.getGroups();

		return explodeLookupValuesByAvailableStockGroups(
				productLookupValues,
				availableStockGroups,
				isDisplayATPOnlyIfPositive());
	}

	private boolean isAvailableStockQueryActivatedInSysConfig()
	{
		final ISysConfigBL sysConfigBL = Services.get(ISysConfigBL.class);
		final int clientId = Env.getAD_Client_ID(Env.getCtx());
		final int orgId = Env.getAD_Org_ID(Env.getCtx());

		final boolean stockQueryActivated = sysConfigBL.getBooleanValue(
				SYSCONFIG_ATP_QUERY_ENABLED,
				false, clientId, orgId);
		return stockQueryActivated;
	}

	@VisibleForTesting
	static LookupValuesList explodeLookupValuesByAvailableStockGroups(
			@NonNull final LookupValuesList initialLookupValues,
			@NonNull final List<Group> availableStockGroups,
			final boolean displayATPOnlyIfPositive)
	{
		final LinkedHashSet<ProductWithATP> productWithATPs = new LinkedHashSet<>();
		for (final Group availableStockGroup : availableStockGroups)
		{
			final int productId = availableStockGroup.getProductId();
			final LookupValue productLookupValue = initialLookupValues.getById(productId);

			// avoid NPE, shall not happen
			if (productLookupValue == null)
			{
				logger.warn("No product lookup value found for productId={} in {}. Skipping group: {}", productId, initialLookupValues, availableStockGroup);
				continue;
			}

			final ProductWithATP.ProductWithATPBuilder productWithATP = ProductWithATP.builder()
					.productId(productId)
					.productDisplayName(productLookupValue.getDisplayNameTrl());

			//
			// Include ATP:
			final boolean displayATP = !displayATPOnlyIfPositive || availableStockGroup.getQty().signum() > 0;
			if (displayATP)
			{
				productWithATP
						.qtyATP(availableStockGroup.getQty())
						.uomSymbolStr(availableStockGroup.getUomSymbolStr())
						.attributeMap(availableStockGroup.getLookupAttributesMap())
						.storageAttributesString(availableStockGroup.getStorageAttributesString());
			}

			productWithATPs.add(productWithATP.build());
		}

		if (productWithATPs.isEmpty())
		{
			return initialLookupValues; // fallback
		}

		return productWithATPs.stream()
				.map(productWithATP -> createProductLookupValue(productWithATP))
				.collect(LookupValuesList.collect());
	}

	private static IntegerLookupValue createProductLookupValue(final ProductWithATP productWithATP)
	{
		return IntegerLookupValue.builder()
				.id(productWithATP.getProductId())
				.displayName(createDisplayName(productWithATP))
				.attribute(ATTRIBUTE_ASI, productWithATP.getAttributeMap())
				.build();
	}

	private static ITranslatableString createDisplayName(final ProductWithATP productWithATP)
	{
		final ITranslatableString productDisplayName = productWithATP.getProductDisplayName();
		final Quantity qtyATP = productWithATP.getQtyATP();

		//
		// ATP is not available => return only the product display name
		if (qtyATP == null)
		{
			return productDisplayName;
		}
		//
		// ATP is available:
		else
		{
			final ITranslatableString qtyValueStr = TranslatableStrings.number(qtyATP.getAsBigDecimal(), DisplayType.Quantity);

			final ITranslatableString uomSymbolStr = productWithATP.getUomSymbolStr();
			final ITranslatableString storageAttributeString = productWithATP.getStorageAttributesString();

			return TranslatableStrings.join("",
					productDisplayName,
					": ", qtyValueStr, " ", uomSymbolStr,
					" (", storageAttributeString, ")");
		}
	}

	@Value
	@Builder
	private static class ProductWithATP
	{
		int productId;
		@NonNull
		ITranslatableString productDisplayName;

		//
		// Available to promise info (ATP)
		Quantity qtyATP;
		ITranslatableString uomSymbolStr;
		ITranslatableString storageAttributesString;
		@NonNull
		@Default
		ImmutableMap<String, Object> attributeMap = ImmutableMap.of();
	}

	private boolean isDisplayATPOnlyIfPositive()
	{
		final Properties ctx = Env.getCtx();

		return Services.get(ISysConfigBL.class).getBooleanValue(
				SYSCONFIG_DISPLAY_ATP_ONLY_IF_POSITIVE,
				true,
				Env.getAD_Client_ID(ctx), Env.getAD_Org_ID(ctx));
	}

	public static ProductAndAttributes toProductAndAttributes(@NonNull final LookupValue lookupValue)
	{
		final ProductId productId = lookupValue.getIdAs(ProductId::ofRepoId);

		final Map<Object, Object> valuesByAttributeIdMap = lookupValue.getAttribute(ATTRIBUTE_ASI);
		final ImmutableAttributeSet attributes = ImmutableAttributeSet.ofValuesByAttributeIdMap(valuesByAttributeIdMap);

		return ProductAndAttributes.builder()
				.productId(productId)
				.attributes(attributes)
				.build();
	}

	private boolean isFullTextSearchEnabled()
	{
		final boolean disabled = Services.get(ISysConfigBL.class).getBooleanValue(SYSCONFIG_DisableFullTextSearch, false);
		return !disabled;
	}

	@Value
	@Builder
	public static class ProductAndAttributes
	{
		@NonNull
		private final ProductId productId;

		@Default
		@NonNull
		private final ImmutableAttributeSet attributes = ImmutableAttributeSet.EMPTY;
	}

	private interface I_M_Product_Lookup_V
	{
		String Table_Name = "M_Product_Lookup_V";

		String COLUMNNAME_AD_Org_ID = "AD_Org_ID";
		String COLUMNNAME_IsActive = "IsActive";
		String COLUMNNAME_M_Product_ID = "M_Product_ID";
		String COLUMNNAME_Value = "Value";
		String COLUMNNAME_Name = "Name";
		String COLUMNNAME_UPC = "UPC";
		String COLUMNNAME_BPartnerProductNo = "BPartnerProductNo";
		String COLUMNNAME_BPartnerProductName = "BPartnerProductName";
		String COLUMNNAME_C_BPartner_ID = "C_BPartner_ID";

		String COLUMNNAME_IsBOM = "IsBOM";
	}
}
