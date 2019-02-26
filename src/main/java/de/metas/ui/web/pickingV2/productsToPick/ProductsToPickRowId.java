package de.metas.ui.web.pickingV2.productsToPick;

import javax.annotation.Nullable;

import de.metas.handlingunits.HuId;
import de.metas.inoutcandidate.api.ShipmentScheduleId;
import de.metas.product.ProductId;
import de.metas.ui.web.window.datatypes.DocumentId;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

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

@EqualsAndHashCode
@ToString(of = "documentId")
final class ProductsToPickRowId
{
	@Getter
	private final HuId huId;

	private final DocumentId documentId;

	@Builder
	private ProductsToPickRowId(
			@NonNull final ProductId productId,
			@NonNull ShipmentScheduleId shipmentScheduleId,
			@Nullable final HuId huId)
	{
		this.huId = huId;
		// this.productId = productId;
		this.documentId = createDocumentId(productId, shipmentScheduleId, huId);
	}

	public DocumentId toDocumentId()
	{
		return documentId;
	}

	private static DocumentId createDocumentId(
			@NonNull final ProductId productId,
			final ShipmentScheduleId shipmentScheduleId,
			final HuId huId)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("P").append(productId.getRepoId());
		sb.append("_").append("S").append(shipmentScheduleId.getRepoId());

		if (huId != null)
		{
			sb.append("_").append("HU").append(huId.getRepoId());
		}

		return DocumentId.ofString(sb.toString());
	}

}
