package de.metas.ui.web.order.products_proposal.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Properties;

import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.I_C_Order;
import org.compiere.model.I_C_OrderLine;
import org.compiere.util.Env;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableList;

import de.metas.adempiere.callout.OrderFastInput;
import de.metas.adempiere.gui.search.HUPackingAwareCopy.ASICopyMode;
import de.metas.adempiere.gui.search.IHUPackingAware;
import de.metas.adempiere.gui.search.IHUPackingAwareBL;
import de.metas.adempiere.gui.search.impl.OrderLineHUPackingAware;
import de.metas.adempiere.gui.search.impl.PlainHUPackingAware;
import de.metas.logging.LogManager;
import de.metas.order.IOrderDAO;
import de.metas.order.OrderId;
import de.metas.product.IProductBL;
import de.metas.ui.web.order.products_proposal.model.ProductProposalPrice;
import de.metas.ui.web.order.products_proposal.model.ProductsProposalRow;
import de.metas.uom.UomId;
import de.metas.util.Services;
import lombok.Builder;
import lombok.NonNull;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2019 metas GmbH
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

public final class OrderLinesFromProductProposalsProducer
{
	private static final Logger logger = LogManager.getLogger(OrderLinesFromProductProposalsProducer.class);
	private final ITrxManager trxManager = Services.get(ITrxManager.class);
	private final IOrderDAO ordersRepo = Services.get(IOrderDAO.class);
	private final IHUPackingAwareBL huPackingAwareBL = Services.get(IHUPackingAwareBL.class);
	private final IProductBL productBL = Services.get(IProductBL.class);

	private final OrderId orderId;
	private final ImmutableList<ProductsProposalRow> rows;

	@Builder
	private OrderLinesFromProductProposalsProducer(
			@NonNull final OrderId orderId,
			@NonNull final List<ProductsProposalRow> rows)
	{
		this.orderId = orderId;
		this.rows = ImmutableList.copyOf(rows);
	}

	public void produce()
	{
		if (rows.isEmpty())
		{
			return;
		}

		trxManager.runInNewTrx(this::produceInTrx);
	}

	private void produceInTrx()
	{
		final Properties ctx = Env.getCtx();
		final I_C_Order order = ordersRepo.getById(orderId);

		for (final ProductsProposalRow row : rows)
		{
			OrderFastInput.addOrderLine(ctx, order, orderLine -> updateOrderLine(order, orderLine, row));
		}
	}

	private void updateOrderLine(
			final I_C_Order order,
			final I_C_OrderLine newOrderLine,
			final ProductsProposalRow fromRow)
	{
		final IHUPackingAware rowPackingAware = createHUPackingAware(order, fromRow);
		final IHUPackingAware orderLinePackingAware = OrderLineHUPackingAware.of(newOrderLine);

		huPackingAwareBL.prepareCopyFrom(rowPackingAware)
				.overridePartner(false)
				.asiCopyMode(ASICopyMode.CopyID) // because we just created the ASI
				.copyTo(orderLinePackingAware);

		final ProductProposalPrice price = fromRow.getPrice();
		// IMPORTANT: manual price is always true because we want to make sure the price the sales guy saw in proposals list is the price which gets into order line
		newOrderLine.setIsManualPrice(true);
		newOrderLine.setPriceEntered(price.getUserEnteredPriceValue());
	}

	private IHUPackingAware createHUPackingAware(
			@NonNull final I_C_Order order,
			@NonNull final ProductsProposalRow fromRow)
	{
		final PlainHUPackingAware huPackingAware = createAndInitHUPackingAware(order, fromRow);

		final BigDecimal qty = fromRow.getQty();
		if (qty == null || qty.signum() <= 0)
		{
			throw new AdempiereException("Qty shall be greather than zero"); // TODO trl
		}

		huPackingAwareBL.computeAndSetQtysForNewHuPackingAware(huPackingAware, qty);
		validateNewHUPackingAware(huPackingAware);

		return huPackingAware;
	}

	private PlainHUPackingAware createAndInitHUPackingAware(
			@NonNull final I_C_Order order,
			@NonNull final ProductsProposalRow fromRow)
	{

		final PlainHUPackingAware huPackingAware = new PlainHUPackingAware();
		huPackingAware.setC_BPartner_ID(order.getC_BPartner_ID());
		huPackingAware.setDateOrdered(order.getDateOrdered());
		huPackingAware.setInDispute(false);

		final UomId uomId = productBL.getStockingUOMId(fromRow.getProductId());
		huPackingAware.setM_Product_ID(fromRow.getProductId().getRepoId());
		huPackingAware.setC_UOM_ID(uomId.getRepoId());
		// huPackingAware.setM_AttributeSetInstance_ID(...);
		// huPackingAware.setM_HU_PI_Item_Product_ID(...);

		return huPackingAware;
	}

	private void validateNewHUPackingAware(@NonNull final PlainHUPackingAware huPackingAware)
	{
		if (huPackingAware.getQty() == null || huPackingAware.getQty().signum() <= 0)
		{
			logger.warn("Invalid Qty={} for {}", huPackingAware.getQty(), huPackingAware);
			throw new AdempiereException("Qty shall be greather than zero"); // TODO trl
		}
		if (huPackingAware.getQtyTU() == null || huPackingAware.getQtyTU().signum() <= 0)
		{
			logger.warn("Invalid QtyTU={} for {}", huPackingAware.getQtyTU(), huPackingAware);
			throw new AdempiereException("QtyTU shall be greather than zero"); // TODO trl
		}
	}
}
