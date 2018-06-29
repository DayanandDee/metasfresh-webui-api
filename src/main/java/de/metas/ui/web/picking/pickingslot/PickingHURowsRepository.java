package de.metas.ui.web.picking.pickingslot;

import static org.adempiere.model.InterfaceWrapperHelper.load;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.dao.IQueryBuilder;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.GuavaCollectors;
import org.adempiere.util.Services;
import org.compiere.model.IQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableListMultimap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.SetMultimap;

import de.metas.handlingunits.HuId;
import de.metas.handlingunits.model.I_M_HU;
import de.metas.handlingunits.model.I_M_Picking_Candidate;
import de.metas.handlingunits.model.I_M_ShipmentSchedule;
import de.metas.handlingunits.model.X_M_HU;
import de.metas.handlingunits.model.X_M_Picking_Candidate;
import de.metas.handlingunits.picking.IHUPickingSlotDAO;
import de.metas.handlingunits.reservation.HUReservationService;
import de.metas.handlingunits.sourcehu.SourceHUsService;
import de.metas.handlingunits.sourcehu.SourceHUsService.MatchingSourceHusQuery;
import de.metas.handlingunits.sourcehu.SourceHUsService.MatchingSourceHusQuery.MatchingSourceHusQueryBuilder;
import de.metas.inoutcandidate.api.IShipmentScheduleEffectiveBL;
import de.metas.picking.api.IPickingSlotDAO;
import de.metas.picking.api.IPickingSlotDAO.PickingSlotQuery;
import de.metas.picking.model.I_M_PickingSlot;
import de.metas.printing.esb.base.util.Check;
import de.metas.ui.web.handlingunits.DefaultHUEditorViewFactory;
import de.metas.ui.web.handlingunits.HUEditorRow;
import de.metas.ui.web.handlingunits.HUEditorRowAttributesProvider;
import de.metas.ui.web.handlingunits.HUEditorRowFilter;
import de.metas.ui.web.handlingunits.HUEditorViewRepository;
import de.metas.ui.web.handlingunits.SqlHUEditorViewRepository;
import de.metas.ui.web.picking.PickingConstants;
import lombok.NonNull;

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
 * This class is used by {@link PickingSlotViewRepository} and provides the HUs that are related to picking.
 *
 * @author metas-dev <dev@metasfresh.com>
 *
 */
@Service
public class PickingHURowsRepository
{
	private final HUEditorViewRepository huEditorRepo;

	@Autowired
	public PickingHURowsRepository(
			@NonNull final DefaultHUEditorViewFactory huEditorViewFactory,
			@NonNull final HUReservationService huReservationService)
	{
		this(createDefaultHUEditorViewRepository(huEditorViewFactory, huReservationService));
	}

	private static SqlHUEditorViewRepository createDefaultHUEditorViewRepository(
			@NonNull final DefaultHUEditorViewFactory huEditorViewFactory,
			@NonNull final HUReservationService huReservationService)
	{
		return SqlHUEditorViewRepository.builder()
				.windowId(PickingConstants.WINDOWID_PickingSlotView)
				.attributesProvider(HUEditorRowAttributesProvider.builder().readonly(true).build())
				.sqlViewBinding(huEditorViewFactory.getSqlViewBinding())
				.huReservationService(huReservationService)
				.build();
	}

	/**
	 * Creates an instance using the given {@code huEditorRepo}. Intended for testing.
	 *
	 * @param huEditorRepo
	 */
	@VisibleForTesting
	PickingHURowsRepository(@NonNull final HUEditorViewRepository huEditorRepo)
	{
		this.huEditorRepo = huEditorRepo;
	}

	/**
	 * Retrieve the union of all HUs that match any one of the given shipment schedule IDs and that are flagged to be fine picking source HUs.
	 *
	 * @param shipmentScheduleIds
	 * @return
	 */
	public List<HUEditorRow> retrieveSourceHUs(@NonNull final PickingSlotRepoQuery query)
	{
		final MatchingSourceHusQuery matchingSourceHUsQuery = createMatchingSourceHusQuery(query);
		final Set<HuId> sourceHUIds = SourceHUsService.get().retrieveMatchingSourceHUIds(matchingSourceHUsQuery);
		return huEditorRepo.retrieveHUEditorRows(sourceHUIds, HUEditorRowFilter.ALL);
	}

