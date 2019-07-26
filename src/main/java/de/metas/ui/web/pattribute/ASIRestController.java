package de.metas.ui.web.pattribute;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.metas.ui.web.config.WebConfig;
import de.metas.ui.web.pattribute.json.JSONASILayout;
import de.metas.ui.web.pattribute.json.JSONCreateASIRequest;
import de.metas.ui.web.session.UserSession;
import de.metas.ui.web.window.controller.Execution;
import de.metas.ui.web.window.datatypes.DocumentId;
import de.metas.ui.web.window.datatypes.LookupValue;
import de.metas.ui.web.window.datatypes.LookupValuesList;
import de.metas.ui.web.window.datatypes.json.JSONDocument;
import de.metas.ui.web.window.datatypes.json.JSONDocumentChangedEvent;
import de.metas.ui.web.window.datatypes.json.JSONDocumentLayoutOptions;
import de.metas.ui.web.window.datatypes.json.JSONDocumentOptions;
import de.metas.ui.web.window.datatypes.json.JSONLookupValue;
import de.metas.ui.web.window.datatypes.json.JSONLookupValuesList;
import de.metas.ui.web.window.model.IDocumentChangesCollector;
import io.swagger.annotations.Api;

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

@Api
@RestController
@RequestMapping(value = ASIRestController.ENDPOINT)
public class ASIRestController
{
	public static final String ENDPOINT = WebConfig.ENDPOINT_ROOT + "/pattribute";

	@Autowired
	private UserSession userSession;

	@Autowired
	private ASIRepository asiRepo;

	private JSONDocumentOptions newJsonDocumentOpts()
	{
		return JSONDocumentOptions.of(userSession);
	}

	private JSONDocumentLayoutOptions newJsonDocumentLayoutOpts()
	{
		return JSONDocumentLayoutOptions.of(userSession);
	}

	@PostMapping({ "", "/" })
	public JSONDocument createASIDocument(@RequestBody final JSONCreateASIRequest request)
	{
		userSession.assertLoggedIn();

		return Execution.callInNewExecution("createASI", () -> asiRepo.createNewFrom(request).toJSONDocument(newJsonDocumentOpts()));
	}

	@GetMapping("/{asiDocId}/layout")
	public JSONASILayout getLayout(@PathVariable("asiDocId") final String asiDocIdStr)
	{
		userSession.assertLoggedIn();

		final DocumentId asiDocId = DocumentId.of(asiDocIdStr);
		final ASILayout asiLayout = asiRepo.getLayout(asiDocId);
		return JSONASILayout.of(asiLayout, newJsonDocumentLayoutOpts());
	}

	@GetMapping("/{asiDocId}")
	public JSONDocument getASIDocument(@PathVariable("asiDocId") final String asiDocIdStr)
	{
		userSession.assertLoggedIn();

		final DocumentId asiDocId = DocumentId.of(asiDocIdStr);
		return asiRepo.forASIDocumentReadonly(asiDocId, asiDoc -> asiDoc.toJSONDocument(newJsonDocumentOpts()));
	}

	@PatchMapping("/{asiDocId}")
	public List<JSONDocument> processChanges(
			@PathVariable("asiDocId") final String asiDocIdStr //
			, @RequestBody final List<JSONDocumentChangedEvent> events //
	)
	{
		userSession.assertLoggedIn();

		final DocumentId asiDocId = DocumentId.of(asiDocIdStr);

		return Execution.callInNewExecution("processChanges", () -> {
			final IDocumentChangesCollector changesCollector = Execution.getCurrentDocumentChangesCollectorOrNull();
			asiRepo.processASIDocumentChanges(asiDocId, events, changesCollector);
			return JSONDocument.ofEvents(changesCollector, newJsonDocumentOpts());
		});
	}

	@GetMapping("/{asiDocId}/field/{attributeName}/typeahead")
	public JSONLookupValuesList getAttributeTypeahead(
			@PathVariable("asiDocId") final String asiDocIdStr //
			, @PathVariable("attributeName") final String attributeName //
			, @RequestParam(name = "query", required = true) final String query //
	)
	{
		userSession.assertLoggedIn();

		final DocumentId asiDocId = DocumentId.of(asiDocIdStr);
		return asiRepo.forASIDocumentReadonly(asiDocId, asiDoc -> asiDoc.getFieldLookupValuesForQuery(attributeName, query))
				.transform(this::toJSONLookupValuesList);
	}

	private JSONLookupValuesList toJSONLookupValuesList(final LookupValuesList lookupValuesList)
	{
		return JSONLookupValuesList.ofLookupValuesList(lookupValuesList, userSession.getAD_Language());
	}

	private JSONLookupValue toJSONLookupValue(final LookupValue lookupValue)
	{
		return JSONLookupValue.ofLookupValue(lookupValue, userSession.getAD_Language());
	}

	@GetMapping("/{asiDocId}/field/{attributeName}/dropdown")
	public JSONLookupValuesList getAttributeDropdown(
			@PathVariable("asiDocId") final String asiDocIdStr //
			, @PathVariable("attributeName") final String attributeName //
	)
	{
		userSession.assertLoggedIn();

		final DocumentId asiDocId = DocumentId.of(asiDocIdStr);
		return asiRepo.forASIDocumentReadonly(asiDocId, asiDoc -> asiDoc.getFieldLookupValues(attributeName))
				.transform(this::toJSONLookupValuesList);
	}

	@PostMapping(value = "/{asiDocId}/complete")
	public JSONLookupValue complete(@PathVariable("asiDocId") final String asiDocIdStr)
	{
		userSession.assertLoggedIn();

		final DocumentId asiDocId = DocumentId.of(asiDocIdStr);

		return Execution.callInNewExecution("complete", () -> asiRepo.complete(asiDocId))
				.transform(this::toJSONLookupValue);
	}
}
