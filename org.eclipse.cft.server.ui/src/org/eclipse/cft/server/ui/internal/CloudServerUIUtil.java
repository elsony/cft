/*******************************************************************************
 * Copyright (c) 2015 Pivotal Software, Inc. 
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
 *     IBM - initial API and implementation
 ********************************************************************************/
package org.eclipse.cft.server.ui.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.cft.server.core.AbstractCloudFoundryUrl;
import org.eclipse.cft.server.core.internal.CloudFoundryBrandingExtensionPoint;
import org.eclipse.cft.server.core.internal.CloudFoundryBrandingExtensionPoint.CloudServerURL;
import org.eclipse.cft.server.core.internal.CloudFoundryPlugin;
import org.eclipse.cft.server.core.internal.CloudFoundryServer;
import org.eclipse.cft.server.ui.CloudUIUtil;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.operation.IRunnableContext;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Shell;

/**
 * THIS IS NOT API AND USERS SHOULD EXPECT BREAKING CHANGES.
 * 
 * Available API is exposed through {@link CloudUIUtil}
 * 
 * This class is an appropriate complement for {@link CFUiUtil}
 * that exposes (internally) the methods that received and returned the old
 * {@link CloudServerURL}, switching it to the more generic
 * {@link AbstractCloudFoundryUrl}
 * 
 */
public class CloudServerUIUtil {
	public static List<AbstractCloudFoundryUrl> getAllUrls(String serverTypeId, IRunnableContext runnableContext, boolean runningInUIThread) throws CoreException {
		List<AbstractCloudFoundryUrl> urls = new ArrayList<AbstractCloudFoundryUrl>();
		// Be super safe to avoid NPE
		AbstractCloudFoundryUrl defaultUrl = getDefaultUrl(serverTypeId, runnableContext, runningInUIThread); 
		if (defaultUrl != null) {
			urls.add(defaultUrl);
		}
		
		List <AbstractCloudFoundryUrl> cloudUrls = getUrls(serverTypeId, runnableContext, runningInUIThread);
		if (cloudUrls != null) {
			urls.addAll(cloudUrls);
		}
		
		return urls;
	}

	public static AbstractCloudFoundryUrl getDefaultUrl(final String serverTypeId, IRunnableContext context, boolean runningInUIThread) throws CoreException {
		try {
			final AbstractCloudFoundryUrl[] abstractUrls = new AbstractCloudFoundryUrl[1];
			ICoreRunnable coreRunner = new ICoreRunnable() {
				public void run(IProgressMonitor monitor) throws CoreException {
					abstractUrls[0] = CloudFoundryBrandingExtensionPoint.getDefaultUrl(serverTypeId);
				}
			};
			
			if(runningInUIThread) {
			
				if (context != null) {
					CFUiUtil.runForked(coreRunner, context);
				}
				else {
					CFUiUtil.runForked(coreRunner);
				}
			} else {
				coreRunner.run(new NullProgressMonitor());
			}

			return abstractUrls[0];
		}
		catch (OperationCanceledException e) {
			throw new CoreException(CloudFoundryPlugin.getErrorStatus(e));
		}
	}

