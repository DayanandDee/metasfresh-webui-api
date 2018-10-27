package de.metas.ui.web.process.json;

import java.io.Serializable;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import de.metas.ui.web.process.IProcessInstanceController;
import de.metas.ui.web.window.datatypes.json.JSONDocumentField;
import de.metas.ui.web.window.datatypes.json.JSONOptions;
import de.metas.util.GuavaCollectors;

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

@SuppressWarnings("serial")
public class JSONProcessInstance implements Serializable
{
	public static JSONProcessInstance of(final IProcessInstanceController pinstance, final JSONOptions jsonOpts)
	{
		return new JSONProcessInstance(pinstance, jsonOpts);
	}

	@JsonProperty("pinstanceId")
	private final String pinstanceId;

	/** Parameters */
	@JsonProperty("fieldsByName")
	private final Map<String, JSONDocumentField> parametersByName;

	private JSONProcessInstance(final IProcessInstanceController pinstance, final JSONOptions jsonOpts)
	{
		super();
		pinstanceId = pinstance.getInstanceId().toJson();

		parametersByName = pinstance.getParameters()
				.stream()
				.map(param -> JSONDocumentField.ofProcessParameter(param, jsonOpts.getAD_Language()))
				.collect(GuavaCollectors.toImmutableMapByKey(JSONDocumentField::getField));
	}
}
