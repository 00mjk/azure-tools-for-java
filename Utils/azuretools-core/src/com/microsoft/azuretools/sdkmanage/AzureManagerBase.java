/*
 * Copyright (c) Microsoft Corporation
 *
 * All rights reserved.
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.microsoft.azuretools.sdkmanage;

import com.google.common.base.Throwables;
import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.arm.resources.AzureConfigurable;
import com.microsoft.azure.credentials.AzureTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.applicationinsights.v2015_05_01.implementation.InsightsManager;
import com.microsoft.azure.management.appplatform.v2019_05_01_preview.implementation.AppPlatformManager;
import com.microsoft.azure.management.resources.Subscription;
import com.microsoft.azure.management.resources.Tenant;
import com.microsoft.azuretools.authmanage.*;
import com.microsoft.azuretools.exception.AzureRuntimeException;
import com.microsoft.azuretools.enums.ErrorEnum;
import com.microsoft.azuretools.telemetry.TelemetryInterceptor;
import com.microsoft.azuretools.utils.AzureRegisterProviderNamespaces;
import com.microsoft.azuretools.utils.Pair;
import org.apache.commons.lang3.StringUtils;
import rx.Observable;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.microsoft.azuretools.authmanage.Environment.*;

/**
 * Created by vlashch on 1/27/17.
 */
public abstract class AzureManagerBase implements AzureManager {
    private static final String CHINA_PORTAL = "https://portal.azure.cn";
    private static final String GLOBAL_PORTAL = "https://ms.portal.azure.com";

    private static final String CHINA_SCM_SUFFIX = ".scm.chinacloudsites.cn";
    private static final String GLOBAL_SCM_SUFFIX = ".scm.azurewebsites.net";

    private static final Logger LOGGER = Logger.getLogger(AzureManagerBase.class.getName());

    protected Map<String, Azure> sidToAzureMap = new ConcurrentHashMap<>();
    protected Map<String, AppPlatformManager> sidToAzureSpringCloudManagerMap = new ConcurrentHashMap<>();
    protected Map<String, InsightsManager> sidToInsightsManagerMap = new ConcurrentHashMap<>();
    protected final SubscriptionManager subscriptionManager;
    protected static final Settings settings = new Settings();

    protected AzureManagerBase() {
        this.subscriptionManager = new SubscriptionManagerPersist(this);
    }

    @Override
    public String getPortalUrl() {
        Environment env = getEnvironment();
        if (GLOBAL.equals(env)) {
            return GLOBAL_PORTAL;
        } else if (CHINA.equals(env)) {
            return CHINA_PORTAL;
        } else if (GERMAN.equals(env)) {
            return AzureEnvironment.AZURE_GERMANY.portal();
        } else if (US_GOVERNMENT.equals(env)) {
            return AzureEnvironment.AZURE_US_GOVERNMENT.portal();
        } else {
            return env.getAzureEnvironment().portal();
        }
    }

    @Override
    public String getScmSuffix() {
        Environment env = getEnvironment();
        if (GLOBAL.equals(env)) {
            return GLOBAL_SCM_SUFFIX;
        } else if (CHINA.equals(env)) {
            return CHINA_SCM_SUFFIX;
        } else {
            return GLOBAL_SCM_SUFFIX;
        }
    }

    @Override
    public String getTenantIdBySubscription(String subscriptionId) throws IOException {
        final Pair<Subscription, Tenant> subscriptionTenantPair = getSubscriptionsWithTenant().stream()
                .filter(pair -> pair != null && pair.first() != null && pair.second() != null)
                .filter(pair -> StringUtils.equals(pair.first().subscriptionId(), subscriptionId))
                .findFirst().orElseThrow(() -> new IOException("Failed to find storage subscription id"));
        return subscriptionTenantPair.second().tenantId();
    }

    protected <T extends AzureConfigurable<T>> T buildAzureManager(AzureConfigurable<T> configurable) {
        return configurable.withInterceptor(new TelemetryInterceptor())
                .withUserAgent(CommonSettings.USER_AGENT);
    }

    @Override
    public List<Subscription> getSubscriptions() throws IOException {
        return getSubscriptionsWithTenant().stream().map(Pair::first).collect(Collectors.toList());
    }

    @Override
    public List<Pair<Subscription, Tenant>> getSubscriptionsWithTenant() throws IOException {
        final List<Pair<Subscription, Tenant>> subscriptions = new LinkedList<>();
        final Azure.Authenticated authentication = authTenant(getCurrentTenantId());
        // could be multi tenant - return all subscriptions for the current account
        final List<Tenant> tenants = getTenants(authentication);
        for (Tenant tenant : tenants) {
            final Azure.Authenticated tenantAuthentication = authTenant(tenant.tenantId());
            final List<Subscription> tenantSubscriptions = getSubscriptions(tenantAuthentication);
            for (Subscription subscription : tenantSubscriptions) {
                subscriptions.add(new Pair<>(subscription, tenant));
            }
        }
        return subscriptions;
    }