	public static List<AbstractCloudFoundryUrl> getUrls(final String serverTypeId, final IRunnableContext context, boolean runningInUIThread) throws CoreException {
		try {
			final List<AbstractCloudFoundryUrl> cloudUrls = new ArrayList<AbstractCloudFoundryUrl>();
			ICoreRunnable coreRunner = new ICoreRunnable() {
				public void run(IProgressMonitor monitor) throws CoreException {
					Set<String> urlNames = new HashSet<String>();

					List<AbstractCloudFoundryUrl> userDefinedUrls = getUserDefinedUrls(serverTypeId);
					for (AbstractCloudFoundryUrl userDefinedUrl : userDefinedUrls) {
						cloudUrls.add(userDefinedUrl);
						urlNames.add(userDefinedUrl.getName());
					}

					List<AbstractCloudFoundryUrl> defaultUrls = CloudFoundryBrandingExtensionPoint.getCloudUrls(serverTypeId);
					if (defaultUrls != null) {
						for (AbstractCloudFoundryUrl defaultUrl : defaultUrls) {
							if (!urlNames.contains(defaultUrl.getName())) {
								cloudUrls.add(defaultUrl);
							}
						}

						Collections.sort(cloudUrls, new Comparator<AbstractCloudFoundryUrl>() {
							public int compare(AbstractCloudFoundryUrl o1, AbstractCloudFoundryUrl o2) {
								return o1.getName().compareToIgnoreCase(o2.getName());
							}
						});
					}
				}
			};
			
			
			if(runningInUIThread) {
				if (context != null) {
					CFUiUtil.runForked(coreRunner, context);
				}
				else {
					CFUiUtil.runForked(coreRunner);
				}
			} else {
				coreRunner.run(new NullProgressMonitor());
			}

			return cloudUrls;
		}
		catch (OperationCanceledException e) {
			throw new CoreException(CloudFoundryPlugin.getErrorStatus(e));
		}
		
	}
	
	public static AbstractCloudFoundryUrl getWildcardUrl(AbstractCloudFoundryUrl cloudUrl, List<AbstractCloudFoundryUrl> allCloudUrls, Shell shell) {
		String url = cloudUrl.getUrl();
		if (url.contains("{")) { //$NON-NLS-1$
			int startIndex = url.indexOf("{"); //$NON-NLS-1$
			int endIndex = url.indexOf("}"); //$NON-NLS-1$
			String wildcard = url.substring(startIndex + 1, endIndex);

			TargetURLDialog dialog = new TargetURLDialog(shell, cloudUrl, wildcard, allCloudUrls);
			if (dialog.open() == IDialogConstants.OK_ID) {
				url = dialog.getUrl();
				String name = dialog.getName();
				boolean selfSigned = url != null && CloudFoundryServer.isSelfSigned(url);
				return new UserDefinedCloudFoundryUrl(name, url, selfSigned);
			}
			else {
				return null;
			}
		}

		return null;
	}
	
	public static List<AbstractCloudFoundryUrl> getUserDefinedUrls(String serverTypeId) {
		List<AbstractCloudFoundryUrl> urls = new ArrayList<AbstractCloudFoundryUrl>();

		IPreferenceStore prefStore = CloudFoundryServerUiPlugin.getDefault().getPreferenceStore();
		String urlString = prefStore.getString(CFUiUtil.ATTR_USER_DEFINED_URLS + "." + serverTypeId); //$NON-NLS-1$

		if (urlString != null && urlString.length() > 0) {
			// Split on "||"
			String[] urlEntries = urlString.split("\\|\\|"); //$NON-NLS-1$
			if (urlEntries != null) {
				for (String entry : urlEntries) {
					if (entry.length() > 0) {
						String[] values = entry.split(","); //$NON-NLS-1$
						if (values != null) {
							String name = null;
							String url = null;

							if (values.length >= 2) {
								name = values[0];
								url = values[1];
							}

							boolean selfSigned = url != null && CloudFoundryServer.isSelfSigned(url);
							urls.add(new UserDefinedCloudFoundryUrl(name, url, selfSigned));
						}
					}
				}
			}
		}

		return urls;
	}
	
	public static void storeUserDefinedUrls(String serverTypeId, List<AbstractCloudFoundryUrl> urls) {
		IPreferenceStore prefStore = CloudFoundryServerUiPlugin.getDefault().getPreferenceStore();
		StringBuilder builder = new StringBuilder();

		for (AbstractCloudFoundryUrl url : urls) {
			if (url.getUserDefined()) {
				builder.append(url.getName());

				builder.append(","); //$NON-NLS-1$
				builder.append(url.getUrl());

				builder.append("||"); //$NON-NLS-1$

				// Also store the self-signed for each user-defined URL
				CloudFoundryServer.setSelfSigned(url.getSelfSigned(), url.getUrl());
			}
		}

		prefStore.setValue(CFUiUtil.ATTR_USER_DEFINED_URLS + "." + serverTypeId, builder.toString()); //$NON-NLS-1$
	}
	
	
}
