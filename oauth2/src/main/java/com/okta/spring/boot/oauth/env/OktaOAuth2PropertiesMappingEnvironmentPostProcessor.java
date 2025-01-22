/*
 * Copyright 2017 Okta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.okta.spring.boot.oauth.env;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.*;

import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * This {@link EnvironmentPostProcessor} configures additional {@link PropertySource}s that map OIDC discovery metadata
 * and standard Okta properties to standard Spring Boot OAuth2 properties.
 *
 * <p>
 * <table summary="Property mapping">
 *     <tr>
 *         <th>Okta Property</th>
 *         <th>Spring Boot Property</th>
 *     </tr>
 *     <tr>
 *         <td>okta.oauth2.client-id</td>
 *         <td>spring.security.oauth2.client.registration.okta.client-id</td>
 *     </tr>
 *     <tr>
 *         <td>okta.oauth2.client-secret</td>
 *         <td>spring.security.oauth2.client.registration.okta.client-secret
 *     </tr>
 *     <tr>
 *         <td>okta.oauth2.scopes</td>
 *         <td>spring.security.oauth2.client.registration.okta.scope</td></td>
 *     </tr>
 *     <tr>
 *         <td>${okta.oauth2.issuer}/v1/authorize</td>
 *         <td>spring.security.oauth2.client.provider.okta.authorization-uri</td></td>
 *     </tr>
 *     <tr>
 *         <td>${okta.oauth2.issuer}/v1/token</td>
 *         <td>spring.security.oauth2.client.provider.okta.token-uri</td></td>
 *     </tr>
 *     <tr>
 *         <td>${okta.oauth2.issuer}/v1/userinfo</td>
 *         <td>spring.security.oauth2.client.provider.okta.user-info-uri</td></td>
 *     </tr>
 *     <tr>
 *         <td>${okta.oauth2.issuer}/v1/keys</td>
 *         <td>spring.security.oauth2.client.provider.okta.jwk-set-uri</td></td>
 *     </tr>
 *     <tr>
 *         <td>${okta.oauth2.issuer}</td>
 *         <td>spring.security.oauth2.resourceserver.jwt.issuer-uri</td></td>
 *     </tr>
 *     <tr>
 *         <td>${okta.oauth2.issuer}/v1/keys</td>
 *         <td>spring.security.oauth2.resourceserver.jwt.jwk-set-uri</td></td>
 *     </tr>
 *     <tr>
 *         <td>${okta.oauth2.clientId}</td>
 *         <td>spring.security.oauth2.resourceserver.opaquetoken.client-id</td></td>
 *     </tr>
 *     <tr>
 *         <td>${okta.oauth2.clientSecret}</td>
 *         <td>spring.security.oauth2.resourceserver.opaquetoken.client-secret</td></td>
 *     </tr>
 *     <tr>
 *         <td>${okta.oauth2.introspectionUri}</td>
 *         <td>spring.security.oauth2.resourceserver.opaquetoken.introspection-uri</td></td>
 *     </tr>
 * </table>
 *
 * @since 0.2.0
 */
