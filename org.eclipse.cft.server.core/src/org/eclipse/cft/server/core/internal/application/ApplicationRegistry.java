/*******************************************************************************
 * Copyright (c) 2013, 2016 Pivotal Software, Inc. and others
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution. 
 * 
 * The Eclipse Public License is available at 
 * 
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * and the Apache License v2.0 is available at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * You may elect to redistribute this code under either of these licenses.
 *  
 *  Contributors:
 *     Pivotal Software, Inc. - initial API and implementation
 ********************************************************************************/
package org.eclipse.cft.server.core.internal.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.cft.server.core.AbstractApplicationDelegate;
import org.eclipse.cft.server.core.CFApplicationArchive;
import org.eclipse.cft.server.core.internal.CloudFoundryPlugin;
import org.eclipse.cft.server.core.internal.CloudFoundryServer;
import org.eclipse.cft.server.core.internal.client.CloudFoundryApplicationModule;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.model.IModuleResource;

/**
 * Given WST application module, this registry determines what frameworks and
 * runtimes are applicable to that module, and also provides the related
 * application delegate for that module, which is responsible for providing
 * additional information, for example, whether that application requires URLs,
 * and if that application has its own mechanism to generate an archive used by
 * the Cloud Foundry plugin framework to push the application's resources to a
 * Cloud Foundry server.
 * 
 */
public class ApplicationRegistry {

	public enum Priority {
		High, Medium, Low
	}

	private static Map<Priority, List<ApplicationProvider>> delegates;

	private static ApplicationArchiverFactory archiverFactory;

	private static final String APPLICATION_DELEGATE_EXT_ELEMENT = "applicationDelegate"; //$NON-NLS-1$

	public static final String DEFAULT_JAVA_WEB_PROVIDER_ID = "org.eclipse.cft.server.application.javaweb"; //$NON-NLS-1$

	public static String EXTENSION_POINT = "org.eclipse.cft.server.core.application"; //$NON-NLS-1$

	public static AbstractApplicationDelegate getApplicationDelegate(IModule module) {
		ApplicationProvider provider = getApplicationProvider(module);
		if (provider != null) {
			return provider.getDelegate();
		}

		return null;
	}

	public static CFApplicationArchive getApplicationArchive(IModule module, IServer server,
			IModuleResource[] resources, IProgressMonitor monitor) throws CoreException {

		// Check if manifest archiving is supported before asking the delegates
		// for an archive
		CFApplicationArchive archive = null;
		ApplicationArchiverFactory factory = getArchiverFactory();
		if (factory.supportsManifestArchiving(module, server)) {
			archive = factory.getManifestApplicationArchiver().getApplicationArchive(module, server, resources,
					monitor);
		}

		if (archive == null) {
			List<ApplicationProvider> providers = getApplicationProviders(module);
			if (providers != null) {
				// Iterate through all the providers and find one that provides
				// an
				// archive for the given module
				for (ApplicationProvider applicationProvider : providers) {
					AbstractApplicationDelegate delegate = applicationProvider.getDelegate();
					if (delegate != null) {
						archive = delegate.getApplicationArchive(module, server, resources, monitor);
						if (archive != null) {
							break;
						}
					}
				}
			}
		}

		return archive;
	}

	public static ApplicationArchiverFactory getArchiverFactory() {
		if (archiverFactory == null) {
			archiverFactory = new ApplicationArchiverFactory();
		}
		return archiverFactory;
	}

	public static AbstractApplicationDelegate getApplicationDelegate(IModule module, CloudFoundryServer cloudServer) {

		AbstractApplicationDelegate delegate = null;

		if (cloudServer != null) {
			List<ApplicationProvider> providers = getApplicationProviders(module);
			if (providers != null) {
				for (ApplicationProvider prv : providers) {
					AbstractApplicationDelegate appDel = prv.getDelegate();

					if (appDel instanceof ICloudFoundryServerApplicationDelegate && cloudServer.getUrl()
							.contains(((ICloudFoundryServerApplicationDelegate) appDel).getServerUri())) {
						delegate = appDel;
						break;
					}
				}
			}
		}

		return (delegate != null) ? delegate : getApplicationDelegate(module);
	}

	public static ApplicationProvider getDefaultJavaWebApplicationProvider() {
		ApplicationProvider provider = getApplicationProvider(DEFAULT_JAVA_WEB_PROVIDER_ID);

		if (provider == null) {
			CloudFoundryPlugin.logError("Unable to load default Java Web application provider with this ID: " //$NON-NLS-1$
					+ DEFAULT_JAVA_WEB_PROVIDER_ID + ". Please check that the plug-in is correctly installed."); //$NON-NLS-1$
		}
		return provider;
	}

	public static ApplicationProvider getApplicationProvider(IModule module) {

		if (delegates == null) {
			delegates = load();
		}

		ApplicationProvider provider = null;

		for (Priority priority : Priority.values()) {

			List<ApplicationProvider> providerList = delegates.get(priority);
			if (providerList != null) {
				for (ApplicationProvider prv : providerList) {
					// First do a check based on static extension point
					// information about the
					// provider based
					// on the application ID that the provider supports.

					if (supportsModule(module, prv)) {
						provider = prv;
						return provider;
					}

				}
			}
		}

		return provider;
	}