    @Override
    public Azure getAzure(String sid) throws IOException {
        if (!isSignedIn()) {
            return null;
        }
        if (sidToAzureMap.containsKey(sid)) {
            return sidToAzureMap.get(sid);
        }
        final String tid = this.subscriptionManager.getSubscriptionTenant(sid);
        final Azure azure = authTenant(tid).withSubscription(sid);
        // TODO: remove this call after Azure SDK properly implements handling of unregistered provider namespaces
        AzureRegisterProviderNamespaces.registerAzureNamespaces(azure);
        sidToAzureMap.put(sid, azure);
        return azure;
    }

    @Override
    public AppPlatformManager getAzureSpringCloudClient(String sid) throws IOException {
        if (!isSignedIn()) {
            return null;
        }
        return sidToAzureSpringCloudManagerMap.computeIfAbsent(sid, s -> {
            String tid = this.subscriptionManager.getSubscriptionTenant(sid);
            return authSpringCloud(sid, tid);
        });
    }

    @Override
    public InsightsManager getInsightsManager(String sid) throws IOException {
        if (!isSignedIn()) {
            return null;
        }
        return sidToInsightsManagerMap.computeIfAbsent(sid, s -> {
            String tid = this.subscriptionManager.getSubscriptionTenant(sid);
            return authApplicationInsights(sid, tid);
        });
    }

    @Override
    public SubscriptionManager getSubscriptionManager() {
        return this.subscriptionManager;
    }

    @Override
    public Environment getEnvironment() {
        if (!isSignedIn()) {
            return null;
        }
        return CommonSettings.getEnvironment();
    }

    @Override
    public String getManagementURI() throws IOException {
        if (!isSignedIn()) {
            return null;
        }
        // environments other than global cloud are not supported for interactive login for now
        return getEnvironment().getAzureEnvironment().resourceManagerEndpoint();
    }

    public String getStorageEndpointSuffix() {
        if (!isSignedIn()) {
            return null;
        }
        return getEnvironment().getAzureEnvironment().storageEndpointSuffix();
    }

    @Override
    public Settings getSettings() {
        return settings;
    }

    @Override
    public void drop() throws IOException {
        LOGGER.log(Level.INFO, "ServicePrincipalAzureManager.drop()");
        this.subscriptionManager.cleanSubscriptions();
    }

    protected abstract String getCurrentTenantId() throws IOException;

    protected boolean isSignedIn() {
        return false;
    }

    protected AzureTokenCredentials getCredentials(String tenantId) {
        return new RefreshableTokenCredentials(this, tenantId);
    }

    public List<Tenant> getTenants(String tenantId) {
        return getTenants(authTenant(tenantId));
    }

    public List<Subscription> getSubscriptions(String tenantId) {
        return getSubscriptions(authTenant(tenantId));
    }

    private List<Subscription> getSubscriptions(Azure.Authenticated tenantAuthentication) {
        return tenantAuthentication.subscriptions().listAsync()
                .onErrorResumeNext(err -> {
                    LOGGER.warning(err.getMessage());
                    return Observable.empty();
                })
                .toList()
                .toBlocking()
                .singleOrDefault(Collections.emptyList());
    }

    private List<Tenant> getTenants(Azure.Authenticated authentication) {
        try {
            return authentication.tenants().listAsync()
                    .toList()
                    .toBlocking()
                    .singleOrDefault(Collections.emptyList());
        } catch (Exception err) {
            LOGGER.warning(Throwables.getStackTraceAsString(err));
            if (Throwables.getCausalChain(err).stream().filter(e -> e instanceof UnknownHostException).count() > 0) {
                throw new AzureRuntimeException(ErrorEnum.UNKNOWN_HOST_EXCEPTION);
            } else if (err instanceof AzureRuntimeException) {
                throw err;
            }
            return Collections.emptyList();
        }
    }

    protected Azure.Authenticated authTenant(String tenantId) {
        final AzureTokenCredentials credentials = getCredentials(tenantId);
        return Azure.configure()
                .withInterceptor(new TelemetryInterceptor())
                .withUserAgent(CommonSettings.USER_AGENT)
                .authenticate(credentials);
    }

    protected AppPlatformManager authSpringCloud(String subscriptionId, String tenantId) {
        final AzureTokenCredentials credentials = getCredentials(tenantId);
        return buildAzureManager(AppPlatformManager.configure())
                .authenticate(credentials, subscriptionId);
    }

    protected InsightsManager authApplicationInsights(String subscriptionId, String tenantId) {
        final AzureTokenCredentials credentials = getCredentials(tenantId);
        return buildAzureManager(InsightsManager.configure())
                .authenticate(credentials, subscriptionId);
    }
}
