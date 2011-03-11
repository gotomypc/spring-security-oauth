/*
 * Copyright 2008-2009 Web Cohesion
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.oauth2.config;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.security.access.SecurityConfig;
import org.springframework.security.config.BeanIds;
import org.springframework.security.oauth2.consumer.OAuth2ClientContextFilter;
import org.springframework.security.oauth2.consumer.OAuth2ClientProcessingFilter;
import org.springframework.security.oauth2.consumer.OAuth2ProfileChain;
import org.springframework.security.oauth2.consumer.rememberme.HttpSessionOAuth2RememberMeServices;
import org.springframework.security.oauth2.consumer.token.InMemoryOAuth2ClientTokenServices;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.DefaultFilterInvocationSecurityMetadataSource;
import org.springframework.security.web.access.intercept.RequestKey;
import org.springframework.security.web.util.AntUrlPathMatcher;
import org.springframework.security.web.util.RegexUrlPathMatcher;
import org.springframework.security.web.util.UrlMatcher;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for the OAuth "provider" element.
 *
 * @author Ryan Heaton
 */
public class OAuth2ClientBeanDefinitionParser implements BeanDefinitionParser {

  public BeanDefinition parse(Element element, ParserContext parserContext) {
    BeanDefinition filterChainProxy = parserContext.getRegistry().getBeanDefinition(BeanIds.FILTER_CHAIN_PROXY);
    Map filterChainMap = (Map) filterChainProxy.getPropertyValues().getPropertyValue("filterChainMap").getValue();
    List<BeanMetadataElement> filterChain = findFilterChain(filterChainMap);

    if (filterChain == null) {
      throw new IllegalStateException("Unable to find the filter chain for the universal pattern matcher where the oauth filters are to be inserted.");
    }

    String tokenServicesRef = element.getAttribute("token-services-ref");
    String resourceDetailsServiceRef = element.getAttribute("resource-details-service-ref");
    String rememberMeServicesRef = element.getAttribute("remember-me-services-ref");
    String profileManagerRef = element.getAttribute("profile-manager-ref");
    String requireAuthenticated = element.getAttribute("require-authenticated");
    String redirectStrategyRef = element.getAttribute("redirect-strategy-ref");

    if (!StringUtils.hasText(tokenServicesRef)) {
      tokenServicesRef = "oauth2ClientTokenServices";
      BeanDefinitionBuilder tokenServices = BeanDefinitionBuilder.rootBeanDefinition(InMemoryOAuth2ClientTokenServices.class);
      parserContext.getRegistry().registerBeanDefinition(tokenServicesRef, tokenServices.getBeanDefinition());
    }

    if (!StringUtils.hasText(rememberMeServicesRef)) {
      rememberMeServicesRef = "oauth2ClientRememberMeServices";
      BeanDefinitionBuilder rememberMeServices = BeanDefinitionBuilder.rootBeanDefinition(HttpSessionOAuth2RememberMeServices.class);
      parserContext.getRegistry().registerBeanDefinition(rememberMeServicesRef, rememberMeServices.getBeanDefinition());
    }

    if (!StringUtils.hasText(resourceDetailsServiceRef)) {
      resourceDetailsServiceRef = "oauth2ResourceDetailsService";
      BeanDefinitionBuilder resourceDetailsService = BeanDefinitionBuilder.rootBeanDefinition(ResourceDetailsServiceFactoryBean.class);
      parserContext.getRegistry().registerBeanDefinition(resourceDetailsServiceRef, resourceDetailsService.getBeanDefinition());
    }

    if (!StringUtils.hasText(profileManagerRef)) {
      profileManagerRef = "oauth2ClientProfileManager";
      BeanDefinitionBuilder flowManager = BeanDefinitionBuilder.rootBeanDefinition(OAuth2ProfileChain.class);
      if ("false".equalsIgnoreCase(redirectStrategyRef)) {
        flowManager.addPropertyValue("requireAuthenticated", "false");
      }
      flowManager.addPropertyReference("tokenServices", tokenServicesRef);
      parserContext.getRegistry().registerBeanDefinition(profileManagerRef, flowManager.getBeanDefinition());
    }

    BeanDefinitionBuilder clientContextFilterBean = BeanDefinitionBuilder.rootBeanDefinition(OAuth2ClientContextFilter.class);
    clientContextFilterBean.addPropertyReference("profileManager", profileManagerRef);
    clientContextFilterBean.addPropertyReference("rememberMeServices", rememberMeServicesRef);

    if (StringUtils.hasText(redirectStrategyRef)) {
      clientContextFilterBean.addPropertyReference("redirectStrategy", redirectStrategyRef);
    }

    int filterIndex = insertIndex(filterChain);
    parserContext.getRegistry().registerBeanDefinition("oauth2ClientContextFilter", clientContextFilterBean.getBeanDefinition());
    filterChain.add(filterIndex++, new RuntimeBeanReference("oauth2ClientContextFilter"));

    List filterPatterns = DomUtils.getChildElementsByTagName(element, "url");
    if (!filterPatterns.isEmpty()) {
      BeanDefinitionBuilder consumerFilterBean = BeanDefinitionBuilder.rootBeanDefinition(OAuth2ClientProcessingFilter.class);

      String patternType = element.getAttribute("path-type");
      if (!StringUtils.hasText(patternType)) {
        patternType = "ant";
      }

      boolean useRegex = patternType.equals("regex");

      UrlMatcher matcher = new AntUrlPathMatcher();
      if (useRegex) {
        matcher = new RegexUrlPathMatcher();
      }

      // Deal with lowercase conversion requests
      String lowercaseComparisons = element.getAttribute("lowercase-comparisons");
      if (!StringUtils.hasText(lowercaseComparisons)) {
        lowercaseComparisons = null;
      }

      if ("true".equals(lowercaseComparisons)) {
        if (useRegex) {
          ((RegexUrlPathMatcher) matcher).setRequiresLowerCaseUrl(true);
        }
      }
      else if ("false".equals(lowercaseComparisons)) {
        if (!useRegex) {
          ((AntUrlPathMatcher) matcher).setRequiresLowerCaseUrl(false);
        }
      }

      LinkedHashMap invocationDefinitionMap = new LinkedHashMap();
      Iterator filterPatternIt = filterPatterns.iterator();

      boolean useLowerCasePaths = (matcher instanceof AntUrlPathMatcher) && matcher.requiresLowerCaseUrl();
      while (filterPatternIt.hasNext()) {
        Element filterPattern = (Element) filterPatternIt.next();

        String path = filterPattern.getAttribute("pattern");
        if (!StringUtils.hasText(path)) {
          parserContext.getReaderContext().error("pattern attribute cannot be empty or null", filterPattern);
        }

        if (useLowerCasePaths) {
          path = path.toLowerCase();
        }

        String method = filterPattern.getAttribute("httpMethod");
        if (!StringUtils.hasText(method)) {
          method = null;
        }

        // Convert the comma-separated list of access attributes to a ConfigAttributeDefinition
        String access = filterPattern.getAttribute("resources");
        if (StringUtils.hasText(access)) {
          invocationDefinitionMap.put(new RequestKey(path, method), SecurityConfig.createList(StringUtils.commaDelimitedListToStringArray(access)));
        }
      }

      DefaultFilterInvocationSecurityMetadataSource source = new DefaultFilterInvocationSecurityMetadataSource(matcher, invocationDefinitionMap);
      source.setStripQueryStringFromUrls(true); //see https://jira.springsource.org/browse/SECOAUTH-18
      consumerFilterBean.addPropertyValue("objectDefinitionSource", source);
      consumerFilterBean.addPropertyReference("resourceDetailsService", resourceDetailsServiceRef);
      parserContext.getRegistry().registerBeanDefinition("oauth2ClientSecurityFilter", consumerFilterBean.getBeanDefinition());
      filterChain.add(filterIndex++, new RuntimeBeanReference("oauth2ClientSecurityFilter"));
    }

    return null;
  }

  protected List<BeanMetadataElement> findFilterChain(Map filterChainMap) {
    //the filter chain we want is the last one in the sorted map.
    Iterator valuesIt = filterChainMap.values().iterator();
    while (valuesIt.hasNext()) {
      List<BeanMetadataElement> filterChain = (List<BeanMetadataElement>) valuesIt.next();
      if (!valuesIt.hasNext()) {
        return filterChain;
      }
    }

    return null;
  }

  /**
   * Attempts to find the place in the filter chain to insert the spring security oauth filters. Currently,
   * these filters are inserted after the ExceptionTranslationFilter.
   *
   * @param filterChain The filter chain configuration.
   * @return The insert index.
   */
  private int insertIndex(List<BeanMetadataElement> filterChain) {
    int i;
    for (i = 0; i < filterChain.size(); i++) {
      BeanMetadataElement filter = filterChain.get(i);
      if (filter instanceof BeanDefinition) {
        String beanName = ((BeanDefinition) filter).getBeanClassName();
        if (beanName.equals(ExceptionTranslationFilter.class.getName())) {
          return i + 1;
        }
      }
    }
    return filterChain.size();
  }
}