	public static List<ApplicationProvider> getApplicationProviders(IModule module) {

		if (delegates == null) {
			delegates = load();
		}

		List<ApplicationProvider> providers = new ArrayList<ApplicationProvider>();
		for (Priority priority : Priority.values()) {

			List<ApplicationProvider> providerList = delegates.get(priority);
			if (providerList != null) {
				for (ApplicationProvider prv : providerList) {
					// First do a check based on static extension point
					// information about the
					// provider based
					// on the application ID that the provider supports.

					if (supportsModule(module, prv)) {
						providers.add(prv);
					}

				}
			}
		}

		return providers;
	}

	/**
	 * Get the application delegate provider based on the provider ID (e.g.
	 * org.eclipse.cft.server.application.javaweb). This is used in case a
	 * IModule is not available.
	 * @param providerID
	 * @return ApplicationProvider matching the specified providerID, or null if
	 * not found.
	 */
	public static ApplicationProvider getApplicationProvider(String providerID) {
		if (providerID == null) {
			return null;
		}

		if (delegates == null) {
			delegates = load();
		}

		ApplicationProvider provider = null;

		for (Priority priority : Priority.values()) {

			List<ApplicationProvider> providerList = delegates.get(priority);
			if (providerList != null) {
				for (ApplicationProvider prv : providerList) {
					// First do a check based on static extension point
					// information about the
					// provider based
					// on the application ID that the provider supports.

					if (providerID.equals(prv.getProviderID())) {
						provider = prv;
						break;
					}
				}
			}
		}

		return provider;
	}

	public static boolean isSupportedModule(IModule module) {

		// Check module ID first to prevent unnecessary loading of delegates for
		// incompatible modules.
		String moduleID = module != null && module.getModuleType() != null ? module.getModuleType().getId() : null;

		if (moduleID == null) {
			return false;
		}

		if (delegates == null) {
			delegates = load();
		}

		for (List<ApplicationProvider> providerList : delegates.values()) {

			if (providerList != null) {
				for (ApplicationProvider provider : providerList) {

					if (supportsModule(module, provider)) {
						return true;
					}
				}
			}
		}

		return false;

	}

	private static boolean supportsModule(IModule module, ApplicationProvider provider) {
		if (module == null) {
			return false;
		}

		String moduleID = module != null && module.getModuleType() != null ? module.getModuleType().getId() : null;

		if (moduleID != null) {
			List<String> supportedModuleIDs = provider.getModuleIDs();
			if (supportedModuleIDs != null) {
				for (String supportedID : supportedModuleIDs) {
					if (moduleID.equals(supportedID)) {
						return true;
					}
				}
			}
		}

		return false;
	}

	private static Map<Priority, List<ApplicationProvider>> load() {
		Map<Priority, List<ApplicationProvider>> providerMap = new HashMap<Priority, List<ApplicationProvider>>();
		IExtensionPoint extensionPoint = Platform.getExtensionRegistry().getExtensionPoint(EXTENSION_POINT);

		if (extensionPoint == null) {
			CloudFoundryPlugin.logError("Failed to load application type providers from: " + EXTENSION_POINT); //$NON-NLS-1$
		}
		else {
			for (IExtension extension : extensionPoint.getExtensions()) {
				for (IConfigurationElement config : extension.getConfigurationElements()) {

					if (APPLICATION_DELEGATE_EXT_ELEMENT.equals(config.getName())) {
						ApplicationProvider provider = new ApplicationProvider(config, EXTENSION_POINT);
						Priority priority = provider.getPriority();
						String providerID = provider.getProviderID();
						if (priority == null || providerID == null) {
							CloudFoundryPlugin
									.logError("Failed to load Cloud Foundry application provider from extension point: " //$NON-NLS-1$
											+ EXTENSION_POINT + ". Missing provider ID and priority values"); //$NON-NLS-1$
						}
						else {
							List<ApplicationProvider> providers = providerMap.get(priority);
							if (providers == null) {
								providers = new ArrayList<ApplicationProvider>();
								providerMap.put(priority, providers);
							}
							providers.add(provider);
						}
					}
				}
			}
		}

		return providerMap;
	}

	/**
	 * Determines if the application requires a URL. By default, applications
	 * are required to have at least one mapped URL, unless otherwise specified
	 * by the application's delegate.
	 * @param delegate
	 * @param appModule
	 * @return true if application URL is required for the given application.
	 * False otherwise.
	 */
	public static boolean shouldSetDefaultUrl(AbstractApplicationDelegate delegate,
			CloudFoundryApplicationModule appModule) {
		return delegate == null || (delegate instanceof ModuleResourceApplicationDelegate
				? ((ModuleResourceApplicationDelegate) delegate).shouldSetDefaultUrl(appModule)
				: delegate.requiresURL());
	}

}
