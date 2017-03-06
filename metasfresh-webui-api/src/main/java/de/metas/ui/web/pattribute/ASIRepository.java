package de.metas.ui.web.pattribute;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.mm.attributes.api.IAttributeDAO;
import org.adempiere.mm.attributes.util.ASIEditingInfo;
import org.adempiere.util.Services;
import org.adempiere.util.lang.IAutoCloseable;
import org.compiere.model.I_M_AttributeInstance;
import org.compiere.model.MAttributeSetInstance;
import org.compiere.util.CCache;
import org.compiere.util.Evaluatee;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import de.metas.logging.LogManager;
import de.metas.ui.web.exceptions.EntityNotFoundException;
import de.metas.ui.web.pattribute.ASIDescriptorFactory.ASIAttributeFieldBinding;
import de.metas.ui.web.pattribute.json.JSONCreateASIRequest;
import de.metas.ui.web.window.datatypes.DocumentId;
import de.metas.ui.web.window.datatypes.DocumentPath;
import de.metas.ui.web.window.datatypes.LookupValue;
import de.metas.ui.web.window.datatypes.json.JSONDocumentChangedEvent;
import de.metas.ui.web.window.model.Document;
import de.metas.ui.web.window.model.Document.CopyMode;
import de.metas.ui.web.window.model.DocumentCollection;
import de.metas.ui.web.window.model.IDocumentChangesCollector.ReasonSupplier;
import de.metas.ui.web.window.model.IDocumentEvaluatee;
import de.metas.ui.web.window.model.IDocumentFieldView;

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

@Component
public class ASIRepository
{
	// services
	private static final Logger logger = LogManager.getLogger(ASIRepository.class);
	@Autowired
	private ASIDescriptorFactory descriptorsFactory;
	@Autowired
	@Lazy
	private DocumentCollection documentsCollection;

	private final AtomicInteger nextASIDocId = new AtomicInteger(1);
	private final CCache<DocumentId, ASIDocument> id2asiDoc = CCache.newLRUCache("ASIDocuments", 500, 0);

	private static final String VERSION_DEFAULT = "0";
	private static final ReasonSupplier REASON_ProcessASIDocumentChanges = () -> "process ASI document changes";

	public ASIDocument createNewFrom(final JSONCreateASIRequest request)
	{
		final ASIEditingInfo info = createASIEditingInfo(request);

		//
		// Get the ASI descriptor
		final ASIDescriptor asiDescriptor = descriptorsFactory.getASIDescriptor(info);

		//
		// Create the new ASI document
		final Document asiDocData = Document.builder(asiDescriptor.getEntityDescriptor())
				.initializeAsNewDocument(nextASIDocId::getAndIncrement, VERSION_DEFAULT)
				.build();

		//
		// If we have a template ASI, populate the ASI document from it
		final MAttributeSetInstance templateASI = info.getM_AttributeSetInstance();
		if (templateASI != null)
		{
			for (final I_M_AttributeInstance fromAI : Services.get(IAttributeDAO.class).retrieveAttributeInstances(templateASI))
			{
				loadASIDocumentField(asiDocData, fromAI);
			}
		}

		//
		// Validate, log and add the new ASI document to our index
		asiDocData.checkAndGetValidStatus();
		logger.trace("Created from ASI={}: {}", templateASI, asiDocData);

		final Evaluatee effectiveContext = info.getEffectiveContext();
		final ASIDocument asiDoc = new ASIDocument(asiDescriptor, asiDocData, effectiveContext);
		commit(asiDoc);

		return asiDoc;
	}

