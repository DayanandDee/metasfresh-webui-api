package de.metas.ui.web.document.process;

import org.adempiere.ad.table.api.IADTableDAO;
import org.adempiere.service.ClientId;
import org.compiere.model.I_Fact_Acct;
import org.compiere.util.Env;

import de.metas.acct.api.IPostingRequestBuilder.PostImmediate;
import de.metas.acct.api.IPostingService;
import de.metas.process.IProcessPrecondition;
import de.metas.process.Param;
import de.metas.process.ProcessPreconditionsResolution;
import de.metas.process.RunOutOfTrx;
import de.metas.ui.web.process.adprocess.ViewBasedProcessTemplate;
import de.metas.ui.web.view.IViewRow;
import de.metas.util.Services;

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

public class WEBUI_Fact_Acct_Repost extends ViewBasedProcessTemplate implements IProcessPrecondition
{
	private final IPostingService postingService = Services.get(IPostingService.class);
	private final IADTableDAO adTablesRepo = Services.get(IADTableDAO.class);

	public static final String TABLENAME_RV_UnPosted = "RV_UnPosted";

	@Param(parameterName = "IsEnforcePosting", mandatory = true)
	private boolean enforce;

	@Override
	public ProcessPreconditionsResolution checkPreconditionsApplicable()
	{
		if (getSelectedRowIds().isEmpty())
		{
			return ProcessPreconditionsResolution.rejectBecauseNoSelection();
		}

		return ProcessPreconditionsResolution.accept();
	}

	@Override
	@RunOutOfTrx
	protected String doIt() throws Exception
	{
		getView().streamByIds(getSelectedRowIds())
				.map(this::extractDocumentToRepost)
				.distinct()
				.forEach(this::repost);
		return MSG_OK;
	}

	private DocumentToRepost extractDocumentToRepost(final IViewRow row)
	{
		if (I_Fact_Acct.Table_Name.equals(getTableName())
				|| TABLENAME_RV_UnPosted.equals(getTableName()))
		{
			return extractDocumentToRepostFromTableAndRecordIdRow(row);
		}
		else
		{
			return extractDocumentToRepostFromRegularRow(row);
		}
	}

	private DocumentToRepost extractDocumentToRepostFromTableAndRecordIdRow(final IViewRow row)
	{
		final int adTableId = row.getFieldValueAsInt(I_Fact_Acct.COLUMNNAME_AD_Table_ID, -1);
		final int recordId = row.getFieldValueAsInt(I_Fact_Acct.COLUMNNAME_Record_ID, -1);
		final ClientId adClientId = ClientId.ofRepoId(row.getFieldValueAsInt(I_Fact_Acct.COLUMNNAME_AD_Client_ID, -1));
		return DocumentToRepost.builder()
				.adTableId(adTableId)
				.recordId(recordId)
				.clientId(adClientId)
				.build();
	}

	private DocumentToRepost extractDocumentToRepostFromRegularRow(final IViewRow row)
	{
		final int adTableId = adTablesRepo.retrieveTableId(getTableName());
		final int recordId = row.getId().toInt();

		final ClientId adClientId = ClientId.ofRepoId(row.getFieldValueAsInt(I_Fact_Acct.COLUMNNAME_AD_Client_ID, -1));

		return DocumentToRepost.builder()
				.adTableId(adTableId)
				.recordId(recordId)
				.clientId(adClientId)
				.build();
	}

	private void repost(final DocumentToRepost doc)
	{
		postingService
				.newPostingRequest()
				.setClientId(doc.getClientId())
				.setDocument(doc.getAdTableId(), doc.getRecordId())
				.setForce(enforce)
				.setPostImmediate(PostImmediate.Yes)
				.setFailOnError(true)
				.onErrorNotifyUser(Env.getLoggedUserId())
				.postIt();
	}

	@Override
	protected void postProcess(final boolean success)
	{
		getView().invalidateSelection();
	}

	@lombok.Value
	@lombok.Builder
	private static class DocumentToRepost
	{
		int adTableId;
		int recordId;
		ClientId clientId;
	}

}