	@VisibleForTesting
	static MatchingSourceHusQuery createMatchingSourceHusQuery(@NonNull final PickingSlotRepoQuery query)
	{
		final I_M_ShipmentSchedule currentShipmentSchedule = query.getCurrentShipmentScheduleId() > 0 ? load(query.getCurrentShipmentScheduleId(), I_M_ShipmentSchedule.class) : null;
		final List<Integer> productIds;
		final Set<Integer> allShipmentScheduleIds = query.getShipmentScheduleIds();
		if (allShipmentScheduleIds.isEmpty())
		{
			productIds = ImmutableList.of();
		}
		else if (allShipmentScheduleIds.size() == 1)
		{
			productIds = ImmutableList.of(currentShipmentSchedule.getM_Product_ID());
		}
		else
		{
			productIds = Services.get(IQueryBL.class)
					.createQueryBuilder(I_M_ShipmentSchedule.class)
					.addInArrayFilter(I_M_ShipmentSchedule.COLUMNNAME_M_ShipmentSchedule_ID, allShipmentScheduleIds)
					.create()
					.listDistinct(I_M_ShipmentSchedule.COLUMNNAME_M_Product_ID, Integer.class);
		}

		final MatchingSourceHusQueryBuilder builder = MatchingSourceHusQuery.builder()
				.productIds(productIds);

		final IShipmentScheduleEffectiveBL shipmentScheduleEffectiveBL = Services.get(IShipmentScheduleEffectiveBL.class);
		final int effectiveWarehouseId = currentShipmentSchedule != null ? shipmentScheduleEffectiveBL.getWarehouseId(currentShipmentSchedule) : -1;
		builder.warehouseId(effectiveWarehouseId);
		return builder.build();
	}

	/**
	 *
	 * @param pickingSlotRowQuery determines which {@code M_ShipmentSchedule_ID}s this is about,<br>
	 *            and also (optionally) if the returned rows shall have picking candidates with a certain status.
	 *
	 * @return a multi-map where the keys are {@code M_PickingSlot_ID}s and the value is a list of HUEditorRows which also contain with the respective {@code M_Picking_Candidate}s' {@code processed} states.
	 */
	public ListMultimap<Integer, PickedHUEditorRow> retrievePickedHUsIndexedByPickingSlotId(@NonNull final PickingSlotRepoQuery pickingSlotRowQuery)
	{
		final List<I_M_Picking_Candidate> pickingCandidates = retrievePickingCandidates(pickingSlotRowQuery);
		return retrievePickedHUsIndexedByPickingSlotId(pickingCandidates);
	}

	private static List<I_M_Picking_Candidate> retrievePickingCandidates(@NonNull final PickingSlotRepoQuery pickingSlotRowQuery)
	{
		// configure the query builder
		final IQueryBL queryBL = Services.get(IQueryBL.class);

		final IQueryBuilder<I_M_Picking_Candidate> queryBuilder = queryBL
				.createQueryBuilder(I_M_Picking_Candidate.class)
				.addOnlyActiveRecordsFilter()
				.addInArrayFilter(I_M_Picking_Candidate.COLUMN_M_ShipmentSchedule_ID, pickingSlotRowQuery.getShipmentScheduleIds());

		switch (pickingSlotRowQuery.getPickingCandidates())
		{
			case ONLY_NOT_CLOSED:
				queryBuilder.addNotEqualsFilter(I_M_Picking_Candidate.COLUMN_Status, X_M_Picking_Candidate.STATUS_CL); // even if we don't care, we *do not* want to show closed picking candidates
				break;
			case ONLY_NOT_CLOSED_OR_NOT_RACK_SYSTEM:
				final Set<Integer> rackSystemPickingSlotIds = Services.get(IHUPickingSlotDAO.class).retrieveAllPickingSlotIdsWhichAreRackSystems();
				queryBuilder.addCompositeQueryFilter()
						.setJoinOr()
						.addNotEqualsFilter(I_M_Picking_Candidate.COLUMN_Status, X_M_Picking_Candidate.STATUS_CL)
						.addNotInArrayFilter(I_M_Picking_Candidate.COLUMN_M_PickingSlot_ID, rackSystemPickingSlotIds);
				break;
			case ONLY_PROCESSED:
				queryBuilder.addEqualsFilter(I_M_Picking_Candidate.COLUMN_Status, X_M_Picking_Candidate.STATUS_PR);
				break;
			case ONLY_UNPROCESSED:
				queryBuilder.addEqualsFilter(I_M_Picking_Candidate.COLUMN_Status, X_M_Picking_Candidate.STATUS_IP);
				break;
			default:
				Check.errorIf(true, "Query has unexpected pickingCandidates={}; query={}", pickingSlotRowQuery.getPickingCandidates(), pickingSlotRowQuery);
		}

		//
		// Picking slot Barcode filter
		final String pickingSlotBarcode = pickingSlotRowQuery.getPickingSlotBarcode();
		if (!Check.isEmpty(pickingSlotBarcode, true))
		{
			final IPickingSlotDAO pickingSlotDAO = Services.get(IPickingSlotDAO.class);
			final List<Integer> pickingSlotIds = pickingSlotDAO.retrievePickingSlotIds(PickingSlotQuery.builder()
					.barcode(pickingSlotBarcode)
					.build());
			if (pickingSlotIds.isEmpty())
			{
				return ImmutableList.of();
			}

			queryBuilder.addInArrayFilter(I_M_Picking_Candidate.COLUMN_M_PickingSlot_ID, pickingSlotIds);
		}

		//
		// HU filter
		final IQuery<I_M_HU> husQuery = queryBL.createQueryBuilder(I_M_HU.class)
				.addNotEqualsFilter(I_M_HU.COLUMNNAME_HUStatus, X_M_HU.HUSTATUS_Shipped) // not already shipped (https://github.com/metasfresh/metasfresh-webui-api/issues/647)
				.create();
		queryBuilder.addInSubQueryFilter(I_M_Picking_Candidate.COLUMN_M_HU_ID, I_M_HU.COLUMN_M_HU_ID, husQuery);

		return queryBuilder
				.orderBy(I_M_Picking_Candidate.COLUMNNAME_M_Picking_Candidate_ID)
				.create()
				.list();
	}

