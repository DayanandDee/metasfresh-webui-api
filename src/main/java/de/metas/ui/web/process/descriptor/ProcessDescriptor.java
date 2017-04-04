package de.metas.ui.web.process.descriptor;

import java.util.Optional;

import org.adempiere.ad.security.IUserRolePermissions;
import org.adempiere.util.Check;
import org.slf4j.Logger;

import com.google.common.base.MoreObjects;
import com.google.common.base.Throwables;

import de.metas.logging.LogManager;
import de.metas.process.IProcessDefaultParametersProvider;
import de.metas.process.IProcessPreconditionsContext;
import de.metas.process.ProcessPreconditionsResolution;
import de.metas.process.ProcessPreconditionChecker;
import de.metas.ui.web.window.descriptor.DocumentEntityDescriptor;

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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

public final class ProcessDescriptor
{
	private static final Logger logger = LogManager.getLogger(ProcessDescriptor.class);

	public static final Builder builder()
	{
		return new Builder();
	}

	public static enum ProcessDescriptorType
	{
		Form, Workflow, Process, Report
	};

	private final int adProcessId;
	private final ProcessDescriptorType type;
	private final Class<? extends IProcessDefaultParametersProvider> defaultParametersProviderClass;
	private final String processClassname;

	private final DocumentEntityDescriptor parametersDescriptor;
	private final ProcessLayout layout;

	private ProcessDescriptor(final Builder builder)
	{
		super();

		adProcessId = builder.getAD_Process_ID();

		type = builder.getType();

		processClassname = builder.getProcessClassname();
		defaultParametersProviderClass = builder.getProcessDefaultParametersProvider();

		parametersDescriptor = builder.getParametersDescriptor();

		layout = builder.getLayout();
	}
	
	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("AD_Process_ID", adProcessId)
				.toString();
	}

	public int getAD_Process_ID()
	{
		return adProcessId;
	}

	public String getCaption(final String adLanguage)
	{
		return getLayout().getCaption(adLanguage);
	}

	public String getDescription(final String adLanguage)
	{
		return getLayout().getDescription(adLanguage);
	}

	public ProcessDescriptorType getType()
	{
		return type;
	}

	public String getProcessClassname()
	{
		return processClassname;
	}

	public boolean isExecutionGranted(final IUserRolePermissions permissions)
	{
		// Filter out processes on which we don't have access
		final int adProcessId = getAD_Process_ID();
		final Boolean accessRW = permissions.checkProcessAccess(adProcessId);
		if (accessRW == null)
		{
			// no access at all => cannot execute
			return false;
		}
		else if (!accessRW)
		{
			// has just readonly access => cannot execute
			return false;
		}

		return true;
	}

	public ProcessPreconditionsResolution checkPreconditionsApplicable(final IProcessPreconditionsContext context)
	{
		return ProcessPreconditionChecker.newInstance()
				.setProcess(getProcessClassname())
				.setPreconditionsContext(context)
				.checkApplies();
	}
	
	public IProcessDefaultParametersProvider getDefaultParametersProvider()
	{
		if (defaultParametersProviderClass == null)
		{
			return null;
		}
		
		try
		{
			return defaultParametersProviderClass.newInstance();
		}
		catch (InstantiationException | IllegalAccessException ex)
		{
			throw Throwables.propagate(ex);
		}
	}
	
	public DocumentEntityDescriptor getParametersDescriptor()
	{
		return parametersDescriptor;
	}

	public ProcessLayout getLayout()
	{
		return layout;
	}

	public static final class Builder
	{
		private ProcessDescriptorType type;
		private int adProcessId;

		public String processClassname;
		private Optional<Class<?>> processClass = Optional.empty();

		private DocumentEntityDescriptor parametersDescriptor;
		private ProcessLayout layout;

		private Builder()
		{
			super();
		}

		public ProcessDescriptor build()
		{
			return new ProcessDescriptor(this);
		}

		public Builder setAD_Process_ID(final int adProcessId)
		{
			this.adProcessId = adProcessId;
			return this;
		}

		private int getAD_Process_ID()
		{
			Check.assume(adProcessId > 0, "adProcessId > 0");
			return adProcessId;
		}

		public Builder setType(final ProcessDescriptorType type)
		{
			this.type = type;
			return this;
		}

		private ProcessDescriptorType getType()
		{
			Check.assumeNotNull(type, "Parameter type is not null");
			return type;
		}

		public Builder setProcessClassname(final String processClassname)
		{
			this.processClassname = processClassname;
			processClass = loadProcessClass(processClassname);
			return this;
		}

		private String getProcessClassname()
		{
			return processClassname;
		}

		private Class<?> getProcessClassOrNull()
		{
			return processClass.orElse(null);
		}

		private static Optional<Class<?>> loadProcessClass(final String classname)
		{
			if (Check.isEmpty(classname, true))
			{
				return Optional.empty();
			}

			final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
			try
			{
				final Class<?> processClass = classLoader.loadClass(classname);
				return Optional.of(processClass);
			}
			catch (final ClassNotFoundException e)
			{
				logger.error("Cannot process class: {}", classname, e);
				return Optional.empty();
			}
		}

		private Class<? extends IProcessDefaultParametersProvider> getProcessDefaultParametersProvider()
		{
			final Class<?> processClass = getProcessClassOrNull();
			if (processClass == null || !IProcessDefaultParametersProvider.class.isAssignableFrom(processClass))
			{
				return null;
			}

			try
			{
				return processClass.asSubclass(IProcessDefaultParametersProvider.class);
			}
			catch (final Exception e)
			{
				logger.error(e.getLocalizedMessage(), e);
				return null;
			}
		}


		public Builder setParametersDescriptor(final DocumentEntityDescriptor parametersDescriptor)
		{
			this.parametersDescriptor = parametersDescriptor;
			return this;
		}

		private DocumentEntityDescriptor getParametersDescriptor()
		{
			Check.assumeNotNull(parametersDescriptor, "Parameter parametersDescriptor is not null");
			return parametersDescriptor;
		}

		public Builder setLayout(final ProcessLayout layout)
		{
			this.layout = layout;
			return this;
		}

		private ProcessLayout getLayout()
		{
			Check.assumeNotNull(layout, "Parameter layout is not null");
			return layout;
		}
	}

}
