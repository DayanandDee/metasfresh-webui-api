package de.metas.ui.web.pickingV2.productsToPick;

import org.springframework.stereotype.Repository;

import de.metas.handlingunits.picking.PickingCandidateRepository;
import de.metas.handlingunits.picking.PickingCandidateService;
import de.metas.ui.web.order.sales.hu.reservation.HUReservationDocumentFilterService;
import de.metas.ui.web.pickingV2.packageable.PackageableRow;
import lombok.NonNull;

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

@Repository
public class ProductsToPickRowsRepository
{
	private final HUReservationDocumentFilterService huReservationService;
	private final PickingCandidateRepository pickingCandidateRepo;
	private final PickingCandidateService pickingCandidateService;

	public ProductsToPickRowsRepository(
			@NonNull final HUReservationDocumentFilterService huReservationService,
			@NonNull final PickingCandidateRepository pickingCandidateRepo,
			@NonNull final PickingCandidateService pickingCandidateService)
	{
		this.huReservationService = huReservationService;
		this.pickingCandidateRepo = pickingCandidateRepo;
		this.pickingCandidateService = pickingCandidateService;
	}

	public ProductsToPickRowsData createProductsToPickRowsData(final PackageableRow packageableRow)
	{
		return newProductsToPickRowsFactory()
				.create(packageableRow);
	}

	private ProductsToPickRowsDataFactory newProductsToPickRowsFactory()
	{
		return ProductsToPickRowsDataFactory.builder()
				.huReservationService(huReservationService)
				.pickingCandidateRepo(pickingCandidateRepo)
				.pickingCandidateService(pickingCandidateService)
				.build();
	}
}