final class OktaOAuth2PropertiesMappingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final DeferredLog log = new DeferredLog();

    private static final String OKTA_OAUTH_PREFIX = "okta.oauth2.";
    private static final String OKTA_OAUTH_ISSUER = OKTA_OAUTH_PREFIX + "issuer";
    private static final String OKTA_OAUTH_ISSUER_WITH_PATH = OKTA_OAUTH_ISSUER + "-with-path";
    private static final String OKTA_OAUTH_CLIENT_ID = OKTA_OAUTH_PREFIX + "client-id";
    private static final String OKTA_OAUTH_CLIENT_SECRET = OKTA_OAUTH_PREFIX + "client-secret";
    private static final String OKTA_OAUTH_SCOPES = OKTA_OAUTH_PREFIX + "scopes"; // array vs string

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {

        OIDCMetadata oidcMetadata;
        try {
            String issuer = environment.getProperty(OKTA_OAUTH_ISSUER);
            if (issuer != null) {
                RestTemplate restTemplate = new RestTemplate();
                if (!issuer.endsWith("/")) {
                    issuer += "/";
                }
                ResponseEntity<String> response
                    = restTemplate.getForEntity(issuer + ".well-known/openid-configuration", String.class);
                oidcMetadata = new OIDCMetadata(response);
            } else {
                oidcMetadata = new OIDCMetadata(OKTA_OAUTH_ISSUER_WITH_PATH);
            }
        } catch (JsonProcessingException | ResourceAccessException e) {
            log.warn("Failed to process '.well-known/openid-configuration' metadata. Using defaults for Okta");
            oidcMetadata = new OIDCMetadata(OKTA_OAUTH_ISSUER_WITH_PATH);
        }

        // convert okta.oauth2.* properties to long form spring oauth properties
        environment.getPropertySources().addLast(remappedOktaToStandardOAuthPropertySource(environment));
        environment.getPropertySources().addLast(remappedOktaOAuth2ScopesPropertySource(environment));
        // default scopes, as of Spring Security 5.4 default scopes are no longer added, this restores that functionality
        environment.getPropertySources().addLast(defaultOktaScopesSource(environment, Objects.requireNonNull(oidcMetadata)));
        // okta's endpoints can be resolved from an issuer
        environment.getPropertySources().addLast(new OktaIssuerWithPathPropertySource(environment, oidcMetadata.isAuth0()));
        environment.getPropertySources().addLast(oktaStaticDiscoveryPropertySource(environment, oidcMetadata));
        // Auth0 does not have an introspection endpoint
        if (oidcMetadata.getIntrospectionURI() != null) {
            environment.getPropertySources().addLast(oktaOpaqueTokenPropertySource(environment, oidcMetadata));
        }
        environment.getPropertySources().addLast(oktaRedirectUriPropertySource(environment));
        environment.getPropertySources().addLast(otkaForcePkcePropertySource(environment, oidcMetadata));

        if (application != null) {
            // This is required as EnvironmentPostProcessors are run before logging system is initialized
            application.addInitializers(ctx -> log.replayTo(OktaOAuth2PropertiesMappingEnvironmentPostProcessor.class));
        }
    }

    private PropertySource<?> otkaForcePkcePropertySource(ConfigurableEnvironment environment, OIDCMetadata oidcMetadata) {
        Map<String, Object> props = new HashMap<>();
        props.put("spring.security.oauth2.client.registration.okta.client-authentication-method", oidcMetadata.getClientAuthenticationMethod());

        return new ConditionalMapPropertySource("okta-pkce-for-public-clients", props, environment, OKTA_OAUTH_ISSUER, OKTA_OAUTH_CLIENT_ID) {
            @Override
            public boolean containsProperty(String name) {
                return super.containsProperty(name)
                    && !environment.containsProperty("spring.security.oauth2.client.registration.okta.client-secret");
            }
        };
    }

    private PropertySource defaultOktaScopesSource(Environment environment, OIDCMetadata oidcMetadata) {
        Map<String, Object> props = new HashMap<>();
        props.put("spring.security.oauth2.client.registration.okta.scope", oidcMetadata.getScope());
        return new ConditionalMapPropertySource("default-scopes", props, environment, OKTA_OAUTH_ISSUER, OKTA_OAUTH_CLIENT_ID);
    }

    private PropertySource remappedOktaToStandardOAuthPropertySource(Environment environment) {
        Map<String, String> aliasMap = new HashMap<>();

        aliasMap.put("spring.security.oauth2.client.registration.okta.client-id", OKTA_OAUTH_CLIENT_ID);
        aliasMap.put("spring.security.oauth2.client.registration.okta.client-secret", OKTA_OAUTH_CLIENT_SECRET);

        return new RemappedPropertySource("okta-to-oauth2", aliasMap, environment);
    }

    private PropertySource remappedOktaOAuth2ScopesPropertySource(Environment environment) {

        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.security.oauth2.client.registration.okta.scope", "${" + OKTA_OAUTH_SCOPES + "}");
        return new OktaScopesPropertySource("okta-scope-remaper", properties, environment);
    }

    private PropertySource oktaRedirectUriPropertySource(Environment environment) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.security.oauth2.client.registration.okta.redirect-uri", "{baseUrl}${okta.oauth2.redirect-uri}");
        return new ConditionalMapPropertySource("okta-redirect-uri-helper", properties, environment, "okta.oauth2.redirect-uri");
    }

    private PropertySource oktaStaticDiscoveryPropertySource(Environment environment, OIDCMetadata oidcMetadata) {

        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", "${" + OKTA_OAUTH_ISSUER + "}");
        properties.put("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", oidcMetadata.getJwkSetURI());
        properties.put("spring.security.oauth2.client.provider.okta.authorization-uri", oidcMetadata.getAuthorizationURI());
        properties.put("spring.security.oauth2.client.provider.okta.token-uri", oidcMetadata.getTokenURI());
        properties.put("spring.security.oauth2.client.provider.okta.user-info-uri", oidcMetadata.getUserInfoURI());
        properties.put("spring.security.oauth2.client.provider.okta.jwk-set-uri", oidcMetadata.getJwkSetURI());
        properties.put("spring.security.oauth2.client.provider.okta.issuer-uri", "${" + OKTA_OAUTH_ISSUER + "}"); // required for OIDC logout

        return new ConditionalMapPropertySource("okta-static-discovery", properties, environment, OKTA_OAUTH_ISSUER);
    }

    private PropertySource oktaOpaqueTokenPropertySource(Environment environment, OIDCMetadata oidcMetadata) {

        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.security.oauth2.resourceserver.opaque-token.client-id", "${" + OKTA_OAUTH_CLIENT_ID + "}");
        properties.put("spring.security.oauth2.resourceserver.opaque-token.client-secret", "${" + OKTA_OAUTH_CLIENT_SECRET + "}");
        properties.put("spring.security.oauth2.resourceserver.opaque-token.introspection-uri", oidcMetadata.getIntrospectionURI());

        return new ConditionalMapPropertySource("okta-opaque-token", properties, environment, OKTA_OAUTH_ISSUER, OKTA_OAUTH_CLIENT_SECRET);
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 1;
    }

    private static class ConditionalMapPropertySource extends MapPropertySource {

        private final Environment environment;
        private final List<String> conditionalProperties;

        private ConditionalMapPropertySource(String name, Map<String, Object> source, Environment environment, String... conditionalProperties) {
            super(name, source);
            this.environment = environment;
            this.conditionalProperties = Arrays.asList(conditionalProperties);
        }

        @Override
        public Object getProperty(String name) {

            return containsProperty(name)
                ? super.getProperty(name)
                : null;
        }

        @Override
        public boolean containsProperty(String name) {
            return super.containsProperty(name)
                && conditionalProperties.stream().allMatch(environment::containsProperty);
        }
    }

    private static class OktaScopesPropertySource extends MapPropertySource {

        private final Environment environment;

        private OktaScopesPropertySource(String name, Map<String, Object> source, Environment environment) {
            super(name, source);
            this.environment = environment;
        }

        @Override
        public Object getProperty(String name) {

            if (containsProperty(name)) {
                return Binder.get(environment).bind(OKTA_OAUTH_SCOPES, Bindable.setOf(String.class)).orElse(null);
            }
            return null;
        }
    }

    /**
     * PropertySource that resolves <code>okta.oauth2.issuer-with-path</code> property from
     * <code>okta.oauth2.issuer</code>. Okta has two types of authorization servers (issuers), and the URL format is
     * slightly different between them, this PropertySource will dynamically return the base URL for each type at
     * property lookup time.
     */
    private static class OktaIssuerWithPathPropertySource extends PropertySource<String> {

        private final Environment environment;
        private final boolean isAuth0;

        private OktaIssuerWithPathPropertySource(Environment environment, boolean isAuth0) {
            super("okta-issuer-url-resolving-source");
            this.environment = environment;
            this.isAuth0 = isAuth0;
        }

        @Override
        public Object getProperty(@NotNull String key) {

            // only support this key
            if (OKTA_OAUTH_ISSUER_WITH_PATH.equals(key)) {
                // issuer could be null (only resolve properties after checking if the key is `OKTA_OAUTH_ISSUER_WITH_PATH`)
                return Optional.ofNullable(environment.getProperty(OKTA_OAUTH_ISSUER))
                    .map(issuer -> {
                        // Check if URL is an Auth0 org or is an Okta Org Authorization Server
                        if (isAuth0 || issuer.contains("/oauth2")) {
                            // if it's an Auth0 org or if already contains the suffix leave the property as is
                            return issuer;
                        }
                        // for the Okta Org Authorization Server, /oauth2 needs to be appended
                        return issuer + "/oauth2";
                    })
                    // otherwise return null
                    .orElse(null);
            }

            return null;
        }

        @Override
        public boolean containsProperty(@NotNull String key) {
            return OKTA_OAUTH_ISSUER_WITH_PATH.equals(key) && environment.containsProperty(OKTA_OAUTH_ISSUER);
        }
    }
}