	private ASIEditingInfo createASIEditingInfo(final JSONCreateASIRequest request)
	{
		final DocumentPath documentPath = request.getSource().toSingleDocumentPath();
		return documentsCollection.forDocumentReadonly(documentPath, document -> {
			final IDocumentEvaluatee documentEvaluatee = document.asEvaluatee();
			final int productId = documentEvaluatee.get_ValueAsInt("M_Product_ID", -1);
			final boolean isSOTrx = documentEvaluatee.get_ValueAsBoolean("IsSOTrx", true);

			final int attributeSetInstanceId = request.getTemplateId();
			final String callerTableName = document.getEntityDescriptor().getTableNameOrNull();
			final int callerColumnId = -1; // FIXME implement
			return ASIEditingInfo.of(productId, attributeSetInstanceId, callerTableName, callerColumnId, isSOTrx, documentEvaluatee);
		});
	}

	public ASILayout getLayout(final DocumentId asiDocId)
	{
		return forASIDocumentReadonly(asiDocId, asiDoc -> asiDoc.getDescriptor().getLayout());
	}

	private ASIDocument getASIDocumentNoLock(final DocumentId asiDocId)
	{
		final ASIDocument asiDoc = id2asiDoc.get(asiDocId);
		if (asiDoc == null)
		{
			throw new EntityNotFoundException("No product attributes found for asiId=" + asiDocId);
		}
		return asiDoc;
	}

	private final void commit(final ASIDocument asiDoc)
	{
		final DocumentId asiDocId = asiDoc.getDocumentId();
		if (asiDoc.isCompleted())
		{
			final ASIDocument asiDocRemoved = id2asiDoc.remove(asiDocId);
			logger.trace("Removed from repository by ID={}: {}", asiDocId, asiDocRemoved);
		}
		else
		{
			final ASIDocument asiDocReadonly = asiDoc.copy(CopyMode.CheckInReadonly);
			id2asiDoc.put(asiDocId, asiDocReadonly);
			logger.trace("Added to repository: {}", asiDocReadonly);
		}
	}

	public <R> R forASIDocumentReadonly(final DocumentId asiDocId, final Function<ASIDocument, R> processor)
	{
		try (final IAutoCloseable readLock = getASIDocumentNoLock(asiDocId).lockForReading())
		{
			final ASIDocument asiDoc = getASIDocumentNoLock(asiDocId);
			return processor.apply(asiDoc);
		}
	}

	public <R> R forASIDocumentWritable(final DocumentId asiDocId, final Function<ASIDocument, R> processor)
	{
		try (final IAutoCloseable readLock = getASIDocumentNoLock(asiDocId).lockForWriting())
		{
			final ASIDocument asiDoc = getASIDocumentNoLock(asiDocId).copy(CopyMode.CheckOutWritable);
			final R result = processor.apply(asiDoc);

			Services.get(ITrxManager.class)
					.getCurrentTrxListenerManagerOrAutoCommit()
					.onAfterCommit(() -> commit(asiDoc));

			return result;
		}
	}

	public void processASIDocumentChanges(final DocumentId asiDocId, final List<JSONDocumentChangedEvent> events)
	{
		forASIDocumentWritable(asiDocId, asiDoc -> {
			asiDoc.processValueChanges(events, REASON_ProcessASIDocumentChanges);
			return null; // no response
		});
	}

	private static void loadASIDocumentField(final Document asiDoc, final I_M_AttributeInstance fromAI)
	{
		final String fieldName = fromAI.getM_Attribute().getValue();
		final IDocumentFieldView field = asiDoc.getFieldViewOrNull(fieldName);

		// Skip loading the attribute instance if it's no longer exist.
		// This can happen if we are trying to load an old ASI but in meantime the AttributeSet was changed and the attribute was removed or deactivated.
		if (field == null)
		{
			logger.warn("Attribute {} no longer exist in {}", fieldName, asiDoc.getEntityDescriptor());
			return;
		}

		final Object value = field
				.getDescriptor()
				.getDataBindingNotNull(ASIAttributeFieldBinding.class)
				.readValue(fromAI);

		asiDoc.processValueChange(fieldName, value, () -> "update from " + fromAI);
	}

	public LookupValue complete(final DocumentId asiDocId)
	{
		return forASIDocumentWritable(asiDocId, ASIDocument::complete);
	}
}