	private ListMultimap<Integer, PickedHUEditorRow> retrievePickedHUsIndexedByPickingSlotId(@NonNull final List<I_M_Picking_Candidate> pickingCandidates)
	{
		final Map<Integer, PickedHUEditorRow> huId2huRow = new HashMap<>();

		final Builder<Integer, PickedHUEditorRow> builder = ImmutableListMultimap.builder();

		for (final I_M_Picking_Candidate pickingCandidate : pickingCandidates)
		{
			final int huId = pickingCandidate.getM_HU_ID();
			if (huId2huRow.containsKey(huId))
			{
				continue;
			}

			final HUEditorRow huEditorRow = huEditorRepo.retrieveForHUId(HuId.ofRepoId(huId));
			final boolean pickingCandidateProcessed = isPickingCandidateProcessed(pickingCandidate);
			final PickedHUEditorRow row = new PickedHUEditorRow(huEditorRow, pickingCandidateProcessed);

			huId2huRow.put(huId, row);

			builder.put(pickingCandidate.getM_PickingSlot_ID(), row);
		}

		return builder.build();
	}

	private static boolean isPickingCandidateProcessed(@NonNull final I_M_Picking_Candidate pc)
	{
		final String status = pc.getStatus();
		if (X_M_Picking_Candidate.STATUS_CL.equals(status))
		{
			return true;
		}
		else if (X_M_Picking_Candidate.STATUS_PR.equals(status))
		{
			return true;
		}
		else if (X_M_Picking_Candidate.STATUS_IP.equals(status))
		{
			return false;
		}
		else
		{
			throw new AdempiereException("Unexpected M_Picking_Candidate.Status=" + status).setParameter("pc", pc);
		}
	}

	public ListMultimap<Integer, PickedHUEditorRow> //
			retrieveAllPickedHUsIndexedByPickingSlotId(@NonNull final List<I_M_PickingSlot> pickingSlots)
	{
		final SetMultimap<Integer, HuId> //
		huIdsByPickingSlotId = Services.get(IHUPickingSlotDAO.class).retrieveAllHUIdsIndexedByPickingSlotId(pickingSlots);

		huEditorRepo.warmUp(ImmutableSet.copyOf(huIdsByPickingSlotId.values()));

		return huIdsByPickingSlotId
				.entries()
				.stream()
				.map(pickingSlotAndHU -> {
					final int pickingSlotId = pickingSlotAndHU.getKey();
					final HuId huId = pickingSlotAndHU.getValue();

					final HUEditorRow huEditorRow = huEditorRepo.retrieveForHUId(huId);
					final boolean pickingCandidateProcessed = true;
					final PickedHUEditorRow row = new PickedHUEditorRow(huEditorRow, pickingCandidateProcessed);

					return GuavaCollectors.entry(pickingSlotId, row);
				})
				.collect(GuavaCollectors.toImmutableListMultimap());
	}

	/**
	 * Immutable pojo that contains the HU editor as retrieved from {@link HUEditorViewRepository} plus the the {@code processed} value from the respective {@link I_M_Picking_Candidate}.
	 *
	 * @author metas-dev <dev@metasfresh.com>
	 *
	 */
	// the fully qualified annotations are a workaround for a javac problem with maven
	@lombok.Value
	@lombok.AllArgsConstructor
	static class PickedHUEditorRow
	{
		HUEditorRow huEditorRow;
		boolean processed;
	}
}
