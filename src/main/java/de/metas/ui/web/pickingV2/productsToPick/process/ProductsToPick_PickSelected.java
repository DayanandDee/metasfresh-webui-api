package de.metas.ui.web.pickingV2.productsToPick.process;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;

import de.metas.handlingunits.picking.PickingCandidateService;
import de.metas.handlingunits.picking.candidate.commands.PickHUResult;
import de.metas.handlingunits.picking.requests.PickHURequest;
import de.metas.process.ProcessPreconditionsResolution;
import de.metas.process.RunOutOfTrx;
import de.metas.ui.web.pickingV2.productsToPick.ProductsToPickRow;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2018 metas GmbH
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

public class ProductsToPick_PickSelected extends ProductsToPickViewBasedProcess
{
	@Autowired
	private PickingCandidateService pickingCandidatesService;

	@Override
	protected ProcessPreconditionsResolution checkPreconditionsApplicable()
	{
		final List<ProductsToPickRow> selectedRows = getSelectedRows();
		if (selectedRows.isEmpty())
		{
			return ProcessPreconditionsResolution.rejectBecauseNoSelection();
		}

		if (!selectedRows.stream().allMatch(ProductsToPickRow::isEligibleForPicking))
		{
			return ProcessPreconditionsResolution.rejectWithInternalReason("select only rows that can be picked");
		}

		return ProcessPreconditionsResolution.accept();
	}

	@Override
	@RunOutOfTrx
	protected String doIt()
	{
		getSelectedRows()
				.stream()
				.filter(ProductsToPickRow::isEligibleForPicking)
				.forEach(this::pickRow);

		invalidateView();

		return MSG_OK;
	}

	private void pickRow(final ProductsToPickRow row)
	{
		final PickHUResult result = pickingCandidatesService.pickHU(PickHURequest.builder()
				.shipmentScheduleId(row.getShipmentScheduleId())
				.qtyToPick(row.getQty())
				.pickFromHuId(row.getHuId())
				.build());

		updateViewRowFromPickingCandidate(row.getId(), result.getPickingCandidate());
	}
}
